package app.quanwen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 笔记本的画布：纸张一页页竖着排，宽度撑满（左右留边），上下滚动。
 *
 * 输入：
 *  - 用过触控笔以后（[Prefs.stylusSeen]）只有笔能写，手指一根就能滚动，手掌碰到也不会画。
 *  - 没用过笔的设备上一根手指写字，两根手指滚动。
 *
 * 每页画成一张缓存图（只留看得见的几页），滚动时直接贴图；正在写的这一笔单独画在上面。
 * 撤回、还原一次一笔。在最后一页写下第一笔时自动在后面加一张空白页。
 */
@SuppressLint("ViewConstructor")
class NoteCanvas(
    ctx: Context,
    private val book: Notebook,
    private val pages: MutableList<NotePage>,
    /** 内容或撤回状态变了（让工具栏刷新按钮、让页面记得保存）。 */
    private val onChanged: () -> Unit,
) : View(ctx) {

    var penColor = NotePaper.COLORS[0]
    var penWidth = NotePaper.WIDTHS[1]

    private val prefs = Prefs(ctx)
    private var stylusSeen = prefs.stylusSeen

    // ---------- 排版 ----------
    private val margin = ctx.dp(16f)
    private val gap = ctx.dp(22f)
    private val topPad = ctx.dp(12f)
    private val bottomPad = ctx.dp(120f)
    private var scale = 1f            // 页面坐标 → 像素
    private var pageWpx = 0f
    private var pageHpx = 0f
    private var scrollPos = 0f
    private val scroller = OverScroller(ctx)
    private var velocity: VelocityTracker? = null

    private fun pageTop(i: Int) = topPad + i * (pageHpx + gap)
    private val contentHeight get() = topPad + pages.size * (pageHpx + gap) - gap + bottomPad
    private val maxScroll get() = max(0f, contentHeight - height)

    // ---------- 撤回 / 还原 ----------
    private class Op(val page: NotePage, val stroke: Stroke)
    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    // ---------- 每页的缓存图 ----------
    private class Cached(val bmp: Bitmap, var version: Int)
    private val cache = LinkedHashMap<NotePage, Cached>()

    // ---------- 正在写的这一笔 ----------
    private enum class Mode { NONE, DRAW, SCROLL }
    private var mode = Mode.NONE
    private var drawPointer = -1
    private var drawPage = -1
    private var cur = FloatArray(256)
    private var curN = 0
    private var lastAvgY = 0f
    private var lastPointerCount = 0

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(1f); color = 0x22000000
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.MUTED; textSize = ctx.dp(11f); textAlign = Paint.Align.RIGHT
    }
    private val path = Path()

    // ---------- 尺寸 ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 转屏或分屏拖动时，保持屏幕顶上还是同一页的同一位置
        val anchor = if (pageHpx > 0) (scrollPos - topPad) / (pageHpx + gap) else 0f
        pageWpx = w - 2 * margin
        scale = pageWpx / NotePaper.PAGE_W
        pageHpx = NotePaper.PAGE_H * scale
        clearCache()
        scrollPos = (topPad + anchor * (pageHpx + gap)).coerceIn(0f, maxScroll)
    }

    // ---------- 撤回 / 还原 ----------

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        val i = op.page.strokes.lastIndexOf(op.stroke)
        if (i >= 0) op.page.strokes.removeAt(i)
        op.page.changed()
        cache.remove(op.page)?.bmp?.recycle()   // 拿掉一笔只能整页重画
        redoStack.addLast(op)
        invalidate(); onChanged()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        addStroke(op.page, op.stroke)
        undoStack.addLast(op)
        invalidate(); onChanged()
    }

    private fun addStroke(page: NotePage, s: Stroke) {
        page.strokes.add(s)
        page.changed()
        cache[page]?.let { c ->   // 缓存图上直接补画这一笔，不用整页重画
            val cv = Canvas(c.bmp); cv.scale(scale, scale); drawStroke(cv, s.pts, s.pts.size, s.width, s.color)
            c.version = page.version
        }
    }

    private fun commit() {
        val pi = drawPage
        if (pi !in pages.indices || curN < 2) return
        val page = pages[pi]
        val wasEmpty = page.strokes.isEmpty()
        val s = Stroke(penColor, penWidth, cur.copyOf(curN))
        addStroke(page, s)
        undoStack.addLast(Op(page, s))
        if (undoStack.size > 1000) undoStack.removeFirst()
        redoStack.clear()
        if (wasEmpty && pi == pages.lastIndex) pages.add(NoteStore.addPage(book))   // 总留一张空白页在最后
        onChanged()
    }

    // ---------- 触摸 ----------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle(); velocity = VelocityTracker.obtain()
                val stylus = isStylus(e, 0)
                if (stylus && !stylusSeen) { stylusSeen = true; prefs.stylusSeen = true }
                if ((stylus || !stylusSeen) && startDraw(e, 0)) {
                    requestUnbufferedDispatch(e)   // 降低笔迹延迟
                } else startScroll(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                when {
                    // 手先碰到（滚动中），接着笔落下：改成写字
                    mode == Mode.SCROLL && isStylus(e, idx) -> { stopScroll(); startDraw(e, idx) }
                    // 没用过笔的设备上一根手指在写，第二根手指落下：这一笔作废，改成两指滚动
                    mode == Mode.DRAW && !isStylus(e, e.findPointerIndex(drawPointer)) && !isStylus(e, idx) -> {
                        if (curN > 40) { commit(); endDraw() } else cancelDraw()   // 已经写了一长笔就留下
                        startScroll(e)
                    }
                    mode == Mode.SCROLL -> resetScrollBaseline(e)
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> {
                    val idx = e.findPointerIndex(drawPointer)
                    if (idx >= 0) {
                        for (h in 0 until e.historySize) addPoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                        addPoint(e.getX(idx), e.getY(idx))
                        invalidate()
                    }
                }
                Mode.SCROLL -> {
                    velocity?.addMovement(e)
                    if (e.pointerCount != lastPointerCount) resetScrollBaseline(e)
                    val y = avgY(e)
                    scrollTo(scrollPos - (y - lastAvgY))
                    lastAvgY = y
                }
                Mode.NONE -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.DRAW && e.getPointerId(e.actionIndex) == drawPointer) { commit(); endDraw() }
                else if (mode == Mode.SCROLL) resetScrollBaseline(e, excluding = e.actionIndex)
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.DRAW -> { commit(); endDraw() }
                    Mode.SCROLL -> {
                        velocity?.addMovement(e)
                        velocity?.computeCurrentVelocity(1000)
                        val vy = velocity?.yVelocity ?: 0f
                        scroller.fling(0, scrollPos.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll.toInt())
                        postInvalidateOnAnimation()
                    }
                    Mode.NONE -> {}
                }
                mode = Mode.NONE
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> { if (mode == Mode.DRAW) cancelDraw(); mode = Mode.NONE }
        }
        return true
    }

    private fun isStylus(e: MotionEvent, idx: Int): Boolean {
        if (idx < 0 || idx >= e.pointerCount) return false
        val t = e.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    /** 落点在某一页上才开始写，返回是否开始了。 */
    private fun startDraw(e: MotionEvent, idx: Int): Boolean {
        val x = e.getX(idx); val y = e.getY(idx)
        val pi = pageAt(x, y)
        if (pi < 0) return false
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
        cur[curN++] = ((x - margin) / scale).coerceIn(0f, NotePaper.PAGE_W)
        cur[curN++] = ((y - top) / scale).coerceIn(0f, NotePaper.PAGE_H)
    }

    private fun endDraw() { mode = Mode.NONE; drawPointer = -1; curN = 0; invalidate() }
    private fun cancelDraw() { curN = 0; drawPointer = -1; mode = Mode.NONE; invalidate() }

    private fun startScroll(e: MotionEvent) {
        mode = Mode.SCROLL
        velocity?.addMovement(e)
        resetScrollBaseline(e)
    }

    private fun stopScroll() { scroller.forceFinished(true); mode = Mode.NONE }

    private fun resetScrollBaseline(e: MotionEvent, excluding: Int = -1) {
        lastAvgY = avgY(e, excluding)
        lastPointerCount = e.pointerCount - (if (excluding >= 0) 1 else 0)
    }

    private fun avgY(e: MotionEvent, excluding: Int = -1): Float {
        var s = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != excluding) { s += e.getY(i); n++ }
        return if (n == 0) 0f else s / n
    }

    private fun scrollTo(y: Float) {
        scrollPos = y.coerceIn(0f, maxScroll)
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPos = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    /** 视图坐标落在哪一页上，不在页上返回 -1。 */
    private fun pageAt(x: Float, y: Float): Int {
        if (x < margin || x > margin + pageWpx) return -1
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
        val first = max(0, floor((scrollPos - topPad) / (pageHpx + gap)).toInt())
        val last = min(pages.lastIndex, floor((scrollPos + height - topPad) / (pageHpx + gap)).toInt())
        for (i in first..last) {
            val top = pageTop(i) - scrollPos
            val bmp = pageBitmap(pages[i])
            canvas.drawBitmap(bmp, margin, top, null)
            canvas.drawRect(margin, top, margin + pageWpx, top + pageHpx, edgePaint)
            canvas.drawText("${i + 1} / ${pages.size}", margin + pageWpx, top + pageHpx + gap * 0.7f, numberPaint)
        }
        // 正在写的这一笔
        if (mode == Mode.DRAW && curN >= 2 && drawPage in pages.indices) {
            canvas.save()
            canvas.translate(margin, pageTop(drawPage) - scrollPos)
            canvas.scale(scale, scale)
            drawStroke(canvas, cur, curN, penWidth, penColor)
            canvas.restore()
        }
        trimCache(first, last)
    }

    private fun pageBitmap(page: NotePage): Bitmap {
        cache[page]?.let { c -> if (c.version == page.version) return c.bmp }
        cache.remove(page)?.bmp?.recycle()
        val bmp = Bitmap.createBitmap(max(1, pageWpx.toInt()), max(1, pageHpx.toInt()), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(NotePaper.PAPER)
        cv.scale(scale, scale)
        for (s in page.strokes) drawStroke(cv, s.pts, s.pts.size, s.width, s.color)
        cache[page] = Cached(bmp, page.version)
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

    fun clearCache() {
        for (c in cache.values) c.bmp.recycle()
        cache.clear()
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

    companion object {
        /** 纸张外面的底色。 */
        const val BACKDROP = 0xFFF4F3F0.toInt()
    }
}
