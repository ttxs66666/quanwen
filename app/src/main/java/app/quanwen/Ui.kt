package app.quanwen

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

object Colors {
    const val INK = 0xFF2B2B3A.toInt()
    const val MUTED = 0xFF8A8778.toInt()
    const val PAPER = 0xFFFFFDF8.toInt()
    const val LINE = 0x22000000
    const val ACCENT = 0xFFD2412F.toInt()

    /** 档位标签的颜色。 */
    fun forType(type: String): Int = when {
        type.contains("判断") || type.contains("Check", true) -> 0xFF2E7D4F.toInt()
        type.contains("推") || type.contains("Nudge", true) -> 0xFFB7791F.toInt()
        type.contains("揭晓") || type.contains("Reveal", true) -> 0xFF6B4FA0.toInt()
        type.isBlank() -> MUTED
        else -> 0xFF2F5D9E.toInt()
    }
}

/** 标题栏是普通文字，不渲染公式：把常见的 LaTeX 写法换成普通字符。 */
fun plainMath(s: String): String {
    var t = s
    val map = mapOf(
        "times" to "×", "cdot" to "·", "le" to "≤", "ge" to "≥", "leq" to "≤", "geq" to "≥",
        "neq" to "≠", "ne" to "≠", "infty" to "∞", "in" to "∈", "sum" to "Σ", "to" to "→",
        "lambda" to "λ", "alpha" to "α", "beta" to "β", "sigma" to "σ", "pi" to "π", "top" to "T",
        "left" to "", "right" to "",
    )
    for ((k, v) in map) t = t.replace(Regex("\\\\$k(?![A-Za-z])"), v)
    t = t.replace(Regex("\\\\(?:mathrm|mathbf|mathsf|text|operatorname)\\{([^}]*)\\}"), "$1")
    t = t.replace(Regex("\\\\[,;! ]"), " ").replace("$", "").replace("{", "").replace("}", "")
    return t.replace(Regex("\\\\([A-Za-z]+)"), "$1").replace(Regex(" {2,}"), " ").trim()
}

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
    cornerRadius = radius
    setColor(color)
    if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
}

/** 提示一句话：在悬浮条的浮层里用气泡（[FloatHost.toast]），在普通页面里用 Toast。 */
fun Context.say(msg: String, long: Boolean = false) {
    val host = this as? FloatHost
    if (host != null) host.toast(msg, long)
    else android.widget.Toast.makeText(this, msg, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * 浮层的窗口参数。type 是 TYPE_ACCESSIBILITY_OVERLAY（无障碍方式）或 TYPE_APPLICATION_OVERLAY（悬浮窗方式）。
 * focusable=false 时不抢输入焦点，点到窗口外的触摸照常传给下面的 App。
 */
fun overlayParams(width: Int, height: Int, focusable: Boolean, type: Int): WindowManager.LayoutParams {
    var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    return WindowManager.LayoutParams(
        width, height,
        type,
        flags, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }
}
