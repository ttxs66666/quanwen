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
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 笔记本的画布：纸张一页页竖着排，宽度撑满（左右留边），上下滚动。
 *
 * 四种工具：
 *  - 笔：黑红蓝三色、三档粗细。撤回、还原一次一笔。
 *  - 橡皮擦：划过哪里擦掉哪里（一笔被擦断就分成几笔），三档大小。笔尾的橡皮头、按着笔上的按键也能擦。
 *  - 圈：荧光笔画一个圈，停笔 1 秒就发给 AI（[onCircle]），荧光慢慢淡掉，不留在纸上；发完自动回到笔。
 *  - 移动图片：只有这个模式下才能拖动贴在纸上的图片（「留下」的回答），避免写字时误碰。选中的图片右上角有 × 可以删掉。
 * 任何工具下（圈除外）长按一张图片，都会弹出删除 / 移动的菜单（[onImageLongPress]）。
 *
 * 输入：用过触控笔以后只有笔能写，手指一根就滚动（防手掌误触）；没用过笔的设备上一根手指写、两根手指滚动。
 * 每页画成一张缓存图（只留看得见的几页），滚动时直接贴图；正在写的这一笔、荧光圈、正在拖的图片单独画在上面。
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
    /** 滚动或尺寸变了（贴着纸的回答框要跟着走）。 */
    var onViewport: (() -> Unit)? = null
    /** 长按了一张图片：第几页、哪张图、按的位置（画布坐标）。菜单关掉时调 [clearPressed]。 */
    var onImageLongPress: ((Int, NoteImage, Float, Float) -> Unit)? = null

    private val prefs = Prefs(ctx)
    private var stylusSeen = prefs.stylusSeen

    // ---------- 排版 ----------
    private val margin = ctx.dp(16f)
    private val gap = ctx.dp(22f)
    private val topPad = ctx.dp(12f)
    private val bottomPad = ctx.dp(120f)
    /** 页面坐标 → 像素。 */
    var scale = 1f
        private set
    private var pageWpx = 0f
    private var pageHpx = 0f
    private var scrollPos = 0f
    private val scroller = OverScroller(ctx)
    private var velocity: VelocityTracker? = null

    private fun pageTop(i: Int) = topPad + i * (pageHpx + gap)
    private val contentHeight get() = topPad + pages.size * (pageHpx + gap) - gap + bottomPad
    private val maxScroll get() = max(0f, contentHeight - height)

    // ---------- 撤回 / 还原 ----------
    private sealed class Op(val page: NotePage) {
        class AddStroke(page: NotePage, val stroke: Stroke) : Op(page)
        class AddImage(page: NotePage, val img: NoteImage) : Op(page)
        class RemoveImage(page: NotePage, val img: NoteImage) : Op(page)
        class MoveImage(page: NotePage, val img: NoteImage, val fx: Float, val fy: Float, val tx: Float, val ty: Float) : Op(page)
        /** 橡皮擦的一下：这一页擦之前、擦之后的笔迹。 */
        class ReplaceStrokes(page: NotePage, val before: List<Stroke>, val after: List<Stroke>) : Op(page)
    }
    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    // ---------- 缓存 ----------
    private class Cached(val bmp: Bitmap, var version: Int)
    private val cache = LinkedHashMap<NotePage, Cached>()
    /** 贴图按当前缩放解码好的位图。 */
    private val imgCache = HashMap<String, Bitmap>()

    // ---------- 正在进行的手势 ----------
    private enum class Mode { NONE, DRAW, SCROLL, DRAG, ERASE }
    private var mode = Mode.NONE
    private var drawPointer = -1
    private var drawPage = -1
    private var cur = FloatArray(256)
    private var curN = 0
    private var lastAvgY = 0f
    private var lastPointerCount = 0

    // 圈
    private var circlePage = -1
    private val circleStrokes = ArrayList<FloatArray>()
    private var circleAlpha = 1f
    private var fade: ValueAnimator? = null
    private val sendCircle = Runnable { finishCircle() }

    // 橡皮擦：这一下擦之前的笔迹（擦到东西才拍），上一个点（页面坐标），橡皮在屏幕上的位置
    private var eraseBefore: List<Stroke>? = null
    private val eraseLast = PointF()
    private val eraseView = PointF()

    // 长按图片
    private var pressImg: Pair<Int, NoteImage>? = null
    private val pressDown = PointF()
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val longPress = Runnable { fireLongPress() }
    /** 长按着的图片：菜单开着时画一个虚线框。 */
    private var pressed: Pair<NotePage, NoteImage>? = null

    // 移动图片
    private var selected: Pair<NotePage, NoteImage>? = null
    private var dragging: NoteImage? = null
    private var dragFrom = PointF()
    private var dragDown = PointF()

    // ---------- 画笔 ----------
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
        pageWpx = w - 2 * margin
        scale = pageWpx / NotePaper.PAGE_W
        pageHpx = NotePaper.PAGE_H * scale
        clearCache()
        scrollPos = (topPad + anchor * (pageHpx + gap)).coerceIn(0f, maxScroll)
        onViewport?.invoke()
    }

    // ---------- 坐标 ----------

    /** 页面坐标 → 画布坐标。 */
    fun toView(page: Int, x: Float, y: Float) = PointF(margin + x * scale, pageTop(page) - scrollPos + y * scale)

    /** 画布坐标在某一页上的页面坐标。 */
    fun toPage(page: Int, vx: Float, vy: Float) = PointF((vx - margin) / scale, (vy - pageTop(page) + scrollPos) / scale)

    fun pageRect(page: Int) = RectF(margin, pageTop(page) - scrollPos, margin + pageWpx, pageTop(page) - scrollPos + pageHpx)

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
            is Op.ReplaceStrokes -> { page.strokes.clear(); page.strokes.addAll(if (forward) op.after else op.before) }
        }
        if (op !is Op.AddStroke) { page.changed(); dropPageCache(page) }
        if (selected?.second?.let { s -> page.images.none { it === s } } == true) selected = null
    }

    private fun addStrokeToPage(page: NotePage, s: Stroke) {
        page.strokes.add(s)
        page.changed()
        cache[page]?.let { c ->   // 缓存图上直接补画这一笔，不用整页重画
            val cv = Canvas(c.bmp); cv.scale(scale, scale); drawStroke(cv, s.pts, s.pts.size, s.width, s.color)
            c.version = page.version
        }
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
        val wasEmpty = page.strokes.isEmpty() && page.images.isEmpty()
        val s = Stroke(penColor, penWidth, cur.copyOf(curN))
        addStrokeToPage(page, s)
        push(Op.AddStroke(page, s))
        if (wasEmpty && pi == pages.lastIndex) pages.add(NoteStore.addPage(book))   // 总留一张空白页在最后
        onChanged()
    }

    /** 贴一张图到某一页（「留下」的回答）。 */
    fun addImage(pageIndex: Int, img: NoteImage) {
        val page = pages.getOrNull(pageIndex) ?: return
        val wasEmpty = page.strokes.isEmpty() && page.images.isEmpty()
        page.images.add(img)
        page.changed(); dropPageCache(page)
        push(Op.AddImage(page, img))
        if (wasEmpty && pageIndex == pages.lastIndex) pages.add(NoteStore.addPage(book))
        invalidate(); onChanged()
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
                    // 手先碰到（滚动中），接着笔落下：改成写字（或擦）
                    mode == Mode.SCROLL && tool != Tool.MOVE && isStylus(e, idx) -> {
                        stopScroll()
                        if (erases(e, idx)) startErase(e, idx) else startDraw(e, idx)
                    }
                    // 没用过笔的设备上一根手指在擦，第二根手指落下：改成两指滚动
                    mode == Mode.ERASE && !isStylus(e, e.findPointerIndex(drawPointer)) && !isStylus(e, idx) -> { endErase(); startScroll(e) }
                    // 没用过笔的设备上一根手指在写，第二根手指落下：改成两指滚动
                    mode == Mode.DRAW && !isStylus(e, e.findPointerIndex(drawPointer)) && !isStylus(e, idx) -> {
                        if (curN > 40) endStroke() else cancelDraw()   // 已经写了一长笔就留下
                        startScroll(e)
                    }
                    mode == Mode.SCROLL -> resetScrollBaseline(e)
                }
            }
            MotionEvent.ACTION_MOVE -> { checkLongPressSlop(e); when (mode) {
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
                Mode.SCROLL -> {
                    velocity?.addMovement(e)
                    if (e.pointerCount != lastPointerCount) resetScrollBaseline(e)
                    val y = avgY(e)
                    scrollTo(scrollPos - (y - lastAvgY))
                    lastAvgY = y
                }
                Mode.DRAG -> dragTo(e.x, e.y)
                Mode.NONE -> {}
            } }
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
                    Mode.SCROLL -> {
                        velocity?.addMovement(e)
                        velocity?.computeCurrentVelocity(1000)
                        val vy = velocity?.yVelocity ?: 0f
                        scroller.fling(0, scrollPos.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll.toInt())
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
        mode = Mode.ERASE
        drawPointer = e.getPointerId(idx)
        drawPage = pi
        eraseBefore = null
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

    /** 擦掉从上一个点到 (x, y) 这一段划过的笔迹。页面缓存图按版本号重画，不用每次新建。 */
    private fun eraseSegment(x: Float, y: Float) {
        val page = pages.getOrNull(drawPage) ?: return
        val out = Eraser.erase(page.strokes, eraseLast.x, eraseLast.y, x, y, eraserSize)
        eraseLast.set(x, y)
        if (out == null) return
        if (eraseBefore == null) eraseBefore = ArrayList(page.strokes)
        page.strokes.clear(); page.strokes.addAll(out)
        page.changed()
    }

    /** 这一下擦完：擦到了东西就记一步撤回。 */
    private fun endErase() {
        val page = pages.getOrNull(drawPage)
        val before = eraseBefore
        if (page != null && before != null) {
            push(Op.ReplaceStrokes(page, before, ArrayList(page.strokes)))
            onChanged()
        }
        eraseBefore = null
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
        when (mode) {   // 这一下不算写字、擦、滚动或拖动
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
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
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
        }
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

    /** 一笔写完：笔就存进页里，圈就先攒着，停笔 1 秒后发出去。 */
    private fun endStroke() {
        if (tool == Tool.CIRCLE) {
            if (curN >= 4) {
                circlePage = drawPage
                circleStrokes.add(cur.copyOf(curN))
                postDelayed(sendCircle, CIRCLE_PAUSE_MS)
            }
        } else commitStroke()
        mode = Mode.NONE; drawPointer = -1; curN = 0
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

    // ---------- 滚动 ----------

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
        onViewport?.invoke()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPos = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
            onViewport?.invoke()
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
            canvas.drawBitmap(pageBitmap(pages[i]), margin, top, null)
            canvas.drawRect(margin, top, margin + pageWpx, top + pageHpx, edgePaint)
            canvas.drawText("${i + 1} / ${pages.size}", margin + pageWpx, top + pageHpx + gap * 0.7f, numberPaint)
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
            canvas.translate(margin, pageTop(circlePage) - scrollPos)
            canvas.scale(scale, scale)
            val alpha = (HIGHLIGHT ushr 24) * circleAlpha
            val color = (alpha.roundToInt() shl 24) or (HIGHLIGHT and 0xFFFFFF)
            for (s in circleStrokes) drawStroke(canvas, s, s.size, HIGHLIGHT_W, color)
            canvas.restore()
        }
        // 正在写的这一笔
        if (mode == Mode.DRAW && curN >= 2 && drawPage in pages.indices) {
            canvas.save()
            canvas.translate(margin, pageTop(drawPage) - scrollPos)
            canvas.scale(scale, scale)
            if (tool == Tool.CIRCLE) drawStroke(canvas, cur, curN, HIGHLIGHT_W, HIGHLIGHT)
            else drawStroke(canvas, cur, curN, penWidth, penColor)
            canvas.restore()
        }
        trimCache(first, last)
    }

    private fun drawImageOnView(canvas: Canvas, pi: Int, img: NoteImage) {
        if (pi < 0) return
        val bmp = imageBitmap(img, (img.w * scale).roundToInt()) ?: return
        val a = toView(pi, img.x, img.y)
        canvas.drawBitmap(bmp, null, RectF(a.x, a.y, a.x + img.w * scale, a.y + img.h * scale), bitmapPaint)
    }

    private fun pageBitmap(page: NotePage): Bitmap {
        val old = cache[page]
        if (old != null && old.version == page.version) return old.bmp
        val w = max(1, pageWpx.toInt()); val h = max(1, pageHpx.toInt())
        // 尺寸没变就画在原来那张图上（橡皮擦的时候每一帧都会变，不用每次新建十几兆的图）
        val bmp = if (old != null && !old.bmp.isRecycled && old.bmp.width == w && old.bmp.height == h) old.bmp
        else { cache.remove(page)?.bmp?.recycle(); Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
        drawPage(Canvas(bmp), page, scale, skip = dragging)
        cache[page] = Cached(bmp, page.version)
        return bmp
    }

    /** 整页画到画布上：纸色、贴图（在下面）、笔迹（在上面，可以在图上写批注）。 */
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
        cv.restore()
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

    fun clearCache() {
        for (c in cache.values) c.bmp.recycle()
        cache.clear()
        for (b in imgCache.values) b.recycle()
        imgCache.clear()
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

    // ---------- 给 AI 和摆回答框用 ----------

    /** 把第 i 页画成一张宽 widthPx 的图（发给 AI 用），不含荧光圈。 */
    fun renderPage(i: Int, widthPx: Int): Bitmap {
        val k = widthPx / NotePaper.PAGE_W
        val bmp = Bitmap.createBitmap(widthPx, (NotePaper.PAGE_H * k).roundToInt(), Bitmap.Config.ARGB_8888)
        drawPage(Canvas(bmp), pages[i], k)
        return bmp
    }

    /**
     * 第 i 页的墨迹分布：把页面分成 [GRID_W]×[GRID_H] 的格子，有笔迹或贴图的格子为 true。
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
        for (img in page.images) {
            var y = img.y; while (y <= img.y + img.h) { var x = img.x; while (x <= img.x + img.w) { mark(x, y); x += cw }; y += ch }
        }
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
        /** 橡皮擦三档半径（页面坐标，页宽 1000）。 */
        val ERASER_SIZES = floatArrayOf(6f, 14f, 30f)
        const val LONG_PRESS_MS = 600L
        const val GRID_W = 50
        const val GRID_H = 36
    }
}

/** 橡皮擦：去掉笔迹里离橡皮划过的线段不到 r（再加半个笔宽）的部分，一笔被擦断就分成几笔。 */
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
                if (k - start >= 2) out.add(Stroke(s.color, s.width, pts.copyOfRange(2 * start, 2 * k)))   // 只剩一个点的碎渣不要
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
