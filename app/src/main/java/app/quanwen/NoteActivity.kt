package app.quanwen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 打开一本笔记本：顶上一条工具栏（返回、撤回、还原、三档粗细、黑红蓝三色），下面是画布。
 * 每 3 秒把改过的页存一次，离开页面时马上存。
 */
class NoteActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var book: Notebook
    private lateinit var pages: MutableList<NotePage>
    private lateinit var canvas: NoteCanvas
    private lateinit var undoBtn: ToolIcon
    private lateinit var redoBtn: ToolIcon
    private val widthBtns = ArrayList<ToolIcon>()
    private val colorBtns = ArrayList<ToolIcon>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val autosave = object : Runnable {
        override fun run() { saveDirty(); main.postDelayed(this, SAVE_EVERY_MS) }
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NoteStore.init(this)
        prefs = Prefs(this)
        book = NoteStore.get(intent.getStringExtra(EXTRA_ID) ?: "") ?: run { finish(); return }
        pages = book.pageIds.map { NoteStore.loadPage(book, it) }.toMutableList()
        if (pages.isEmpty()) pages.add(NoteStore.addPage(book))
        canvas = NoteCanvas(this, book, pages) { refreshButtons() }
        canvas.penColor = NotePaper.COLORS[prefs.noteColor.coerceIn(0, 2)]
        canvas.penWidth = NotePaper.WIDTHS[prefs.noteWidth.coerceIn(0, 2)]

        window.statusBarColor = TOOLBAR
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(TOOLBAR) }
        root.addView(toolbar())
        root.addView(View(this).apply { setBackgroundColor(0x14000000) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1f)))
        root.addView(canvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshButtons()
    }

    override fun onResume() { super.onResume(); main.postDelayed(autosave, SAVE_EVERY_MS) }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(autosave)
        if (::canvas.isInitialized) saveDirty()
    }

    override fun onDestroy() {
        if (::canvas.isInitialized) canvas.clearCache()
        io.shutdown()   // 已经排队的保存会做完
        super.onDestroy()
    }

    /** 把改过的页写到文件（在后台线程写一份快照）。 */
    private fun saveDirty() {
        var any = false
        for (p in pages) if (p.dirty) {
            p.dirty = false
            val snap = ArrayList(p.strokes)
            io.execute { try { NoteStore.writePage(book, p.id, snap) } catch (_: Exception) { p.dirty = true } }
            any = true
        }
        if (any) { book.modified = System.currentTimeMillis(); NoteStore.saveIndex() }
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
            maxWidth = dpi(220f); setPadding(dpi(4f), 0, dpi(12f), 0)
        })
        bar.addView(divider())
        undoBtn = icon(ToolIcon.UNDO) { canvas.undo() }.apply { contentDescription = getString(R.string.note_undo) }
        redoBtn = icon(ToolIcon.REDO) { canvas.redo() }.apply { contentDescription = getString(R.string.note_redo) }
        bar.addView(undoBtn); bar.addView(redoBtn)
        bar.addView(divider())
        for (i in 0..2) {
            val b = icon(ToolIcon.WIDTH, i) {
                prefs.noteWidth = i; canvas.penWidth = NotePaper.WIDTHS[i]; refreshButtons()
            }
            widthBtns.add(b); bar.addView(b)
        }
        bar.addView(divider())
        for (i in 0..2) {
            val b = icon(ToolIcon.COLOR, NotePaper.COLORS[i]) {
                prefs.noteColor = i; canvas.penColor = NotePaper.COLORS[i]; refreshButtons()
            }
            colorBtns.add(b); bar.addView(b)
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }
    }

    private fun refreshButtons() {
        undoBtn.isEnabled = canvas.canUndo; undoBtn.alpha = if (canvas.canUndo) 1f else 0.3f
        redoBtn.isEnabled = canvas.canRedo; redoBtn.alpha = if (canvas.canRedo) 1f else 0.3f
        widthBtns.forEachIndexed { i, b -> b.on = NotePaper.WIDTHS[i] == canvas.penWidth }
        colorBtns.forEachIndexed { i, b -> b.on = NotePaper.COLORS[i] == canvas.penColor }
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

        fun open(ctx: Context, id: String) =
            ctx.startActivity(Intent(ctx, NoteActivity::class.java).putExtra(EXTRA_ID, id))
    }
}

/** 工具栏上的小图标，全用代码画（不用图片资源）。按 24×24 的格子设计，居中缩放。 */
@SuppressLint("ViewConstructor")
class ToolIcon(ctx: Context, private val kind: Int, private val arg: Int = 0) : View(ctx) {
    /** 选中：粗细档位显示浅蓝底，颜色显示外圈。 */
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
        }
        c.restore()
    }

    companion object {
        const val BACK = 0
        const val UNDO = 1
        const val REDO = 2
        const val WIDTH = 3
        const val COLOR = 4
        private val WIDTH_ICON = floatArrayOf(1.2f, 2.2f, 4f)
        private const val SELECTED_BG = 0xFFDCE5FF.toInt()
    }
}
