package app.quanwen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtil {

    /** 笔迹：一笔是一串 x,y 交替的坐标，单位是截图像素。 */
    fun strokesPath(strokes: List<FloatArray>): Path {
        val p = Path()
        for (s in strokes) {
            if (s.size < 2) continue
            p.moveTo(s[0], s[1])
            var i = 2
            while (i + 1 < s.size) { p.lineTo(s[i], s[i + 1]); i += 2 }
        }
        return p
    }

    fun bounds(strokes: List<FloatArray>): RectF {
        val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (s in strokes) {
            var i = 0
            while (i + 1 < s.size) { r.union(s[i], s[i + 1]); i += 2 }
        }
        return r
    }

    /**
     * 生成发给模型的两张图：
     * 整页（画上红圈，缩到长边 [PAGE_MAX]），圈内区域（不带笔迹的原图裁剪，长边最多 [CROP_MAX]）。
     */
    fun prepare(screen: Bitmap, strokes: List<FloatArray>, marker: Int = MARK_RED): Pair<ByteArray, ByteArray> {
        val page = screen.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            if (marker == MARK_HIGHLIGHT) {   // 笔记本：橙色荧光圈，免得和他自己的红笔混在一起
                color = 0x99FF8F00.toInt(); strokeWidth = max(10f, screen.width / 110f)
            } else {
                color = 0xFFE53935.toInt(); strokeWidth = max(4f, screen.width / 320f)
            }
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        Canvas(page).drawPath(strokesPath(strokes), paint)
        val pageJpeg = jpeg(scaleToMax(page, PAGE_MAX))
        page.recycle()

        val b = bounds(strokes)
        val pad = max(24f, screen.width * 0.02f)
        val r = Rect(
            (b.left - pad).roundToInt().coerceIn(0, screen.width - 1),
            (b.top - pad).roundToInt().coerceIn(0, screen.height - 1),
            (b.right + pad).roundToInt().coerceIn(1, screen.width),
            (b.bottom + pad).roundToInt().coerceIn(1, screen.height),
        )
        val crop = Bitmap.createBitmap(screen, r.left, r.top, max(1, r.width()), max(1, r.height()))
        val cropJpeg = jpeg(scaleToMax(crop, CROP_MAX))
        return pageJpeg to cropJpeg
    }

    fun scaleToMax(b: Bitmap, maxEdge: Int): Bitmap {
        val edge = max(b.width, b.height)
        if (edge <= maxEdge) return b
        val s = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt(), (b.height * s).roundToInt(), true)
    }

    fun jpeg(b: Bitmap, quality: Int = 85): ByteArray {
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /**
     * 把圈和圈完写的模式记号（gg、j）分开：第一笔是圈；之后的笔画比圈小得多（最长边不到第一笔的 40%）
     * 就算记号，否则算补圈（有人分两笔画圈）。
     */
    fun splitMarks(strokes: List<FloatArray>): Pair<List<FloatArray>, List<FloatArray>> {
        if (strokes.size < 2) return strokes to emptyList()
        val first = bounds(listOf(strokes[0]))
        val size0 = max(first.width(), first.height())
        val circle = mutableListOf(strokes[0])
        val marks = mutableListOf<FloatArray>()
        for (s in strokes.drop(1)) {
            val b = bounds(listOf(s))
            if (max(b.width(), b.height()) < size0 * 0.4f) marks.add(s) else circle.add(s)
        }
        return circle to marks
    }

    /** 把记号单独画成一张白底黑字的小图（最长边约 [MARK_MAX]），发给模型认。 */
    fun renderMarks(marks: List<FloatArray>): Bitmap {
        val b = bounds(marks)
        val side = max(1f, max(b.width(), b.height()))
        val k = MARK_MAX * 0.8f / side
        val pad = MARK_MAX * 0.1f
        val bmp = Bitmap.createBitmap((b.width() * k + 2 * pad).roundToInt().coerceAtLeast(16),
            (b.height() * k + 2 * pad).roundToInt().coerceAtLeast(16), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFFFFFFF.toInt())
        c.translate(pad - b.left * k, pad - b.top * k)
        c.scale(k, k)
        c.drawPath(strokesPath(marks), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt(); style = Paint.Style.STROKE
            strokeWidth = MARK_MAX / 45f / k; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        })
        return bmp
    }

    const val MARK_MAX = 360
    const val MARK_RED = 0
    const val MARK_HIGHLIGHT = 1
    const val PAGE_MAX = 1600
    const val CROP_MAX = 1600
}
