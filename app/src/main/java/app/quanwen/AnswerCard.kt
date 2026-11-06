package app.quanwen

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮模式下的回答卡片：把 [AnswerView] 放进一个无障碍浮层窗口，负责摆放、拖动、显示和隐藏。
 * 关掉只是隐藏，点悬浮条上的「卡」再拿出来。
 */
class AnswerCard(private val svc: QuanwenService) {
    val current: Qa? get() = view.current
    var visible = false
        private set
    private var attached = false
    private var visibleBeforeCapture = false

    private val screen get() = svc.screenSize()
    private val cardW: Int get() = screen.x.let { w -> max(svc.dpi(300f), min(svc.dpi(460f), (w * 0.6f).toInt())) }

    private val view = AnswerView(svc, fill = false,
        maxBodyHeight = { (screen.y * 0.5f).toInt() },
        onClose = { hide() },
        onResized = { keepOnScreen() })
    private val params = overlayParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, focusable = false)

    init { attachDrag(view.header) }

    fun applyStrings() = view.applyStrings()

    /** 显示某道题。place=true 时把卡片挪到圈的旁边。 */
    fun show(qa: Qa, place: Boolean) {
        params.width = cardW
        if (place) placeNear(qa.bounds)
        if (!attached) { svc.wm.addView(view, params); attached = true } else svc.wm.updateViewLayout(view, params)
        view.visibility = View.VISIBLE
        visible = true
        view.showQa(qa)
    }

    fun hide() { view.visibility = View.GONE; visible = false }

    fun hideForCapture() { visibleBeforeCapture = visible; if (attached) view.visibility = View.GONE }
    fun restoreAfterCapture() { if (visibleBeforeCapture && attached) { view.visibility = View.VISIBLE; visible = true } }

    fun refresh(qa: Qa) = view.refresh(qa)

    fun remove() {
        if (attached) try { svc.wm.removeView(view) } catch (_: Exception) { }
        attached = false; visible = false
        view.destroy()
    }

    /** 先放圈的右边，再左边，都放不下就放圈的下方或上方；不盖住悬浮条。 */
    private fun placeNear(b: FloatArray) {
        val s = screen
        val gap = svc.dpi(16f)
        val w = cardW
        var minX = gap
        var maxX = s.x - gap
        svc.barRect()?.let { r -> if (r.centerX() > s.x / 2) maxX = min(maxX, r.left - gap) else minX = max(minX, r.right + gap) }
        val estH = svc.dpi(220f)
        val top = svc.dpi(24f)
        when {
            b[2] + gap + w <= maxX -> { params.x = (b[2] + gap).toInt(); params.y = b[1].toInt() }
            b[0] - gap - w >= minX -> { params.x = (b[0] - gap - w).toInt(); params.y = b[1].toInt() }
            else -> {
                params.x = b[0].toInt().coerceIn(minX, max(minX, maxX - w))
                params.y = if (b[3] + gap + estH <= s.y) (b[3] + gap).toInt() else max(top, (b[1] - gap - estH).toInt())
            }
        }
        params.y = params.y.coerceIn(top, max(top, s.y - svc.dpi(200f)))
    }

    /** 内容高度变了以后，保证卡片底部不出屏幕。 */
    private fun keepOnScreen() {
        if (!attached) return
        view.post {
            val s = screen
            if (params.y + view.height > s.y - svc.dpi(8f)) {
                params.y = max(0, s.y - svc.dpi(8f) - view.height)
                svc.wm.updateViewLayout(view, params)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(handle: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    val s = screen
                    params.x = (startX + e.rawX - downX).toInt().coerceIn(-params.width / 2, s.x - params.width / 2)
                    params.y = (startY + e.rawY - downY).toInt().coerceIn(0, s.y - svc.dpi(60f))
                    svc.wm.updateViewLayout(view, params)
                }
            }
            true
        }
    }
}
