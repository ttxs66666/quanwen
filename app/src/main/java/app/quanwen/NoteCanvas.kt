package app.quanwen

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * 笔记本的画布：纸张一页页竖着排，上下滚动。双指可以缩放（0.5–4 倍），放大后左右也能拖；[resetZoom] 回到铺满屏宽。
 * 笔的粗细按纸算（页面坐标），缩放只是看得大小不同，放大时写的字和别处一样粗细。
 *
 * 四种工具：
 *  - 笔：黑红蓝三色、三档粗细。撤回、还原一次一笔。
 *  - 橡皮擦：划过哪里擦掉哪里（一笔被擦断就分成几笔），三档大小。笔尾的橡皮头、按着笔上的按键也能擦。
 *    「一同书写」里 AI 写的字也能擦，碰到哪个字擦哪个字。
 *  - 圈：荧光笔画一个圈，停笔 1 秒就发给 AI（[onCircle]），荧光慢慢淡掉，不留在纸上；发完自动回到笔。
 *  - 移动图片：只有这个模式下才能拖动贴在纸上的图片（「留下」的回答），避免写字时误碰。选中的图片右上角有 × 可以删掉。
 * 任何工具下（圈除外）长按一张图片，都会弹出删除 / 移动的菜单（[onImageLongPress]）。
 *
 * 输入：用过触控笔以后只有笔能写，手指一根就拖动、两根缩放（防手掌误触）；没用过笔的设备上一根手指写、两根手指拖动和缩放。
 *
 * 画面：每页画成一张缓存图（按铺满屏宽的大小；缩小到 0.75 倍以下时用小一号的图，省内存），滚动、缩放时直接贴图；
 * 正在写的这一笔、荧光圈、正在拖的图片、粒子单独画在上面。放大超过 1 倍时缓存图会糊，
 * 停下来以后按当前倍数把屏幕上看得见的部分重画一张清楚的（[detail]），之后新写的笔和字直接补画上去。
 */
