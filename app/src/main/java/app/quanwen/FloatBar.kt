package app.quanwen

import android.annotation.SuppressLint
import android.graphics.Rect
import kotlin.math.max
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import kotlin.math.abs

/** 竖着的悬浮条：上面「圈」，下面「卡」。可拖动，长按打开 App。 */
class FloatBar(private val svc: FloatHost) {
    private val prefs = Prefs(svc)
    private val btn = svc.dpi(54f)
    private val root = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(0xF7FFFFFF.toInt(), svc.dp(27f), 0x33000000, svc.dpi(1f))
        setPadding(0, svc.dpi(3f), 0, svc.dpi(3f))
    }
    private val circleBtn = ImageView(svc).apply {
        setImageResource(R.drawable.ic_lasso)
        scaleType = ImageView.ScaleType.CENTER
    }
    private val cardBtn = FrameLayout(svc)
    private val dot = View(svc).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Colors.ACCENT) }
        visibility = View.GONE
    }
    private val spinner = ProgressBar(svc).apply { isIndeterminate = true; visibility = View.GONE }
    private val params = overlayParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, focusable = false, type = svc.windowType)
    private var attached = false

    init {
        root.addView(circleBtn, LinearLayout.LayoutParams(btn, btn))
        root.addView(View(svc).apply { setBackgroundColor(0x22000000) },
            LinearLayout.LayoutParams(svc.dpi(28f), svc.dpi(1f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        cardBtn.addView(ImageView(svc).apply {
            setImageResource(R.drawable.ic_card); scaleType = ImageView.ScaleType.CENTER
        }, FrameLayout.LayoutParams(btn, btn))
        cardBtn.addView(spinner, FrameLayout.LayoutParams(svc.dpi(18f), svc.dpi(18f), Gravity.TOP or Gravity.END)
            .apply { setMargins(0, svc.dpi(5f), svc.dpi(5f), 0) })
        cardBtn.addView(dot, FrameLayout.LayoutParams(svc.dpi(10f), svc.dpi(10f), Gravity.TOP or Gravity.END)
            .apply { setMargins(0, svc.dpi(9f), svc.dpi(9f), 0) })
        root.addView(cardBtn, LinearLayout.LayoutParams(btn, btn))

        attachTouch(circleBtn) { svc.onCircleTap() }
        attachTouch(cardBtn) { svc.onCardTap() }
    }

    fun show() {
        if (!attached) {
            applySavedPosition()
            svc.wm.addView(root, params)
            attached = true
        }
        root.visibility = View.VISIBLE
    }

    fun hide() { root.visibility = View.GONE }

    /** 悬浮条在屏幕上占的区域，卡片摆放时避开它。 */
    fun rect(): Rect? = if (!attached) null else Rect(params.x, params.y, params.x + btn, params.y + max(root.height, btn * 2))

    /** 屏幕旋转后按相对位置重新摆放（原来贴右边的，转完还贴右边）。 */
    fun reclamp() { if (attached) { applySavedPosition(); svc.wm.updateViewLayout(root, params) } }

    // 位置按比例存：x、y 都是 0..1，表示在可移动范围里的位置。
    private val rangeX get() = maxOf(1, svc.screenSize().x - btn)
    private val rangeY get() = maxOf(1, svc.screenSize().y - btn * 2 - svc.dpi(8f))

    private fun applySavedPosition() {
        params.x = (prefs.barFx * rangeX).toInt()
        params.y = (prefs.barFy * rangeY).toInt()
        clamp()
    }

    private fun savePosition() {
        prefs.barFx = params.x.toFloat() / rangeX
        prefs.barFy = params.y.toFloat() / rangeY
    }

    fun remove() {
        if (attached) try { svc.wm.removeView(root) } catch (_: Exception) { }
        attached = false
    }

    fun setBusy(busy: Boolean) { spinner.visibility = if (busy) View.VISIBLE else View.GONE; if (busy) dot.visibility = View.GONE }
    fun setDot(on: Boolean) { dot.visibility = if (on && spinner.visibility != View.VISIBLE) View.VISIBLE else View.GONE }

    private fun clamp() {
        val s = svc.screenSize()
        params.x = params.x.coerceIn(0, maxOf(0, s.x - btn))
        params.y = params.y.coerceIn(0, maxOf(0, s.y - btn * 2 - svc.dpi(8f)))
    }

    /** 点一下执行动作；拖动挪位置；长按打开 App。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(v: View, onClick: () -> Unit) {
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var longFired = false
        val longPress = Runnable { if (!dragging) { longFired = true; v.alpha = 1f; svc.openApp() } }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    dragging = false; longFired = false; v.alpha = 0.55f
                    v.postDelayed(longPress, 650)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; v.removeCallbacks(longPress) }
                    if (dragging) {
                        params.x = (startX + dx).toInt(); params.y = (startY + dy).toInt(); clamp()
                        svc.wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress); v.alpha = 1f
                    if (dragging) savePosition()
                    else if (!longFired) onClick()
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); v.alpha = 1f }
            }
            true
        }
    }
}
