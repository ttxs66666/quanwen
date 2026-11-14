package app.quanwen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 打开一本笔记本：顶上一条工具栏，下面是画布。
 * 工具栏：返回、撤回、还原、三档粗细、黑红蓝三色、圈、移动图片、会话下拉。
 *
 * 圈：用荧光笔圈出问题，停笔 1 秒发给 AI。AI 看到圈所在的整页、圈内放大图，圈在页面上部时再加上一页。
 * 回答框只有一个框：摆在圈的附近、相对稀疏的地方，避开右手放的位置；可以拖标题栏挪开。
 * 「留下」把回答变成一张图贴在纸上。每 3 秒把改过的页存一次，离开页面时马上存。
 */
class NoteActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var book: Notebook
    private lateinit var pages: MutableList<NotePage>
    private lateinit var canvas: NoteCanvas
    private lateinit var layer: FrameLayout
    private lateinit var undoBtn: ToolIcon
    private lateinit var redoBtn: ToolIcon
    private lateinit var circleBtn: ToolIcon
    private lateinit var moveBtn: ToolIcon
    private lateinit var sessionBtn: TextView
    private val widthBtns = ArrayList<ToolIcon>()
    private val colorBtns = ArrayList<ToolIcon>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val autosave = object : Runnable {
        override fun run() { saveDirty(); main.postDelayed(this, SAVE_EVERY_MS) }
    }

    // 回答框：贴在某一页的某个位置（页面坐标），随纸张滚动
    private var popup: AnswerView? = null
    private var popupQa: Qa? = null
    private var popupPage = -1
    private val popupAt = PointF()

    private val onQaChanged: (Qa, Boolean) -> Unit = { qa, _ ->
        if (qa === popupQa) popup?.refresh(qa)
        refreshSession()
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NoteStore.init(this)
        Engine.init(this)
        prefs = Prefs(this)
        book = NoteStore.get(intent.getStringExtra(EXTRA_ID) ?: "") ?: run { finish(); return }
        pages = book.pageIds.map { NoteStore.loadPage(book, it) }.toMutableList()
        if (pages.isEmpty()) pages.add(NoteStore.addPage(book))
        canvas = NoteCanvas(this, book, pages) { refreshButtons() }
        canvas.penColor = NotePaper.COLORS[prefs.noteColor.coerceIn(0, 2)]
        canvas.penWidth = NotePaper.WIDTHS[prefs.noteWidth.coerceIn(0, 2)]
        canvas.onToolChanged = { refreshButtons() }
        canvas.onCircle = { pi, strokes -> askAbout(pi, strokes) }
        canvas.onViewport = { placePopupView() }

        window.statusBarColor = TOOLBAR
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(TOOLBAR) }
        root.addView(toolbar())
        root.addView(View(this).apply { setBackgroundColor(0x14000000) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1f)))
        layer = FrameLayout(this)
        layer.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(layer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Engine.addListener(onQaChanged)
        refreshButtons()
    }

    override fun onResume() {
        super.onResume()
        FloatHost.current?.setSuspended(true)   // 笔记本里有自己的「圈」，悬浮条先收起来
        refreshSession()
        main.postDelayed(autosave, SAVE_EVERY_MS)
    }

    override fun onPause() {
        super.onPause()
        FloatHost.current?.setSuspended(false)
        main.removeCallbacks(autosave)
        if (::canvas.isInitialized) saveDirty()
    }

    override fun onDestroy() {
        Engine.removeListener(onQaChanged)
        closePopup()
        if (::canvas.isInitialized) canvas.clearCache()
        io.shutdown()   // 已经排队的保存会做完
        super.onDestroy()
    }

    /** 把改过的页写到文件（在后台线程写一份快照）。 */
    private fun saveDirty() {
        var any = false
        for (p in pages) if (p.dirty) {
            p.dirty = false
            val strokes = ArrayList(p.strokes)
            val images = p.images.map { NoteImage(it.id, it.x, it.y, it.w, it.h) }
            io.execute { try { NoteStore.writePage(book, p.id, strokes, images) } catch (_: Exception) { p.dirty = true } }
            any = true
        }
        if (any) { book.modified = System.currentTimeMillis(); NoteStore.saveIndex() }
    }

    // ---------- 圈题 → AI ----------

    private fun askAbout(pi: Int, strokes: List<FloatArray>) {
        if (prefs.apiKey.isBlank()) { Toast.makeText(this, R.string.no_key, Toast.LENGTH_LONG).show(); return }
        val w = ImageUtil.PAGE_MAX
        val k = w / NotePaper.PAGE_W
        val pageBmp = canvas.renderPage(pi, w)
        val px = strokes.map { a -> FloatArray(a.size) { a[it] * k } }
        val b = ImageUtil.bounds(strokes)
        // 圈在页面上部：他可能在问上一页的东西，把上一页也给 AI
        val prev = if (pi > 0 && b.top < NotePaper.PAGE_H * PREV_PAGE_ZONE) canvas.renderPage(pi - 1, w) else null
        val qa = Engine.ask(pageBmp, px, screenBounds = null, recycleWhenDone = true,
            marker = ImageUtil.MARK_HIGHLIGHT, prevPage = prev, origin = Qa.ORIGIN_NOTE)
        showPopup(qa, pi, b)
    }

    private fun showPopup(qa: Qa, pi: Int, circle: RectF) {
        closePopup()
        val v = AnswerView(this, fill = false,
            maxBodyHeight = { (layer.height * 0.42f).toInt() },
            onClose = { closePopup() },
            onResized = { placePopupView() },
            minimal = true,
            extraLabel = { getString(R.string.note_keep) },
            onExtra = { keepPopup() })
        val w = popupWidth()
        layer.addView(v, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        v.elevation = dp(6f)
        popup = v; popupQa = qa; popupPage = pi
        val at = PopupPlacer.place(canvas, pi, circle, w.toFloat(), dp(210f), layer.width.toFloat(), layer.height.toFloat(), dp(1f))
        popupAt.set(at)
        dragByHeader(v)
        v.showQa(qa)
        placePopupView()
    }

    private fun popupWidth() = (layer.width * 0.42f).roundToInt().coerceIn(dpi(300f), max(dpi(300f), min(dpi(460f), layer.width - dpi(32f))))

    private fun placePopupView() {
        val v = popup ?: return
        if (popupPage !in 0 until canvas.pageCount()) return
        val p = canvas.toView(popupPage, popupAt.x, popupAt.y)
        v.x = p.x; v.y = p.y
    }

    private fun closePopup() {
        val v = popup ?: return
        layer.removeView(v)
        v.destroy()
        popup = null; popupQa = null; popupPage = -1
    }

    /** 拖标题栏挪开回答框（位置记在页面坐标里，滚动时跟着纸走）。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragByHeader(v: AnswerView) {
        var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f; var moved = false
        v.header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = v.x; startY = v.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || abs(dx) + abs(dy) > dp(6f)) {
                        moved = true
                        val nx = (startX + dx).coerceIn(0f, max(0f, layer.width - v.width.toFloat()))
                        val ny = (startY + dy).coerceIn(-v.height / 2f, layer.height - dp(40f))
                        val pp = canvas.toPage(popupPage, nx, ny)
                        popupAt.set(pp)
                        placePopupView()
                    }
                    true
                }
                else -> moved
            }
        }
    }

    /** 「留下」：把回答正文截成一张图，贴在回答框所在的位置。 */
    private fun keepPopup() {
        val v = popup ?: return
        val pi = popupPage
        val body = v.body
        val loc = IntArray(2); body.getLocationInWindow(loc)
        val win = Rect(); window.decorView.getWindowVisibleDisplayFrame(win)
        val src = Rect(loc[0], loc[1], loc[0] + body.width, loc[1] + body.height)
        if (!src.intersect(0, 0, window.decorView.width, window.decorView.height) || src.width() < 8 || src.height() < 8) {
            Toast.makeText(this, R.string.note_keep_failed, Toast.LENGTH_SHORT).show(); return
        }
        val shot = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
        val bodyTopInPopup = body.top.toFloat()
        PixelCopy.request(window, src, shot, { result ->
            if (result != PixelCopy.SUCCESS) { shot.recycle(); Toast.makeText(this, R.string.note_keep_failed, Toast.LENGTH_SHORT).show(); return@request }
            val pad = dpi(10f)
            val card = Bitmap.createBitmap(shot.width + 2 * pad, shot.height + 2 * pad, Bitmap.Config.ARGB_8888)
            val c = Canvas(card)
            val r = RectF(dp(1f), dp(1f), card.width - dp(1f), card.height - dp(1f))
            c.drawRoundRect(r, dp(10f), dp(10f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.PAPER })
            c.drawBitmap(shot, pad.toFloat(), pad.toFloat(), null)
            c.drawRoundRect(r, dp(10f), dp(10f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = 0x33000000
            })
            shot.recycle()
            val id = try { NoteStore.saveImage(book, card) } catch (_: Exception) { null }
            if (id == null) { card.recycle(); Toast.makeText(this, R.string.note_keep_failed, Toast.LENGTH_SHORT).show(); return@request }
            // 贴在回答正文原来的位置（页面坐标），超出纸张就挪回来
            var w = card.width / canvas.scale; var h = card.height / canvas.scale
            card.recycle()
            if (w > NotePaper.PAGE_W) { h *= NotePaper.PAGE_W / w; w = NotePaper.PAGE_W }
            if (h > NotePaper.PAGE_H) { w *= NotePaper.PAGE_H / h; h = NotePaper.PAGE_H }
            val x = (popupAt.x - pad / canvas.scale).coerceIn(0f, NotePaper.PAGE_W - w)
            val y = (popupAt.y + (bodyTopInPopup - pad) / canvas.scale).coerceIn(0f, NotePaper.PAGE_H - h)
            canvas.addImage(pi, NoteImage(id, x, y, w, h))
            closePopup()
            Toast.makeText(this, R.string.note_kept, Toast.LENGTH_LONG).show()
        }, main)
    }

    // ---------- 工具栏 ----------

    private fun toolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(8f), dpi(4f), dpi(12f), dpi(4f))
        }
        bar.addView(icon(ToolIcon.BACK) { finish() })
        bar.addView(TextView(this).apply {
            text = book.title; textSize = 16f; setTextColor(Colors.INK); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            maxWidth = dpi(200f); setPadding(dpi(4f), 0, dpi(12f), 0)
        })
        bar.addView(divider())
        undoBtn = icon(ToolIcon.UNDO) { canvas.undo() }.apply { contentDescription = getString(R.string.note_undo) }
        redoBtn = icon(ToolIcon.REDO) { canvas.redo() }.apply { contentDescription = getString(R.string.note_redo) }
        bar.addView(undoBtn); bar.addView(redoBtn)
        bar.addView(divider())
        for (i in 0..2) {
            val b = icon(ToolIcon.WIDTH, i) {
                prefs.noteWidth = i; canvas.penWidth = NotePaper.WIDTHS[i]; canvas.tool = NoteCanvas.Tool.PEN; refreshButtons()
            }
            widthBtns.add(b); bar.addView(b)
        }
        bar.addView(divider())
        for (i in 0..2) {
            val b = icon(ToolIcon.COLOR, NotePaper.COLORS[i]) {
                prefs.noteColor = i; canvas.penColor = NotePaper.COLORS[i]; canvas.tool = NoteCanvas.Tool.PEN; refreshButtons()
            }
            colorBtns.add(b); bar.addView(b)
        }
        bar.addView(divider())
        circleBtn = icon(ToolIcon.CIRCLE) {
            if (canvas.tool == NoteCanvas.Tool.CIRCLE) canvas.tool = NoteCanvas.Tool.PEN
            else { canvas.tool = NoteCanvas.Tool.CIRCLE; Toast.makeText(this, R.string.note_circle_hint, Toast.LENGTH_SHORT).show() }
        }.apply { contentDescription = getString(R.string.note_circle) }
        moveBtn = icon(ToolIcon.MOVE) {
            if (canvas.tool == NoteCanvas.Tool.MOVE) canvas.tool = NoteCanvas.Tool.PEN
            else { canvas.tool = NoteCanvas.Tool.MOVE; Toast.makeText(this, R.string.note_move_hint, Toast.LENGTH_SHORT).show() }
        }.apply { contentDescription = getString(R.string.note_move) }
        bar.addView(circleBtn); bar.addView(moveBtn)
        bar.addView(divider())
        sessionBtn = TextView(this).apply {
            textSize = 14f; setTextColor(Colors.INK); gravity = Gravity.CENTER; maxLines = 1
            setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(8f))
            background = rounded(0x0F000000, dp(16f), 0x22000000, dpi(1f))
            setOnClickListener { v -> sessionMenu(v) }
        }
        bar.addView(sessionBtn)
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }
    }

    private fun refreshButtons() {
        undoBtn.isEnabled = canvas.canUndo; undoBtn.alpha = if (canvas.canUndo) 1f else 0.3f
        redoBtn.isEnabled = canvas.canRedo; redoBtn.alpha = if (canvas.canRedo) 1f else 0.3f
        val pen = canvas.tool == NoteCanvas.Tool.PEN
        widthBtns.forEachIndexed { i, b -> b.on = pen && NotePaper.WIDTHS[i] == canvas.penWidth }
        colorBtns.forEachIndexed { i, b -> b.on = NotePaper.COLORS[i] == canvas.penColor }
        circleBtn.on = canvas.tool == NoteCanvas.Tool.CIRCLE
        moveBtn.on = canvas.tool == NoteCanvas.Tool.MOVE
    }

    /** 工具栏上显示当前会话：第一道题的题目（太长就截短），还没有题就是「新会话」。 */
    private fun refreshSession() {
        if (!::sessionBtn.isInitialized) return
        val sn = Engine.store.sessions().firstOrNull { it.id == Engine.store.currentSessionId }
        val name = plainMath(sn?.title ?: "").ifBlank { getString(R.string.session_untitled) }
        sessionBtn.text = getString(R.string.note_session, if (name.length > 10) name.take(10) + "…" else name)
    }

    /** 会话下拉：新建会话、切换到以前的会话。 */
    private fun sessionMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 0, 0, getString(R.string.session_new))
        val list = Engine.store.sessions().take(15)
        val cur = Engine.store.currentSessionId
        list.forEachIndexed { i, sn ->
            val name = plainMath(sn.title).ifBlank { getString(R.string.session_untitled) }
            val label = (if (sn.id == cur) "✓ " else "   ") + name + "  · " + getString(R.string.session_count, sn.qas.size)
            menu.menu.add(0, i + 1, i + 1, label)
        }
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId == 0) {
                Engine.store.newSession()
                Toast.makeText(this, R.string.new_session_done, Toast.LENGTH_SHORT).show()
            } else list.getOrNull(item.itemId - 1)?.let { Engine.store.switchSession(it.id) }
            refreshSession()
            true
        }
        menu.show()
    }

    private fun icon(kind: Int, arg: Int = 0, onClick: () -> Unit) = ToolIcon(this, kind, arg).apply {
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dpi(46f), dpi(46f)).apply { marginStart = dpi(2f); marginEnd = dpi(2f) }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0x1A000000)
        layoutParams = LinearLayout.LayoutParams(dpi(1f), dpi(24f)).apply { marginStart = dpi(8f); marginEnd = dpi(8f) }
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val SAVE_EVERY_MS = 3000L
        private const val TOOLBAR = 0xFFF8F7F4.toInt()
        /** 圈的上沿在页面上部这个比例以内时，把上一页也发给 AI。 */
        private const val PREV_PAGE_ZONE = 0.35f

        fun open(ctx: Context, id: String) =
            ctx.startActivity(Intent(ctx, NoteActivity::class.java).putExtra(EXTRA_ID, id))
    }
}

