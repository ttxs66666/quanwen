package app.quanwen

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * 圈选层：把截图原样铺满屏幕，用笔圈出问题。
 * 抬笔后停 [SEND_DELAY_MS] 就发送；这段时间里接着画，会并进同一个圈。
 */
class LassoOverlay(
    private val svc: FloatHost,
    private val shot: Bitmap,
    private val onDone: (List<FloatArray>) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val prefs = Prefs(svc)
    private val screen = svc.screenSize()
    /** 屏幕像素 / 截图像素，正常是 1。 */
    private val scale = screen.x.toFloat() / shot.width

    private val strokes = mutableListOf<FloatArray>()
    private var current: ArrayList<Float>? = null
    private var finished = false
    private val sendNow = Runnable { finish() }

    private val root = object : FrameLayout(svc) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) cancel()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
    private val canvasView = LassoView()
    private val hint = TextView(svc)

    init {
        root.addView(canvasView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xE6222230.toInt(), svc.dp(20f))
            setPadding(svc.dpi(16f), svc.dpi(4f), svc.dpi(6f), svc.dpi(4f))
        }
        hint.apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
            text = svc.str(R.string.lasso_hint)
        }
        bar.addView(hint)
        bar.addView(pill(svc.str(R.string.lasso_redo)) { redo() })
        bar.addView(pill(svc.str(R.string.lasso_cancel)) { cancel() })
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = svc.dpi(36f) })
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(svc).apply {
        text = label; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
        setPadding(svc.dpi(14f), svc.dpi(9f), svc.dpi(14f), svc.dpi(9f))
        background = rounded(0x33FFFFFF, svc.dp(16f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = svc.dpi(8f) }
    }

    fun show() {
        val p = overlayParams(screen.x, screen.y, focusable = true, type = svc.windowType)
        p.x = 0; p.y = 0
        svc.wm.addView(root, p)
        root.isFocusableInTouchMode = true
        root.requestFocus()
    }

    fun dismiss() {
        root.removeCallbacks(sendNow)
        try { svc.wm.removeView(root) } catch (_: Exception) { }
    }

    private fun redo() {
        root.removeCallbacks(sendNow)
        strokes.clear(); current = null
        hint.text = svc.str(R.string.lasso_hint)
        canvasView.invalidate()
    }

    fun dismissAsCancel() = cancel()

    private fun cancel() {
        if (finished) return
        finished = true
        dismiss()
        onCancel()
    }

    private fun finish() {
        if (finished || strokes.isEmpty()) return
        finished = true
        dismiss()
        onDone(strokes.toList())
    }

    @SuppressLint("ViewConstructor")
    private inner class LassoView : View(svc) {
        private val loc = IntArray(2)
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE53935.toInt(); style = Paint.Style.STROKE
            strokeWidth = svc.dp(3.5f) / scale
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val border = Paint().apply {
            color = 0xCCD2412F.toInt(); style = Paint.Style.STROKE; strokeWidth = svc.dp(5f)
        }

        override fun onDraw(c: Canvas) {
            getLocationOnScreen(loc)
            c.save()
            c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            c.scale(scale, scale)
            c.drawBitmap(shot, 0f, 0f, bmpPaint)
            val all = ArrayList(strokes)
            current?.let { all.add(it.toFloatArray()) }
            c.drawPath(ImageUtil.strokesPath(all), ink)
            c.restore()
            val h = border.strokeWidth / 2
            c.drawRect(h, h, width - h, height - h, border)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val tool = e.getToolType(0)
            val pen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
            if (pen && !prefs.stylusSeen) prefs.stylusSeen = true
            if (!pen && prefs.stylusSeen) return true          // 用过笔以后忽略手指，防手掌误触
            if (e.pointerCount > 1) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    removeCallbacks(sendNow)
                    getLocationOnScreen(loc)
                    current = arrayListOf<Float>().also { add(it, e.x, e.y) }
                }
                MotionEvent.ACTION_MOVE -> current?.let { s ->
                    for (i in 0 until e.historySize) add(s, e.getHistoricalX(0, i), e.getHistoricalY(0, i))
                    add(s, e.x, e.y)
                }
                MotionEvent.ACTION_UP -> {
                    current?.let { s ->
                        add(s, e.x, e.y)
                        val arr = s.toFloatArray()
                        val b = ImageUtil.bounds(listOf(arr))
                        if (max(b.width(), b.height()) * scale >= svc.dp(4f)) strokes.add(arr)  // 只扔掉极小的误触；小笔画可能是模式记号
                    }
                    current = null
                    if (strokes.isNotEmpty()) {
                        hint.text = svc.str(R.string.lasso_sending)
                        postDelayed(sendNow, SEND_DELAY_MS)
                    }
                }
                MotionEvent.ACTION_CANCEL -> current = null
            }
            invalidate()
            return true
        }

        /** 视图坐标 → 截图像素坐标。 */
        private fun add(s: ArrayList<Float>, x: Float, y: Float) {
            s.add((x + loc[0]) / scale); s.add((y + loc[1]) / scale)
        }
    }

    companion object {
        const val SEND_DELAY_MS = 1000L   // 停 1 秒什么都不写就是普通模式；接着写 gg / j 是别的模式
    }
}