@SuppressLint("ViewConstructor")
class NoteCanvas(
    ctx: Context,
    private val book: Notebook,
    private val pages: MutableList<NotePage>,
    /** 内容或撤回状态变了（让工具栏刷新按钮）。 */
    private val onChanged: () -> Unit,
) : View(ctx) {

    enum class Tool { PEN, CIRCLE, MOVE, ERASER }

    var tool = Tool.PEN
        set(v) {
            if (field == v) return
            field = v
            selected = null
            if (v != Tool.CIRCLE) cancelCircle()
            invalidate()
            onToolChanged?.invoke()
        }
    var penColor = NotePaper.COLORS[0]
    var penWidth = NotePaper.WIDTHS[1]
    /** 橡皮擦的半径（页面坐标）。 */
    var eraserSize = ERASER_SIZES[1]

    /** 圈完停笔 1 秒后调用：圈在第几页、圈里的所有笔迹（页面坐标）。 */
    var onCircle: ((Int, List<FloatArray>) -> Unit)? = null
    var onToolChanged: (() -> Unit)? = null
    /** 滚动、缩放或尺寸变了（贴着纸的回答框要跟着走）。 */
    var onViewport: (() -> Unit)? = null
    /** 长按了一张图片：第几页、哪张图、按的位置（画布坐标）。菜单关掉时调 [clearPressed]。 */
    var onImageLongPress: ((Int, NoteImage, Float, Float) -> Unit)? = null
    /** 一笔写完存进了页里（「一同书写」用来认问号、句号）。 */
    var onStrokeCommitted: ((Int, Stroke) -> Unit)? = null
    /** 笔落下开始写或擦（「一同书写」用来取消等着发出去的问题）。 */
    var onPenDown: (() -> Unit)? = null

    private val prefs = Prefs(ctx)
    private var stylusSeen = prefs.stylusSeen
    private var seqCounter = 1L

    // ---------- 排版 ----------
    private val margin = ctx.dp(16f)
    private val gap = ctx.dp(22f)
    private val topPad = ctx.dp(12f)
    private val bottomPad = ctx.dp(120f)
    /** 缩放为 1 时（纸铺满屏宽）页面坐标 → 像素。 */
    private var baseScale = 1f
    /** 缩放倍数，1 = 铺满屏宽。 */
    var zoom = 1f
        private set
    /** 当前的页面坐标 → 像素。 */
    var scale = 1f
        private set
    private var pageWpx = 0f
    private var pageHpx = 0f
    private var scrollPos = 0f
    /** 放大后纸比屏幕宽：左右拖动的距离。 */
    private var scrollX = 0f
    private val scroller = OverScroller(ctx)
    private var velocity: VelocityTracker? = null
    private var zoomAnim: ValueAnimator? = null

    private fun pageTop(i: Int) = topPad + i * (pageHpx + gap)
    private val contentHeight get() = topPad + pages.size * (pageHpx + gap) - gap + bottomPad
    private val maxScroll get() = max(0f, contentHeight - height)
    private val contentWidth get() = pageWpx + 2 * margin
    private val maxScrollX get() = max(0f, contentWidth - width)
    /** 纸的左边在屏幕上的位置：纸比屏幕窄时居中，宽时跟着左右拖。 */
    private fun pageLeft() = if (contentWidth <= width) (width - pageWpx) / 2 else margin - scrollX

    private fun applyScale() {
        scale = baseScale * zoom
        pageWpx = NotePaper.PAGE_W * scale
        pageHpx = NotePaper.PAGE_H * scale
    }

    // ---------- 撤回 / 还原 ----------
    private sealed class Op(val page: NotePage) {
        class AddStroke(page: NotePage, val stroke: Stroke) : Op(page)
        class AddImage(page: NotePage, val img: NoteImage) : Op(page)
        class RemoveImage(page: NotePage, val img: NoteImage) : Op(page)
        class MoveImage(page: NotePage, val img: NoteImage, val fx: Float, val fy: Float, val tx: Float, val ty: Float) : Op(page)
        /**
         * 橡皮擦的一下：这一页擦之前、擦之后的笔迹（没擦到笔迹时为 null），擦掉的 AI 的字。
         * 字只记擦掉的那些：AI 之后还会往纸上写字，不能整页换回去。
         */
        class ReplaceInk(page: NotePage, val before: List<Stroke>?, val after: List<Stroke>?, val glyphs: List<NoteGlyph>) : Op(page)
    }
    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    // ---------- 缓存 ----------
    private class Cached(val bmp: Bitmap, var version: Int, val k: Float)
    private val cache = LinkedHashMap<NotePage, Cached>()
    /** 贴图按需要的宽度解码好的位图。 */
    private val imgCache = HashMap<String, Bitmap>()

    // 放大时清楚的那张：屏幕大小，记着画的时候的倍数、纸的左边、滚动位置和每页的版本
    private var detail: Bitmap? = null
    private var detailScale = 0f
    private var detailLeft = 0f
    private var detailScroll = 0f
    private val detailVersions = HashMap<NotePage, Int>()
    private val renderDetail = Runnable { buildDetail() }

    // ---------- 正在进行的手势 ----------
    private enum class Mode { NONE, DRAW, SCROLL, DRAG, ERASE }
    private var mode = Mode.NONE
    private var drawPointer = -1
    private var drawPage = -1
    private var cur = FloatArray(256)
    private var curN = 0
    private var lastAvgX = 0f
    private var lastAvgY = 0f
    private var lastSpan = 0f
    private var lastPointerCount = 0
    /** 这次手势里缩放过：抬手时不要惯性滑动。 */
    private var pinched = false

    // 圈
    private var circlePage = -1
    private val circleStrokes = ArrayList<FloatArray>()
    private var circleAlpha = 1f
    private var fade: ValueAnimator? = null
    private val sendCircle = Runnable { finishCircle() }

    // 橡皮擦：这一下擦之前的笔迹（擦到东西才拍）、擦掉的字、上一个点（页面坐标）、橡皮在屏幕上的位置
    private var eraseBefore: List<Stroke>? = null
    private val erasedGlyphs = ArrayList<NoteGlyph>()
    private val eraseLast = PointF()
    private val eraseView = PointF()

    // 长按图片
    private var pressImg: Pair<Int, NoteImage>? = null
    private val pressDown = PointF()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val longPress = Runnable { fireLongPress() }
    /** 长按着的图片：菜单开着时画一个虚线框。 */
    private var pressed: Pair<NotePage, NoteImage>? = null

    // 移动图片
    private var selected: Pair<NotePage, NoteImage>? = null
    private var dragging: NoteImage? = null
    private var dragFrom = PointF()
    private var dragDown = PointF()

    // 化成粒子飘散的字
    private val effects = ArrayList<Dissolve>()

    // ---------- 画笔 ----------
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        hinting = Paint.HINTING_OFF
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(1f); color = 0x22000000
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.MUTED; textSize = ctx.dp(11f); textAlign = Paint.Align.RIGHT
    }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(2f); color = 0xFF1559F0.toInt()
        pathEffect = DashPathEffect(floatArrayOf(ctx.dp(6f), ctx.dp(4f)), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1559F0.toInt() }
    private val handleX = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(2f); color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(1.5f); color = 0x99000000.toInt()
    }
    private val path = Path()
    private val handleR = ctx.dp(13f)

    // ---------- 尺寸 ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 转屏或分屏拖动时，保持屏幕顶上还是同一页的同一位置
        val anchor = if (pageHpx > 0) (scrollPos - topPad) / (pageHpx + gap) else 0f
        baseScale = (w - 2 * margin) / NotePaper.PAGE_W
        applyScale()
        clearCache()
        scrollPos = (topPad + anchor * (pageHpx + gap)).coerceIn(0f, maxScroll)
        scrollX = scrollX.coerceIn(0f, maxScrollX)
        onViewport?.invoke()
    }

    // ---------- 坐标 ----------

    /** 页面坐标 → 画布坐标。 */
    fun toView(page: Int, x: Float, y: Float) = PointF(pageLeft() + x * scale, pageTop(page) - scrollPos + y * scale)

    /** 画布坐标在某一页上的页面坐标。 */
    fun toPage(page: Int, vx: Float, vy: Float) = PointF((vx - pageLeft()) / scale, (vy - pageTop(page) + scrollPos) / scale)

    fun pageRect(page: Int): RectF {
        val l = pageLeft(); val t = pageTop(page) - scrollPos
        return RectF(l, t, l + pageWpx, t + pageHpx)
    }

    // ---------- 缩放 ----------

    /** 以屏幕上 (fx, fy) 这一点为中心缩放到 z 倍：这一点下面的纸不动。 */
    fun zoomAt(z: Float, fx: Float, fy: Float) {
        val nz = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(nz - zoom) < 1e-4f || pageHpx <= 0f || pages.isEmpty()) return
        val pi = floor((fy + scrollPos - topPad) / (pageHpx + gap)).toInt().coerceIn(0, pages.lastIndex)
        val p = toPage(pi, fx, fy)
        val oldK = cacheScale()
        zoom = nz
        applyScale()
        scrollPos = (pageTop(pi) + p.y * scale - fy).coerceIn(0f, maxScroll)
        scrollX = if (contentWidth > width) (margin + p.x * scale - fx).coerceIn(0f, maxScrollX) else 0f
        if (cacheScale() != oldK) clearPageCaches()
        invalidate()
        onViewport?.invoke()
    }

    /** 一键复位：缩放回 1 倍（纸铺满屏宽），屏幕中间看着的地方不动。 */
    fun resetZoom() {
        zoomAnim?.cancel()
        if (abs(zoom - 1f) < 1e-3f) { scrollX = 0f; invalidate(); return }
        zoomAnim = ValueAnimator.ofFloat(zoom, 1f).apply {
            duration = 260
            addUpdateListener { zoomAt(it.animatedValue as Float, width / 2f, height / 2f) }
            start()
        }
    }

    /** 缓存图的倍数：平时按铺满屏宽画；缩小到 0.75 倍以下时用 0.6 倍的小图，省内存。 */
    private fun cacheScale() = baseScale * (if (zoom < 0.75f) 0.6f else 1f)

    // ---------- 撤回 / 还原 ----------

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        apply(op, forward = false)
        redoStack.addLast(op)
        invalidate(); onChanged()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        apply(op, forward = true)
        undoStack.addLast(op)
        invalidate(); onChanged()
    }

    private fun push(op: Op) {
        undoStack.addLast(op)
        if (undoStack.size > 1000) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun apply(op: Op, forward: Boolean) {
        val page = op.page
        when (op) {
            is Op.AddStroke -> if (forward) addStrokeToPage(page, op.stroke) else removeStroke(page, op.stroke)
            is Op.AddImage -> if (forward) page.images.add(op.img) else page.images.remove(op.img)
            is Op.RemoveImage -> if (forward) page.images.remove(op.img) else page.images.add(op.img)
            is Op.MoveImage -> if (forward) { op.img.x = op.tx; op.img.y = op.ty } else { op.img.x = op.fx; op.img.y = op.fy }
            is Op.ReplaceInk -> {
                if (op.before != null && op.after != null) { page.strokes.clear(); page.strokes.addAll(if (forward) op.after else op.before) }
                if (forward) page.glyphs.removeAll(op.glyphs.toSet()) else page.glyphs.addAll(op.glyphs)
            }
        }
        if (op !is Op.AddStroke) { page.changed(); dropPageCache(page) }
        if (selected?.second?.let { s -> page.images.none { it === s } } == true) selected = null
    }

    private fun addStrokeToPage(page: NotePage, s: Stroke) {
        page.strokes.add(s)
        page.changed()
        drawIncremental(page) { cv -> drawStroke(cv, s.pts, s.pts.size, s.width, s.color) }   // 直接补画这一笔，不用整页重画
    }

    private fun removeStroke(page: NotePage, s: Stroke) {
        val i = page.strokes.lastIndexOf(s)
        if (i >= 0) page.strokes.removeAt(i)
        page.changed()
        dropPageCache(page)   // 拿掉一笔只能整页重画
    }

    private fun commitStroke() {
        val pi = drawPage
        if (pi !in pages.indices || curN < 2) return
        val page = pages[pi]
        val wasEmpty = page.isBlank()
        val s = Stroke(penColor, penWidth, cur.copyOf(curN)).also { it.seq = seqCounter++ }
        addStrokeToPage(page, s)
        push(Op.AddStroke(page, s))
        if (wasEmpty && pi == pages.lastIndex) pages.add(NoteStore.addPage(book))   // 总留一张空白页在最后
        onChanged()
        onStrokeCommitted?.invoke(pi, s)
    }

    private fun NotePage.isBlank() = strokes.isEmpty() && images.isEmpty() && glyphs.isEmpty()

    /** 贴一张图到某一页（「留下」的回答）。 */
    fun addImage(pageIndex: Int, img: NoteImage) {
        val page = pages.getOrNull(pageIndex) ?: return
        val wasEmpty = page.isBlank()
        page.images.add(img)
        page.changed(); dropPageCache(page)
        push(Op.AddImage(page, img))
        if (wasEmpty && pageIndex == pages.lastIndex) pages.add(NoteStore.addPage(book))
        invalidate(); onChanged()
    }

    // ---------- 「一同书写」：AI 写在纸上的字 ----------

    fun notePage(i: Int): NotePage? = pages.getOrNull(i)

    /** 保证第 i 页存在（AI 写到一页底下时接着往下一页写）。 */
    fun ensurePage(i: Int) {
        while (pages.size <= i) pages.add(NoteStore.addPage(book))
        invalidate()
    }

    /** AI 往纸上写一个字。不进撤回（擦掉可以撤回）。 */
    fun addGlyph(pageIndex: Int, g: NoteGlyph) {
        val page = pages.getOrNull(pageIndex) ?: return
        val wasEmpty = page.isBlank()
        page.glyphs.add(g)
        page.changed()
        drawIncremental(page) { cv -> drawGlyph(cv, g) }
        if (wasEmpty && pageIndex == pages.lastIndex) pages.add(NoteStore.addPage(book))
        invalidate()
    }

    /** 去掉一组字（AI 的询问，他回答以后）：从左往右化成粒子飘散。不进撤回。 */
    fun dissolveGroup(group: String) {
        for (page in pages) {
            val gs = page.glyphs.filter { it.group == group }
            if (gs.isEmpty()) continue
            val r = RectF(gs[0].box())
            for (g in gs) r.union(g.box())
            val size = gs.map { it.size }.average().toFloat()
            r.inset(-size * 0.15f, -size * 0.15f)
            val k = scale.coerceIn(0.8f, 3f)
            val bmp = Bitmap.createBitmap(max(1, (r.width() * k).roundToInt()), max(1, (r.height() * k).roundToInt()), Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.scale(k, k); cv.translate(-r.left, -r.top)
            for (g in gs) drawGlyph(cv, g)
            effects.add(Dissolve(page, r, bmp, k, size))
            page.glyphs.removeAll(gs.toSet())
            page.changed()
        }
        invalidate()
    }

    /**
     * 第 pi 页上和矩形 r 相交的东西（页面坐标）：笔迹、图片、别的回答的字。排 AI 的字时用来避让。
     * 只算竖着和 r 重叠够多的（上一行字的笔画稍微探下来一点不算）。
     */
    fun obstacles(pi: Int, r: RectF, exclude: Set<String>): List<RectF> {
        val page = pages.getOrNull(pi) ?: return emptyList()
        val out = ArrayList<RectF>()
        fun consider(l: Float, t: Float, rr: Float, b: Float) {
            if (rr <= r.left || l >= r.right) return
            val ov = min(b, r.bottom) - max(t, r.top)
            if (ov > 0.3f * min(r.height(), max(1f, b - t))) out.add(RectF(l, t, rr, b))
        }
        for (s in page.strokes) { val b = s.box; val p = s.width / 2; consider(b[0] - p, b[1] - p, b[2] + p, b[3] + p) }
        for (im in page.images) consider(im.x, im.y, im.x + im.w, im.y + im.h)
        for (g in page.glyphs) if (g.group !in exclude) { val b = g.box(); consider(b.left, b.top, b.right, b.bottom) }
        return out
    }

    // ---------- 触摸 ----------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                zoomAnim?.cancel()
                pinched = false
                velocity?.recycle(); velocity = VelocityTracker.obtain()
                val stylus = isStylus(e, 0)
                if (stylus && !stylusSeen) { stylusSeen = true; prefs.stylusSeen = true }
                val writes = stylus || !stylusSeen
                when {
                    tool == Tool.MOVE -> if (!startDrag(e)) startScroll(e)
                    writes && erases(e, 0) && startErase(e, 0) -> requestUnbufferedDispatch(e)
                    writes && startDraw(e, 0) -> requestUnbufferedDispatch(e)   // 降低笔迹延迟
                    else -> startScroll(e)
                }
                armLongPress(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelImagePress()
                val idx = e.actionIndex
                when {
                    // 手先碰到（拖动中），接着笔落下：改成写字（或擦）
                    mode == Mode.SCROLL && tool != Tool.MOVE && isStylus(e, idx) -> {
                        stopScroll()
                        if (erases(e, idx)) startErase(e, idx) else startDraw(e, idx)
                    }
                    // 没用过笔的设备上一根手指在擦，第二根手指落下：改成两指拖动、缩放
                    mode == Mode.ERASE && !isStylus(e, e.findPointerIndex(drawPointer)) && !isStylus(e, idx) -> { endErase(); startScroll(e) }
                    // 没用过笔的设备上一根手指在写，第二根手指落下：改成两指拖动、缩放
                    mode == Mode.DRAW && !isStylus(e, e.findPointerIndex(drawPointer)) && !isStylus(e, idx) -> {
                        if (curN > 40) endStroke() else cancelDraw()   // 已经写了一长笔就留下
                        startScroll(e)
                    }
                    mode == Mode.SCROLL -> resetScrollBaseline(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                checkLongPressSlop(e)
                when (mode) {
                    Mode.ERASE -> {
                        val idx = e.findPointerIndex(drawPointer)
                        if (idx >= 0) {
                            for (h in 0 until e.historySize) eraseTo(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                            eraseTo(e.getX(idx), e.getY(idx))
                            invalidate()
                        }
                    }
                    Mode.DRAW -> {
                        val idx = e.findPointerIndex(drawPointer)
                        if (idx >= 0) {
                            for (h in 0 until e.historySize) addPoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                            addPoint(e.getX(idx), e.getY(idx))
                            invalidate()
                        }
                    }
                    Mode.SCROLL -> moveScroll(e)
                    Mode.DRAG -> dragTo(e.x, e.y)
                    Mode.NONE -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.DRAW && e.getPointerId(e.actionIndex) == drawPointer) endStroke()
                else if (mode == Mode.ERASE && e.getPointerId(e.actionIndex) == drawPointer) endErase()
                else if (mode == Mode.SCROLL) resetScrollBaseline(e, excluding = e.actionIndex)
            }
            MotionEvent.ACTION_UP -> {
                cancelImagePress()
                when (mode) {
                    Mode.DRAW -> endStroke()
                    Mode.ERASE -> endErase()
                    Mode.SCROLL -> if (!pinched) {
                        velocity?.addMovement(e)
                        velocity?.computeCurrentVelocity(1000)
                        val vx = velocity?.xVelocity ?: 0f
                        val vy = velocity?.yVelocity ?: 0f
                        scroller.fling(scrollX.toInt(), scrollPos.toInt(), -vx.toInt(), -vy.toInt(),
                            0, maxScrollX.toInt(), 0, maxScroll.toInt())
                        postInvalidateOnAnimation()
                    }
                    Mode.DRAG -> endDrag()
                    Mode.NONE -> {}
                }
                mode = Mode.NONE
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelImagePress()
                when (mode) {
                    Mode.DRAW -> cancelDraw()
                    Mode.ERASE -> endErase()
                    Mode.DRAG -> endDrag()
                    else -> {}
                }
                mode = Mode.NONE
            }
        }
        return true
    }

    private fun isStylus(e: MotionEvent, idx: Int): Boolean {
        if (idx < 0 || idx >= e.pointerCount) return false
        val t = e.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    /** 这一下是擦：选了橡皮擦，或者拿笔时用的是笔尾的橡皮头 / 按着笔上的按键。 */
    private fun erases(e: MotionEvent, idx: Int): Boolean {
        if (tool == Tool.ERASER) return true
        if (tool != Tool.PEN || idx < 0 || idx >= e.pointerCount) return false
        val t = e.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_ERASER ||
            (t == MotionEvent.TOOL_TYPE_STYLUS && (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
    }

    // ---------- 橡皮擦 ----------

    private fun startErase(e: MotionEvent, idx: Int): Boolean {
        val x = e.getX(idx); val y = e.getY(idx)
        val pi = pageAt(x, y)
        if (pi < 0) return false
        onPenDown?.invoke()
        mode = Mode.ERASE
        drawPointer = e.getPointerId(idx)
        drawPage = pi
        eraseBefore = null
        erasedGlyphs.clear()
        val p = toPage(pi, x, y)
        eraseLast.set(p)
        eraseView.set(x, y)
        eraseSegment(p.x, p.y)
        invalidate()
        return true
    }

    private fun eraseTo(x: Float, y: Float) {
        val p = toPage(drawPage, x, y)
        eraseSegment(p.x, p.y)
        eraseView.set(x, y)
    }

    /** 擦掉从上一个点到 (x, y) 这一段划过的笔迹和字。页面缓存图按版本号重画，不用每次新建。 */
    private fun eraseSegment(x: Float, y: Float) {
        val page = pages.getOrNull(drawPage) ?: return
        val out = Eraser.erase(page.strokes, eraseLast.x, eraseLast.y, x, y, eraserSize)
        val hit = Eraser.glyphsHit(page.glyphs, eraseLast.x, eraseLast.y, x, y, eraserSize)
        eraseLast.set(x, y)
        if (out == null && hit.isEmpty()) return
        if (out != null) {
            if (eraseBefore == null) eraseBefore = ArrayList(page.strokes)
            page.strokes.clear(); page.strokes.addAll(out)
        }
        if (hit.isNotEmpty()) { page.glyphs.removeAll(hit.toSet()); erasedGlyphs.addAll(hit) }
        page.changed()
    }

    /** 这一下擦完：擦到了东西就记一步撤回。 */
    private fun endErase() {
        val page = pages.getOrNull(drawPage)
        val before = eraseBefore
        if (page != null && (before != null || erasedGlyphs.isNotEmpty())) {
            push(Op.ReplaceInk(page, before, before?.let { ArrayList(page.strokes) }, ArrayList(erasedGlyphs)))
            onChanged()
        }
        eraseBefore = null
        erasedGlyphs.clear()
        mode = Mode.NONE; drawPointer = -1
        invalidate()
    }

    // ---------- 长按图片 ----------

    /** 按在一张图片上就开始计时；挪远了、抬起了、第二根手指落下就取消。圈的时候不管。 */
    private fun armLongPress(e: MotionEvent) {
        cancelImagePress()
        if (tool == Tool.CIRCLE || onImageLongPress == null) return
        val pi = pageAt(e.x, e.y)
        if (pi < 0) return
        val pp = toPage(pi, e.x, e.y)
        val img = pages[pi].images.lastOrNull { it.contains(pp.x, pp.y) } ?: return
        pressImg = pi to img
        pressDown.set(e.x, e.y)
        postDelayed(longPress, LONG_PRESS_MS)
    }

    private fun checkLongPressSlop(e: MotionEvent) {
        if (pressImg != null && hypot(e.getX(0) - pressDown.x, e.getY(0) - pressDown.y) > slop) cancelImagePress()
    }

    private fun cancelImagePress() {
        if (pressImg == null) return
        removeCallbacks(longPress)
        pressImg = null
    }

    private fun fireLongPress() {
        val (pi, img) = pressImg ?: return
        pressImg = null
        when (mode) {   // 这一下不算写字、擦、拖动
            Mode.DRAW -> { curN = 0; drawPointer = -1 }
            Mode.ERASE -> endErase()
            Mode.SCROLL -> scroller.forceFinished(true)
            Mode.DRAG -> {
                dragging?.let { it.x = dragFrom.x; it.y = dragFrom.y }
                dragging = null
                selected?.first?.let { dropPageCache(it) }
            }
            Mode.NONE -> {}
        }
        mode = Mode.NONE
        if (pi !in pages.indices || pages[pi].images.none { it === img }) { invalidate(); return }
        pressed = pages[pi] to img
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
        onImageLongPress?.invoke(pi, img, pressDown.x, pressDown.y)
    }

    /** 长按菜单关掉了：去掉虚线框。 */
    fun clearPressed() { pressed = null; invalidate() }

    /** 删掉一张图（长按菜单里的「删除图片」），可以撤回。 */
    fun removeImage(pageIndex: Int, img: NoteImage) {
        val page = pages.getOrNull(pageIndex) ?: return
        if (!page.images.remove(img)) return
        page.changed(); dropPageCache(page)
        push(Op.RemoveImage(page, img))
        if (selected?.second === img) selected = null
        if (pressed?.second === img) pressed = null
        invalidate(); onChanged()
    }

    // ---------- 写字 ----------

    /** 落点在某一页上才开始写，返回是否开始了。 */
    private fun startDraw(e: MotionEvent, idx: Int): Boolean {
        val x = e.getX(idx); val y = e.getY(idx)
        val pi = pageAt(x, y)
        if (pi < 0) return false
        if (tool == Tool.CIRCLE) {
            if (fade?.isStarted == true) {   // 上一个圈还在淡出：直接清掉，开始新的圈
                fade?.cancel(); circleStrokes.clear(); circlePage = -1; circleAlpha = 1f
            }
            removeCallbacks(sendCircle)   // 还在接着画：先不发
            if (circlePage >= 0 && pi != circlePage) return false   // 一次圈只在一页上
        } else onPenDown?.invoke()
        mode = Mode.DRAW
        drawPointer = e.getPointerId(idx)
        drawPage = pi
        curN = 0
        addPoint(x, y)
        invalidate()
        return true
    }

    private fun addPoint(x: Float, y: Float) {
        if (curN + 2 > cur.size) cur = cur.copyOf(cur.size * 2)
        val top = pageTop(drawPage) - scrollPos
        cur[curN++] = ((x - pageLeft()) / scale).coerceIn(0f, NotePaper.PAGE_W)
        cur[curN++] = ((y - top) / scale).coerceIn(0f, NotePaper.PAGE_H)
    }

    /** 一笔写完：笔就存进页里，圈就先攒着，停笔 1 秒后发出去。 */
    private fun endStroke() {
        if (tool == Tool.CIRCLE) {
            if (curN >= 4) {
                circlePage = drawPage
                circleStrokes.add(cur.copyOf(curN))
                postDelayed(sendCircle, CIRCLE_PAUSE_MS)
            }
            mode = Mode.NONE; drawPointer = -1; curN = 0
        } else {
            mode = Mode.NONE; drawPointer = -1
            commitStroke()
            curN = 0
        }
        invalidate()
    }

    private fun cancelDraw() {
        curN = 0; drawPointer = -1; mode = Mode.NONE
        if (tool == Tool.CIRCLE && circleStrokes.isNotEmpty()) postDelayed(sendCircle, CIRCLE_PAUSE_MS)
        invalidate()
    }

    // ---------- 圈 ----------

    private fun finishCircle() {
        if (circleStrokes.isEmpty() || circlePage !in pages.indices) { cancelCircle(); return }
        onCircle?.invoke(circlePage, ArrayList(circleStrokes))
        // 荧光慢慢淡掉，不留在纸上
        fade?.cancel()
        fade = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 700
            startDelay = 250
            addUpdateListener { circleAlpha = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (circleAlpha <= 0.01f) { circleStrokes.clear(); circlePage = -1; circleAlpha = 1f; invalidate() }
                }
            })
            start()
        }
        tool = Tool.PEN   // 圈一次就回到笔，接着写
    }

    private fun cancelCircle() {
        removeCallbacks(sendCircle)
        if (fade?.isStarted == true) return   // 正在淡出：让它淡完
        circleStrokes.clear(); circlePage = -1
    }

    // ---------- 移动图片 ----------

    private fun startDrag(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        // 先看是不是点在选中图片的 ×
        selected?.let { (page, img) ->
            val pi = pages.indexOf(page)
            if (pi >= 0) {
                val p = toView(pi, img.x + img.w, img.y)
                if (hypot(x - p.x, y - p.y) <= handleR * 1.4f) {
                    page.images.remove(img); page.changed(); dropPageCache(page)
                    push(Op.RemoveImage(page, img))
                    selected = null
                    invalidate(); onChanged()
                    mode = Mode.NONE
                    return true
                }
            }
        }
        val pi = pageAt(x, y)
        if (pi < 0) { selected = null; invalidate(); return false }
        val pp = toPage(pi, x, y)
        val img = pages[pi].images.lastOrNull { it.contains(pp.x, pp.y) }
        if (img == null) { selected = null; invalidate(); return false }
        selected = pages[pi] to img
        dragging = img
        dragFrom.set(img.x, img.y)
        dragDown.set(x, y)
        mode = Mode.DRAG
        dropPageCache(pages[pi])   // 拖动时这张图单独画在上面，页面缓存里先不画它
        invalidate()
        return true
    }

    private fun dragTo(x: Float, y: Float) {
        val img = dragging ?: return
        img.x = (dragFrom.x + (x - dragDown.x) / scale).coerceIn(0f, max(0f, NotePaper.PAGE_W - img.w))
        img.y = (dragFrom.y + (y - dragDown.y) / scale).coerceIn(0f, max(0f, NotePaper.PAGE_H - img.h))
        invalidate()
    }

    private fun endDrag() {
        val img = dragging ?: return
        dragging = null
        val page = selected?.first ?: return
        if (img.x != dragFrom.x || img.y != dragFrom.y) {
            push(Op.MoveImage(page, img, dragFrom.x, dragFrom.y, img.x, img.y))
            page.changed()
            onChanged()
        }
        dropPageCache(page)
        invalidate()
    }

    // ---------- 拖动、缩放 ----------

    private fun startScroll(e: MotionEvent) {
        mode = Mode.SCROLL
        velocity?.addMovement(e)
        resetScrollBaseline(e)
    }

    private fun stopScroll() { scroller.forceFinished(true); mode = Mode.NONE }

    private fun resetScrollBaseline(e: MotionEvent, excluding: Int = -1) {
        lastAvgX = avg(e, excluding, x = true)
        lastAvgY = avg(e, excluding, x = false)
        lastSpan = span(e, excluding)
        lastPointerCount = e.pointerCount - (if (excluding >= 0) 1 else 0)
    }

    /** 一根手指拖动，两根手指同时拖动和缩放（以两指中点为中心）。 */
    private fun moveScroll(e: MotionEvent) {
        velocity?.addMovement(e)
        if (e.pointerCount != lastPointerCount) resetScrollBaseline(e)
        val fx = avg(e, -1, x = true)
        val fy = avg(e, -1, x = false)
        if (e.pointerCount >= 2) {
            val sp = span(e, -1)
            if (lastSpan > 0f && sp > 0f && abs(sp - lastSpan) > 0.5f) {
                zoomAt(zoom * sp / lastSpan, fx, fy)
                pinched = true
            }
            lastSpan = sp
        }
        scrollX = (scrollX - (fx - lastAvgX)).coerceIn(0f, maxScrollX)
        scrollPos = (scrollPos - (fy - lastAvgY)).coerceIn(0f, maxScroll)
        lastAvgX = fx; lastAvgY = fy
        invalidate()
        onViewport?.invoke()
    }

    private fun avg(e: MotionEvent, excluding: Int, x: Boolean): Float {
        var s = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != excluding) { s += if (x) e.getX(i) else e.getY(i); n++ }
        return if (n == 0) 0f else s / n
    }

    /** 几根手指到中点的平均距离；不到两根时为 0。 */
    private fun span(e: MotionEvent, excluding: Int): Float {
        val n = e.pointerCount - (if (excluding >= 0) 1 else 0)
        if (n < 2) return 0f
        val cx = avg(e, excluding, x = true); val cy = avg(e, excluding, x = false)
        var s = 0f
        for (i in 0 until e.pointerCount) if (i != excluding) s += hypot(e.getX(i) - cx, e.getY(i) - cy)
        return s / n
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat().coerceIn(0f, maxScrollX)
            scrollPos = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
            onViewport?.invoke()
        }
    }

    /** 视图坐标落在哪一页上，不在页上返回 -1。 */
    private fun pageAt(x: Float, y: Float): Int {
        val l = pageLeft()
        if (x < l || x > l + pageWpx) return -1
        val cy = y + scrollPos - topPad
        if (cy < 0) return -1
        val i = floor(cy / (pageHpx + gap)).toInt()
        if (i !in pages.indices) return -1
        return if (cy - i * (pageHpx + gap) <= pageHpx) i else -1
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BACKDROP)
        if (pageHpx <= 0) return
        val left = pageLeft()
        val first = max(0, floor((scrollPos - topPad) / (pageHpx + gap)).toInt())
        val last = min(pages.lastIndex, floor((scrollPos + height - topPad) / (pageHpx + gap)).toInt())
        val dst = RectF()
        for (i in first..last) {
            val top = pageTop(i) - scrollPos
            dst.set(left, top, left + pageWpx, top + pageHpx)
            val bmp = pageBitmap(pages[i])
            if (abs(bmp.width - pageWpx) < 0.5f) canvas.drawBitmap(bmp, left, top, null)
            else canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        }
        if (zoom > DETAIL_ZOOM) drawDetail(canvas, first, last) else if (detail != null) dropDetail()
        for (i in first..last) {
            val top = pageTop(i) - scrollPos
            canvas.drawRect(left, top, left + pageWpx, top + pageHpx, edgePaint)
            canvas.drawText("${i + 1} / ${pages.size}", left + pageWpx, top + pageHpx + gap * 0.7f, numberPaint)
        }
        // 正在拖的图片
        val sel = selected
        dragging?.let { img -> sel?.let { (page, _) -> drawImageOnView(canvas, pages.indexOf(page), img) } }
        // 选中的图片：虚线框 + 右上角的 ×
        if (sel != null && tool == Tool.MOVE) {
            val pi = pages.indexOf(sel.first)
            if (pi >= 0) {
                val a = toView(pi, sel.second.x, sel.second.y)
                val r = RectF(a.x, a.y, a.x + sel.second.w * scale, a.y + sel.second.h * scale)
                canvas.drawRect(r, selectPaint)
                canvas.drawCircle(r.right, r.top, handleR, handlePaint)
                val d = handleR * 0.4f
                canvas.drawLine(r.right - d, r.top - d, r.right + d, r.top + d, handleX)
                canvas.drawLine(r.right - d, r.top + d, r.right + d, r.top - d, handleX)
            }
        }
        // 长按着的图片
        pressed?.let { (page, img) ->
            val pi = pages.indexOf(page)
            if (pi >= 0) {
                val a = toView(pi, img.x, img.y)
                canvas.drawRect(a.x, a.y, a.x + img.w * scale, a.y + img.h * scale, selectPaint)
            }
        }
        // 橡皮
        if (mode == Mode.ERASE) canvas.drawCircle(eraseView.x, eraseView.y, eraserSize * scale, eraserPaint)
        // 荧光圈
        if (circleStrokes.isNotEmpty() && circlePage in pages.indices) {
            canvas.save()
            canvas.translate(left, pageTop(circlePage) - scrollPos)
            canvas.scale(scale, scale)
            val alpha = (HIGHLIGHT ushr 24) * circleAlpha
            val color = (alpha.roundToInt() shl 24) or (HIGHLIGHT and 0xFFFFFF)
            for (s in circleStrokes) drawStroke(canvas, s, s.size, HIGHLIGHT_W, color)
            canvas.restore()
        }
        // 正在写的这一笔
        if (mode == Mode.DRAW && curN >= 2 && drawPage in pages.indices) {
            canvas.save()
            canvas.translate(left, pageTop(drawPage) - scrollPos)
            canvas.scale(scale, scale)
            if (tool == Tool.CIRCLE) drawStroke(canvas, cur, curN, HIGHLIGHT_W, HIGHLIGHT)
            else drawStroke(canvas, cur, curN, penWidth, penColor)
            canvas.restore()
        }
        // 化成粒子的字
        if (effects.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            val it = effects.iterator()
            while (it.hasNext()) {
                val fx = it.next()
                if (!fx.draw(canvas, now)) { fx.bmp.recycle(); it.remove() }
            }
            if (effects.isNotEmpty()) postInvalidateOnAnimation()
        }
        trimCache(first, last)
    }

    /** 放大时：把清楚的那张盖在糊的缓存图上（每页只在版本没变时用），不是最新的就等停下来再重画。 */
    private fun drawDetail(canvas: Canvas, first: Int, last: Int) {
        val d = detail
        var fresh = d != null && detailScale == scale && pageLeft() == detailLeft && scrollPos == detailScroll
        if (d != null && detailScale == scale) {
            val dx = pageLeft() - detailLeft; val dy = detailScroll - scrollPos
            for (i in first..last) {
                val page = pages[i]
                if (detailVersions[page] != page.version) { fresh = false; continue }
                val r = pageRect(i)
                canvas.save()
                canvas.clipRect(r)
                canvas.drawBitmap(d, dx, dy, null)
                canvas.restore()
            }
        }
        if (!fresh) { removeCallbacks(renderDetail); postDelayed(renderDetail, DETAIL_DELAY_MS) }
    }

    /** 按当前倍数重画屏幕上看得见的几页（清楚的那张）。 */
    private fun buildDetail() {
        if (zoom <= DETAIL_ZOOM || width <= 0 || height <= 0 || pageHpx <= 0f || mode == Mode.SCROLL) {
            if (mode == Mode.SCROLL) postDelayed(renderDetail, DETAIL_DELAY_MS)
            return
        }
        val bmp = detail?.takeIf { it.width == width && it.height == height && !it.isRecycled }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { detail?.recycle(); detail = it }
        bmp.eraseColor(0)
        val cv = Canvas(bmp)
        val left = pageLeft()
        val first = max(0, floor((scrollPos - topPad) / (pageHpx + gap)).toInt())
        val last = min(pages.lastIndex, floor((scrollPos + height - topPad) / (pageHpx + gap)).toInt())
        detailVersions.clear()
        for (i in first..last) {
            val top = pageTop(i) - scrollPos
            cv.save()
            cv.clipRect(left, top, left + pageWpx, top + pageHpx)
            cv.translate(left, top)
            drawPage(cv, pages[i], scale, skip = dragging)
            cv.restore()
            detailVersions[pages[i]] = pages[i].version
        }
        detailScale = scale; detailLeft = left; detailScroll = scrollPos
        invalidate()
    }

    private fun dropDetail() {
        removeCallbacks(renderDetail)
        detail?.recycle(); detail = null
        detailVersions.clear()
    }

    /** 页上新加了一笔或一个字（版本号刚加一）：直接补画到缓存图和清楚的那张上，不用整页重画。 */
    private fun drawIncremental(page: NotePage, draw: (Canvas) -> Unit) {
        cache[page]?.let { c ->
            if (c.version == page.version - 1 && !c.bmp.isRecycled) {
                val cv = Canvas(c.bmp); cv.scale(c.k, c.k); draw(cv); c.version = page.version
            }
        }
        val d = detail ?: return
        val pi = pages.indexOf(page)
        if (pi < 0 || detailScale != scale || detailVersions[page] != page.version - 1) return
        val cv = Canvas(d)
        val top = pageTop(pi) - detailScroll
        cv.clipRect(detailLeft, top, detailLeft + pageWpx, top + pageHpx)
        cv.translate(detailLeft, top); cv.scale(scale, scale)
        draw(cv)
        detailVersions[page] = page.version
    }

    private fun drawImageOnView(canvas: Canvas, pi: Int, img: NoteImage) {
        if (pi < 0) return
        val bmp = imageBitmap(img, (img.w * scale).roundToInt()) ?: return
        val a = toView(pi, img.x, img.y)
        canvas.drawBitmap(bmp, null, RectF(a.x, a.y, a.x + img.w * scale, a.y + img.h * scale), bitmapPaint)
    }

    private fun pageBitmap(page: NotePage): Bitmap {
        val k = cacheScale()
        val old = cache[page]
        if (old != null && old.version == page.version && old.k == k && !old.bmp.isRecycled) return old.bmp
        val w = max(1, (NotePaper.PAGE_W * k).toInt()); val h = max(1, (NotePaper.PAGE_H * k).toInt())
        // 尺寸没变就画在原来那张图上（橡皮擦的时候每一帧都会变，不用每次新建十几兆的图）
        val bmp = if (old != null && !old.bmp.isRecycled && old.bmp.width == w && old.bmp.height == h) old.bmp
        else { cache.remove(page)?.bmp?.recycle(); Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
        drawPage(Canvas(bmp), page, k, skip = dragging)
        cache[page] = Cached(bmp, page.version, k)
        return bmp
    }

    /** 整页画到画布上：纸色、贴图（在下面）、笔迹和 AI 写的字（在上面，可以在图上写批注）。 */
    private fun drawPage(cv: Canvas, page: NotePage, k: Float, skip: NoteImage? = null) {
        cv.drawColor(NotePaper.PAPER)
        for (img in page.images) {
            if (img === skip) continue
            val bmp = imageBitmap(img, (img.w * k).roundToInt()) ?: continue
            cv.drawBitmap(bmp, null, RectF(img.x * k, img.y * k, (img.x + img.w) * k, (img.y + img.h) * k), bitmapPaint)
        }
        cv.save()
        cv.scale(k, k)
        for (s in page.strokes) drawStroke(cv, s.pts, s.pts.size, s.width, s.color)
        for (g in page.glyphs) drawGlyph(cv, g)
        cv.restore()
    }

    /** 在页面坐标里写 AI 的一个字。 */
    private fun drawGlyph(cv: Canvas, g: NoteGlyph) {
        glyphPaint.typeface = AiInk.typeface(context, g.font)
        glyphPaint.textSize = g.size
        glyphPaint.color = g.color
        // 第 17 版龙藏体写下的旧字：宋体更宽，横向压到原来的宽度，免得挤在一起
        glyphPaint.textScaleX = if (g.font == AiInk.LEGACY_HAND) {
            val m = glyphPaint.measureText(g.ch)
            if (m > g.w && m > 0f) g.w / m else 1f
        } else 1f
        if (g.rot != 0f) {
            cv.save()
            cv.rotate(g.rot, g.x + g.w / 2, g.y - g.size * 0.35f)
            cv.drawText(g.ch, g.x, g.y, glyphPaint)
            cv.restore()
        } else cv.drawText(g.ch, g.x, g.y, glyphPaint)
    }

    /** 贴图按需要的宽度解码（同一宽度只解码一次）。 */
    private fun imageBitmap(img: NoteImage, widthPx: Int): Bitmap? {
        val key = img.id + "@" + widthPx
        imgCache[key]?.let { if (!it.isRecycled) return it }
        val f = NoteStore.imageFile(book, img.id)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= widthPx && widthPx > 0) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (imgCache.size > 24) { imgCache.values.forEach { it.recycle() }; imgCache.clear() }
        imgCache[key] = bmp
        return bmp
    }

    /** 只留看得见的页和前后各一页的缓存图，一页大约十几兆。 */
    private fun trimCache(first: Int, last: Int) {
        val keep = (max(0, first - 1)..min(pages.lastIndex, last + 1)).map { pages[it] }.toSet()
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) { e.value.bmp.recycle(); it.remove() }
        }
    }

    private fun dropPageCache(page: NotePage) { cache.remove(page)?.bmp?.recycle() }

    private fun clearPageCaches() {
        for (c in cache.values) c.bmp.recycle()
        cache.clear()
    }

    fun clearCache() {
        clearPageCaches()
        for (b in imgCache.values) b.recycle()
        imgCache.clear()
        dropDetail()
    }

    /** 在页面坐标里画一笔：相邻点之间用二次曲线连，写出来更顺。 */
    private fun drawStroke(cv: Canvas, pts: FloatArray, n: Int, width: Float, color: Int) {
        if (n < 2) return
        if (n == 2) {
            dotPaint.color = color
            cv.drawCircle(pts[0], pts[1], width / 2, dotPaint)
            return
        }
        path.rewind()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < n) {
            val mx = (pts[i - 2] + pts[i]) / 2; val my = (pts[i - 1] + pts[i + 1]) / 2
            path.quadTo(pts[i - 2], pts[i - 1], mx, my)
            i += 2
        }
        path.lineTo(pts[n - 2], pts[n - 1])
        strokePaint.color = color
        strokePaint.strokeWidth = width
        cv.drawPath(path, strokePaint)
    }

    // ---------- 化成粒子飘散 ----------

    /**
     * 一组字化成粒子：从左往右扫过去，扫到的地方字消失，变成一粒粒墨点往上、往右飘，越飘越淡。
     * rect 是这组字的范围（页面坐标），bmp 是这组字按 k 倍画好的图，size 是字号（决定飘多远）。
     */
    private inner class Dissolve(val page: NotePage, val rect: RectF, val bmp: Bitmap, k: Float, size: Float) {
        private val start = SystemClock.uptimeMillis()
        private val n: Int
        private val px: FloatArray
        private val py: FloatArray
        private val vx: FloatArray
        private val vy: FloatArray
        private val delay: FloatArray
        private val life: FloatArray
        private val color: IntArray
        private val wobble: FloatArray

        init {
            val w = bmp.width; val h = bmp.height
            val pix = IntArray(w * h)
            bmp.getPixels(pix, 0, w, 0, 0, w, h)
            val step = max(1, (2.4f * k / scale.coerceAtLeast(0.1f)).roundToInt())
            val picked = ArrayList<Int>()
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) { if ((pix[y * w + x] ushr 24) > 90) picked.add(y * w + x); x += step }
                y += step
            }
            val rnd = Random(start)
            val chosen = if (picked.size > MAX_PARTICLES) picked.shuffled(rnd).take(MAX_PARTICLES) else picked
            n = chosen.size
            px = FloatArray(n); py = FloatArray(n); vx = FloatArray(n); vy = FloatArray(n)
            delay = FloatArray(n); life = FloatArray(n); color = IntArray(n); wobble = FloatArray(n)
            for ((i, p) in chosen.withIndex()) {
                val bx = p % w; val by = p / w
                px[i] = rect.left + (bx + 0.5f) / k
                py[i] = rect.top + (by + 0.5f) / k
                vx[i] = (rnd.nextFloat() * 2.0f - 0.4f) * size * 1.2f
                vy[i] = -(0.7f + rnd.nextFloat() * 1.3f) * size * 1.5f
                delay[i] = (px[i] - rect.left) / max(1f, rect.width()) * SWEEP_MS
                life[i] = 850f + rnd.nextFloat() * 650f
                color[i] = pix[p] or (0xFF shl 24)
                wobble[i] = rnd.nextFloat() * 6.28f
            }
        }

        /** 画一帧，返回还要不要接着画。 */
        fun draw(c: Canvas, now: Long): Boolean {
            val pi = pages.indexOf(page)
            if (pi < 0) return false
            val t = (now - start).toFloat()
            val o = toView(pi, 0f, 0f)
            val sweep = rect.left + rect.width() * (t / SWEEP_MS).coerceIn(0f, 1f)
            var alive = false
            if (sweep < rect.right) {   // 还没扫到的字照常显示
                alive = true
                c.save()
                c.clipRect(o.x + sweep * scale, o.y + rect.top * scale, o.x + rect.right * scale, o.y + rect.bottom * scale)
                c.drawBitmap(bmp, null, RectF(o.x + rect.left * scale, o.y + rect.top * scale, o.x + rect.right * scale, o.y + rect.bottom * scale), bitmapPaint)
                c.restore()
            }
            val r0 = context.dp(1.4f)
            for (i in 0 until n) {
                val age = t - delay[i]
                if (age < 0f) { alive = true; continue }
                val a = age / life[i]
                if (a >= 1f) continue
                alive = true
                val sec = age / 1000f
                val x = px[i] + vx[i] * sec + sin(wobble[i] + sec * 9f) * rect.height() * 0.02f
                val y = py[i] + vy[i] * sec - 0.35f * vy[i].let { abs(it) } * sec * sec
                particlePaint.color = color[i]
                particlePaint.alpha = ((1f - a) * (1f - a) * 255).roundToInt().coerceIn(0, 255)
                c.drawCircle(o.x + x * scale, o.y + y * scale, r0 * (1f - 0.55f * a), particlePaint)
            }
            return alive
        }
    }

    // ---------- 给 AI 和摆回答框用 ----------

    /** 把第 i 页画成一张宽 widthPx 的图（发给 AI 用），不含荧光圈。 */
    fun renderPage(i: Int, widthPx: Int): Bitmap {
        val k = widthPx / NotePaper.PAGE_W
        val bmp = Bitmap.createBitmap(widthPx, (NotePaper.PAGE_H * k).roundToInt(), Bitmap.Config.ARGB_8888)
        drawPage(Canvas(bmp), pages[i], k)
        return bmp
    }

    /**
     * 第 i 页的墨迹分布：把页面分成 [GRID_W]×[GRID_H] 的格子，有笔迹、贴图或 AI 的字的格子为 true。
     * 摆回答框时用来找相对稀疏的地方。
     */
    fun inkGrid(i: Int): BooleanArray {
        val g = BooleanArray(GRID_W * GRID_H)
        val page = pages.getOrNull(i) ?: return g
        val cw = NotePaper.PAGE_W / GRID_W; val ch = NotePaper.PAGE_H / GRID_H
        fun mark(x: Float, y: Float) {
            val cx = (x / cw).toInt().coerceIn(0, GRID_W - 1); val cy = (y / ch).toInt().coerceIn(0, GRID_H - 1)
            g[cy * GRID_W + cx] = true
        }
        fun fill(l: Float, t: Float, r: Float, b: Float) {
            var y = t; while (y <= b) { var x = l; while (x <= r) { mark(x, y); x += cw }; y += ch }
        }
        for (s in page.strokes) {
            var j = 0
            while (j + 1 < s.pts.size) {
                mark(s.pts[j], s.pts[j + 1])
                if (j + 3 < s.pts.size) {   // 两点离得远就在中间补点，免得漏格
                    val dx = s.pts[j + 2] - s.pts[j]; val dy = s.pts[j + 3] - s.pts[j + 1]
                    val steps = (hypot(dx, dy) / (cw / 2)).toInt()
                    for (t in 1 until steps) mark(s.pts[j] + dx * t / steps, s.pts[j + 1] + dy * t / steps)
                }
                j += 2
            }
        }
        for (img in page.images) fill(img.x, img.y, img.x + img.w, img.y + img.h)
        for (gl in page.glyphs) { val b = gl.box(); fill(b.left, b.top, b.right, b.bottom) }
        return g
    }

    fun pageCount() = pages.size

    /** 屏幕中间落在哪一页，以及那一点的页面坐标（夹在纸上）。插入图片时放在这里。 */
    fun viewCenterOnPage(): Pair<Int, PointF> {
        val cy = scrollPos + height / 2f - topPad
        val pi = floor(cy / (pageHpx + gap)).toInt().coerceIn(0, pages.lastIndex)
        val p = toPage(pi, width / 2f, height / 2f)
        return pi to PointF(p.x.coerceIn(0f, NotePaper.PAGE_W), p.y.coerceIn(0f, NotePaper.PAGE_H))
    }

    /** 选中一张图（插入后马上可以拖）。 */
    fun selectImage(pageIndex: Int, img: NoteImage) {
        val page = pages.getOrNull(pageIndex) ?: return
        selected = page to img
        invalidate()
    }

    companion object {
        /** 纸张外面的底色。 */
        const val BACKDROP = 0xFFF4F3F0.toInt()
        /** 荧光圈：半透明的橙黄色，粗。 */
        const val HIGHLIGHT = 0x88FFB300.toInt()
        const val HIGHLIGHT_W = 9f
        const val CIRCLE_PAUSE_MS = 1000L
        const val GRID_W = 50
        const val GRID_H = 36
        /** 橡皮擦三档半径（页面坐标，页宽 1000）。 */
        val ERASER_SIZES = floatArrayOf(6f, 14f, 30f)
        const val LONG_PRESS_MS = 600L
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 4f
        /** 放大超过这个倍数才另画清楚的那张。 */
        private const val DETAIL_ZOOM = 1.05f
        private const val DETAIL_DELAY_MS = 120L
        private const val SWEEP_MS = 520f
        private const val MAX_PARTICLES = 3500
    }
}