/**
 * 给回答框找位置：只在圈的周围挑（左、上、左上、右上、下、左下等），不会跑远；
 * 在这些候选里挑墨迹相对少的，避开右手放的位置（圈右边往下的扇形区域），也不盖住圈本身。
 */
object PopupPlacer {
    /** 返回回答框左上角的页面坐标。w、h、viewW、viewH、unit（1dp）都是像素。 */
    fun place(cv: NoteCanvas, pi: Int, circle: RectF, w: Float, h: Float, viewW: Float, viewH: Float, unit: Float): PointF {
        val a = cv.toView(pi, circle.left, circle.top)
        val b = cv.toView(pi, circle.right, circle.bottom)
        val c = RectF(a.x, a.y, b.x, b.y)
        val g = 16 * unit
        val cands = listOf(
            PointF(c.left - g - w, c.centerY() - h / 2),   // 左
            PointF(c.left - g - w, c.top),                 // 左，顶对齐
            PointF(c.left - g - w, c.bottom - h),          // 左，底对齐
            PointF(c.centerX() - w / 2, c.top - g - h),    // 上
            PointF(c.left - w * 0.6f, c.top - g - h),      // 左上
            PointF(c.right - w * 0.4f, c.top - g - h),     // 右上（在扇形上方）
            PointF(c.right + g, c.top - h * 0.85f),        // 右边偏上
            PointF(c.left - w, c.bottom + g),              // 左下
            PointF(c.centerX() - w / 2, c.bottom + g),     // 下
        )
        val grid = cv.inkGrid(pi)
        val pad = 8 * unit
        var best: PointF? = null
        var bestScore = Float.MAX_VALUE
        for (p in cands) {
            val x = p.x.coerceIn(pad, max(pad, viewW - w - pad))
            val y = p.y.coerceIn(pad, max(pad, viewH - h - pad))
            val r = RectF(x, y, x + w, y + h)
            val score = 10f * overlap(r, c) +
                3f * handZone(r, c, viewW) +
                1.5f * density(cv, pi, r, grid) +
                0.8f * hypot(r.centerX() - c.centerX(), r.centerY() - c.centerY()) / viewW
            if (score < bestScore) { bestScore = score; best = PointF(x, y) }
        }
        val p = best ?: PointF(pad, pad)
        return cv.toPage(pi, p.x, p.y)
    }

