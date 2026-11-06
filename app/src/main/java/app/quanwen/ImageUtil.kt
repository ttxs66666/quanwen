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
    fun prepare(screen: Bitmap, strokes: List<FloatArray>): Pair<ByteArray, ByteArray> {
        val page = screen.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE53935.toInt(); style = Paint.Style.STROKE
            strokeWidth = max(4f, screen.width / 320f)
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

    const val PAGE_MAX = 1600
    const val CROP_MAX = 1600
}