/** 橡皮擦：去掉笔迹里离橡皮划过的线段不到 r（再加半个笔宽）的部分，一笔被擦断就分成几笔；AI 的字碰到就整个擦掉。 */
object Eraser {
    /** 返回擦过以后的笔迹；什么都没擦到返回 null。 */
    fun erase(strokes: List<Stroke>, ax: Float, ay: Float, bx: Float, by: Float, r: Float): List<Stroke>? {
        var out: ArrayList<Stroke>? = null
        for (i in strokes.indices) {
            val s = strokes[i]
            val rr = r + s.width / 2
            val b = s.box
            val far = max(ax, bx) + rr < b[0] || min(ax, bx) - rr > b[2] || max(ay, by) + rr < b[1] || min(ay, by) - rr > b[3]
            val pieces = if (far) null else cut(s, ax, ay, bx, by, rr)
            if (pieces == null) { out?.add(s); continue }
            if (out == null) out = ArrayList<Stroke>(strokes.size + 4).also { o -> for (j in 0 until i) o.add(strokes[j]) }
            out.addAll(pieces)
        }
        return out
    }

    /** 橡皮这一段碰到的 AI 的字（字的外框离线段不到 r）。 */
    fun glyphsHit(glyphs: List<NoteGlyph>, ax: Float, ay: Float, bx: Float, by: Float, r: Float): List<NoteGlyph> {
        if (glyphs.isEmpty()) return emptyList()
        val out = ArrayList<NoteGlyph>()
        val len = hypot(bx - ax, by - ay)
        val steps = max(1, (len / max(1f, r / 2)).toInt())
        for (g in glyphs) {
            val b = g.box()
            // 先粗筛
            if (max(ax, bx) + r < b.left || min(ax, bx) - r > b.right || max(ay, by) + r < b.top || min(ay, by) - r > b.bottom) continue
            // 收紧一点：字的外框四周有空白，碰到字的中间部分才算
            val ix = b.width() * 0.12f; val iy = b.height() * 0.12f
            for (t in 0..steps) {
                val x = ax + (bx - ax) * t / steps; val y = ay + (by - ay) * t / steps
                val dx = max(max(b.left + ix - x, 0f), x - (b.right - ix))
                val dy = max(max(b.top + iy - y, 0f), y - (b.bottom - iy))
                if (hypot(dx, dy) < r) { out.add(g); break }
            }
        }
        return out
    }