    /** r 盖住圈的面积占圈的比例。 */
    private fun overlap(r: RectF, c: RectF): Float {
        val i = RectF()
        if (!i.setIntersect(r, c)) return 0f
        return (i.width() * i.height()) / max(1f, c.width() * c.height())
    }

    /**
     * 右手放的位置：从圈右边中点出发，向右（-35°）到向下偏左（115°）的扇形，半径约半个屏宽。
     * 返回 r 里落在扇形里的采样点比例。
     */
    private fun handZone(r: RectF, c: RectF, viewW: Float): Float {
        val ax = c.right; val ay = c.centerY()
        val radius = max(viewW * 0.45f, c.width())
        var n = 0
        for (i in 0..2) for (j in 0..2) {
            val x = r.left + r.width() * i / 2; val y = r.top + r.height() * j / 2
            val dx = x - ax; val dy = y - ay
            if (hypot(dx, dy) > radius) continue
            val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            if (deg in -35.0..115.0) n++
        }
        return n / 9f
    }

    /** r 落在纸上的部分里，有墨迹的格子占多少。纸外面（页间空白）算空。 */
    private fun density(cv: NoteCanvas, pi: Int, r: RectF, grid: BooleanArray): Float {
        val tl = cv.toPage(pi, r.left, r.top); val br = cv.toPage(pi, r.right, r.bottom)
        val cw = NotePaper.PAGE_W / NoteCanvas.GRID_W; val ch = NotePaper.PAGE_H / NoteCanvas.GRID_H
        val x0 = (tl.x / cw).toInt(); val x1 = (br.x / cw).toInt()
        val y0 = (tl.y / ch).toInt(); val y1 = (br.y / ch).toInt()
        var total = 0; var ink = 0
        for (y in y0..y1) for (x in x0..x1) {
            total++
            if (x in 0 until NoteCanvas.GRID_W && y in 0 until NoteCanvas.GRID_H && grid[y * NoteCanvas.GRID_W + x]) ink++
        }
        return if (total == 0) 0f else ink.toFloat() / total
    }
}

