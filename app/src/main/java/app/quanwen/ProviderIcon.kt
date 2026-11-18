package app.quanwen

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 厂商的小图标：圆角方块（渐变底色）上一个字或一个简单图形。
 * glyph 是 "*"（放射的星芒）、"✦"（四角星）、"+"（添加），其他就是写在上面的字。
 */
class ProviderIcon(
    private val color: Int,
    private val color2: Int,
    private val glyph: String,
    /** 右上角一个小蓝点（Kimi）。 */
    private val dot: Boolean = false,
) : Drawable() {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()).toFloat()
        if (s <= 0f) return
        val l = b.left + (b.width() - s) / 2f
        val t = b.top + (b.height() - s) / 2f
        rect.set(l, t, l + s, t + s)
        val r = s * 0.28f
        bg.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, color, color2, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, bg)
        // 上半截一层淡淡的高光，让方块不那么平
        c.save()
        c.clipRect(rect.left, rect.top, rect.right, rect.top + s * 0.48f)
        c.drawRoundRect(rect, r, r, shine)
        c.restore()

        val cx = rect.centerX()
        val cy = rect.centerY()
        fg.style = Paint.Style.FILL
        when (glyph) {
            "*" -> {   // 星芒：12 根长短相间的光线
                fg.style = Paint.Style.STROKE
                fg.strokeCap = Paint.Cap.ROUND
                fg.strokeWidth = s * 0.075f
                for (i in 0 until 12) {
                    val a = Math.PI * 2 * i / 12 - Math.PI / 2
                    val len = if (i % 2 == 0) s * 0.30f else s * 0.21f
                    val r0 = s * 0.06f
                    c.drawLine(cx + (cos(a) * r0).toFloat(), cy + (sin(a) * r0).toFloat(),
                        cx + (cos(a) * len).toFloat(), cy + (sin(a) * len).toFloat(), fg)
                }
            }
            "✦" -> {   // 四角星：边往里凹
                val R = s * 0.33f
                path.reset()
                path.moveTo(cx, cy - R)
                path.quadTo(cx + R * 0.12f, cy - R * 0.12f, cx + R, cy)
                path.quadTo(cx + R * 0.12f, cy + R * 0.12f, cx, cy + R)
                path.quadTo(cx - R * 0.12f, cy + R * 0.12f, cx - R, cy)
                path.quadTo(cx - R * 0.12f, cy - R * 0.12f, cx, cy - R)
                path.close()
                c.drawPath(path, fg)
            }
            "+" -> {
                fg.style = Paint.Style.STROKE
                fg.strokeCap = Paint.Cap.ROUND
                fg.strokeWidth = s * 0.09f
                val h = s * 0.2f
                c.drawLine(cx - h, cy, cx + h, cy, fg)
                c.drawLine(cx, cy - h, cx, cy + h, fg)
            }
            else -> {
                val n = glyph.codePointCount(0, glyph.length)
                val cjk = glyph.any { it.code > 0x2E80 }
                fg.textSize = s * when {
                    cjk -> 0.5f
                    n <= 1 -> 0.54f
                    n == 2 -> 0.4f
                    else -> 0.3f
                }
                val fm = fg.fontMetrics
                c.drawText(glyph, cx, cy - (fm.ascent + fm.descent) / 2f, fg)
            }
        }
        if (dot) {
            fg.style = Paint.Style.FILL
            val d = fg.color
            fg.color = 0xFF3D8BFF.toInt()
            c.drawCircle(rect.right - s * 0.25f, rect.top + s * 0.25f, s * 0.085f, fg)
            fg.color = d
        }
    }

    override fun setAlpha(alpha: Int) { bg.alpha = alpha; fg.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fg.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        fun of(p: Provider) = ProviderIcon(p.color, p.color2, p.glyph, dot = p.id == "moonshot")
        fun add() = ProviderIcon(0xFFB8B3A4.toInt(), 0xFF9C9786.toInt(), "+")
    }
}