    /** 擦到了就返回剩下的几段（可能是空的），没擦到返回 null。 */
    private fun cut(s: Stroke, ax: Float, ay: Float, bx: Float, by: Float, rr: Float): List<Stroke>? {
        val pts = densify(s.pts, max(0.5f, rr / 2))
        val n = pts.size / 2
        val hit = BooleanArray(n)
        var any = false
        for (k in 0 until n) if (distToSegment(pts[2 * k], pts[2 * k + 1], ax, ay, bx, by) < rr) { hit[k] = true; any = true }
        if (!any) return null
        val out = ArrayList<Stroke>()
        var start = -1
        for (k in 0..n) {
            val keep = k < n && !hit[k]
            if (keep && start < 0) start = k
            if (!keep && start >= 0) {
                if (k - start >= 2) out.add(Stroke(s.color, s.width, pts.copyOfRange(2 * start, 2 * k)).also { it.seq = s.seq })   // 只剩一个点的碎渣不要
                start = -1
            }
        }
        return out
    }

    /** 相邻两点离得比 step 远就在中间补点，免得橡皮从两点之间划过却没擦到。 */
    private fun densify(p: FloatArray, step: Float): FloatArray {
        if (p.size < 4) return p
        val out = ArrayList<Float>(p.size * 2)
        var j = 0
        while (j + 1 < p.size) {
            out.add(p[j]); out.add(p[j + 1])
            if (j + 3 < p.size) {
                val dx = p[j + 2] - p[j]; val dy = p[j + 3] - p[j + 1]
                val d = hypot(dx, dy)
                if (d > step) {
                    val m = kotlin.math.ceil(d / step).toInt()
                    for (t in 1 until m) { out.add(p[j] + dx * t / m); out.add(p[j + 1] + dy * t / m) }
                }
            }
            j += 2
        }
        return out.toFloatArray()
    }

    fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 <= 1e-6f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