/** 工具栏上的小图标，全用代码画（不用图片资源）。按 24×24 的格子设计，居中缩放。 */
@SuppressLint("ViewConstructor")
class ToolIcon(ctx: Context, private val kind: Int, private val arg: Int = 0) : View(ctx) {
    /** 选中：工具显示浅蓝底，颜色显示外圈。 */
    var on = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Colors.INK
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val size = min(width, height).toFloat()
        if (on && kind != COLOR) {
            fill.color = SELECTED_BG
            c.drawCircle(width / 2f, height / 2f, size / 2 - context.dp(1f), fill)
        }
        val s = size / 24f * 0.62f
        c.save()
        c.translate(width / 2f - 12 * s, height / 2f - 12 * s)
        c.scale(s, s)
        stroke.strokeWidth = 2f
        stroke.color = Colors.INK
        path.rewind()
        when (kind) {
            BACK -> { path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f); c.drawPath(path, stroke) }
            UNDO, REDO -> {
                if (kind == REDO) { c.translate(24f, 0f); c.scale(-1f, 1f) }
                path.moveTo(19f, 18f)
                path.cubicTo(19f, 11f, 13f, 8.5f, 6.5f, 11.5f)
                c.drawPath(path, stroke)
                path.rewind()
                path.moveTo(7f, 6.5f); path.lineTo(5.5f, 12f); path.lineTo(11f, 13.5f)
                c.drawPath(path, stroke)
            }
            WIDTH -> {
                stroke.strokeWidth = WIDTH_ICON[arg.coerceIn(0, 2)]
                path.moveTo(4f, 15f)
                path.cubicTo(7f, 7f, 10f, 7f, 11.5f, 12.5f)
                path.cubicTo(13f, 18f, 16f, 18f, 20f, 9f)
                c.drawPath(path, stroke)
            }
            COLOR -> {
                fill.color = arg
                c.drawCircle(12f, 12f, if (on) 6.5f else 8f, fill)
                if (on) { stroke.color = arg; stroke.strokeWidth = 2.2f; c.drawCircle(12f, 12f, 10.5f, stroke) }
            }
            CIRCLE -> {   // 荧光圈 + 问号
                stroke.color = 0xFFFFB300.toInt(); stroke.strokeWidth = 3.2f
                path.addOval(RectF(3f, 5f, 21f, 19f), Path.Direction.CW)
                c.drawPath(path, stroke)
                fill.color = Colors.INK
                fill.textSize = 11f; fill.textAlign = Paint.Align.CENTER; fill.isFakeBoldText = true
                c.drawText("?", 12f, 16f, fill)
            }
            MOVE -> {   // 四向箭头
                path.moveTo(12f, 3f); path.lineTo(12f, 21f); path.moveTo(3f, 12f); path.lineTo(21f, 12f)
                path.moveTo(9f, 6f); path.lineTo(12f, 3f); path.lineTo(15f, 6f)
                path.moveTo(9f, 18f); path.lineTo(12f, 21f); path.lineTo(15f, 18f)
                path.moveTo(6f, 9f); path.lineTo(3f, 12f); path.lineTo(6f, 15f)
                path.moveTo(18f, 9f); path.lineTo(21f, 12f); path.lineTo(18f, 15f)
                c.drawPath(path, stroke)
            }
        }
        c.restore()
    }

    companion object {
        const val BACK = 0
        const val UNDO = 1
        const val REDO = 2
        const val WIDTH = 3
        const val COLOR = 4
        const val CIRCLE = 5
        const val MOVE = 6
        private val WIDTH_ICON = floatArrayOf(1.2f, 2.2f, 4f)
        private const val SELECTED_BG = 0xFFDCE5FF.toInt()
    }
}
