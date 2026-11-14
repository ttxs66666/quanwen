package app.quanwen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

/**
 * App 内圈题用的画布：把一张图（截图或照片）完整显示出来，在上面圈题。
 * - 笔（或者没用过笔时的单指）画圈；抬笔后停 [SEND_DELAY_MS] 就交给 [onDone]，这段时间里接着画会并进同一个圈。
 * - 双指缩放、拖动；用过笔以后，单指也只负责拖动（防手掌误触）。
 * - 发出去的圈会留在图上（颜色变浅），下一次落笔时清掉。
 */
@SuppressLint("ViewConstructor")
class ImageLassoView(context: Context, private val onDone: (List<FloatArray>) -> Unit) : View(context) {
    private val prefs = Prefs(context)
    var bitmap: Bitmap? = null
        private set

    private val strokes = mutableListOf<FloatArray>()
    private var current: ArrayList<Float>? = null
    private var sent = false
    private val sendNow = Runnable { if (strokes.isNotEmpty()) { sent = true; invalidate(); onDone(strokes.toList()) } }

    /** 图片坐标 → 视图坐标。 */
    private val m = Matrix()
    private val inv = Matrix()
    private var baseScale = 1f

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val frame = Paint().apply { color = 0x22000000; style = Paint.Style.STROKE; strokeWidth = context.dp(1f) }

    private enum class Mode { NONE, DRAW, PAN, ZOOM }
    private var mode = Mode.NONE
    private var lastX = 0f; private var lastY = 0f
    private var lastDist = 0f; private var lastMidX = 0f; private var lastMidY = 0f

    fun setImage(b: Bitmap) {
        removeCallbacks(sendNow)
        bitmap = b
        strokes.clear(); current = null; sent = false
        fit()
        invalidate()
    }

    fun clearStrokes() {
        removeCallbacks(sendNow)
        strokes.clear(); current = null; sent = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { fit() }

    /** 图片完整放进视图，居中。 */
    private fun fit() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val pad = context.dp(8f)
        val s = minOf((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
        baseScale = s
        m.reset()
        m.postScale(s, s)
        m.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        m.invert(inv)
    }

    private fun scale(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        c.save()
        c.concat(m)
        c.drawBitmap(b, 0f, 0f, bmpPaint)
        c.drawRect(0f, 0f, b.width.toFloat(), b.height.toFloat(), frame.apply { strokeWidth = context.dp(1f) / scale() })
        val all = ArrayList(strokes)
        current?.let { all.add(it.toFloatArray()) }
        ink.color = if (sent && current == null) 0x99E53935.toInt() else 0xFFE53935.toInt()
        ink.strokeWidth = context.dp(3.5f) / scale()
        c.drawPath(ImageUtil.strokesPath(all), ink)
        c.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bitmap ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tool = e.getToolType(0)
                val pen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
                if (pen && !prefs.stylusSeen) prefs.stylusSeen = true
                if (pen || !prefs.stylusSeen) {
                    removeCallbacks(sendNow)
                    if (sent) { strokes.clear(); sent = false }
                    mode = Mode.DRAW
                    current = arrayListOf<Float>().also { add(it, e.x, e.y) }
                } else {
                    mode = Mode.PAN; lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.DRAW) current = null          // 第二根手指落下：这一笔不算，改成缩放
                if (e.pointerCount >= 2) {
                    mode = Mode.ZOOM
                    lastDist = dist(e); lastMidX = midX(e); lastMidY = midY(e)
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> current?.let { s ->
                    for (i in 0 until e.historySize) add(s, e.getHistoricalX(0, i), e.getHistoricalY(0, i))
                    add(s, e.x, e.y)
                }
                Mode.PAN -> { m.postTranslate(e.x - lastX, e.y - lastY); lastX = e.x; lastY = e.y; clampAndInvert(b) }
                Mode.ZOOM -> if (e.pointerCount >= 2) {
                    val d = dist(e); val mx = midX(e); val my = midY(e)
                    if (lastDist > 0f) {
                        var f = d / lastDist
                        val target = scale() * f
                        val minS = baseScale; val maxS = baseScale * 8f
                        if (target < minS) f = minS / scale(); if (target > maxS) f = maxS / scale()
                        m.postScale(f, f, mx, my)
                    }
                    m.postTranslate(mx - lastMidX, my - lastMidY)
                    lastDist = d; lastMidX = mx; lastMidY = my
                    clampAndInvert(b)
                }
                Mode.NONE -> Unit
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.DRAW) current?.let { s ->
                    add(s, e.x, e.y)
                    val arr = s.toFloatArray()
                    val r = ImageUtil.bounds(listOf(arr))
                    if (max(r.width(), r.height()) * scale() >= context.dp(4f)) strokes.add(arr)   // 只扔掉极小的误触；小笔画可能是模式记号
                    if (strokes.isNotEmpty()) postDelayed(sendNow, SEND_DELAY_MS)
                }
                current = null; mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> { current = null; mode = Mode.NONE }
        }
        invalidate()
        return true
    }

    /** 视图坐标 → 图片坐标，存进笔迹。 */
    private fun add(s: ArrayList<Float>, x: Float, y: Float) {
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        s.add(p[0]); s.add(p[1])
    }

    /** 不让图片被拖出视图太多。 */
    private fun clampAndInvert(b: Bitmap) {
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        m.mapRect(r)
        val margin = context.dp(48f)
        var dx = 0f; var dy = 0f
        if (r.right < margin) dx = margin - r.right
        if (r.left > width - margin) dx = width - margin - r.left
        if (r.bottom < margin) dy = margin - r.bottom
        if (r.top > height - margin) dy = height - margin - r.top
        if (dx != 0f || dy != 0f) m.postTranslate(dx, dy)
        m.invert(inv)
    }

    private fun dist(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun midX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2f
    private fun midY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f

    companion object {
        const val SEND_DELAY_MS = 1000L   // 停 1 秒什么都不写就是普通模式；接着写 gg / j 是别的模式
    }
}
