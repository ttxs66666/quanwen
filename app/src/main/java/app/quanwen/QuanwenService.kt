package app.quanwen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * 整个 App 的核心：一个无障碍服务。
 * 它负责悬浮条、截屏、圈选层和回答卡片；提问和追问交给 [Engine]。
 * 截屏用无障碍服务自带的 takeScreenshot（Android 11+），在系统设置里开一次就行。
 */
class QuanwenService : AccessibilityService() {

    lateinit var wm: WindowManager
        private set
    private val main = Handler(Looper.getMainLooper())

    private var bar: FloatBar? = null
    private var card: AnswerCard? = null
    private var lasso: LassoOverlay? = null
    private var capturing = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Engine.init(this)
        Engine.addListener(onQaChanged)
        bar = FloatBar(this).also { it.show() }
        card = AnswerCard(this)
        instance = this
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({
            bar?.reclamp()
            lasso?.let { l -> lasso = null; l.dismissAsCancel() }   // 截图方向已经不对，取消这次圈选
        }, 300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean { teardown(); return super.onUnbind(intent) }
    override fun onDestroy() { teardown(); super.onDestroy() }

    private fun teardown() {
        if (instance === this) instance = null
        Engine.removeListener(onQaChanged)
        lasso?.dismiss(); lasso = null
        bar?.remove(); bar = null
        card?.remove(); card = null
    }

    // ---------- 小工具 ----------

    fun str(id: Int): String = Lang.wrap(this).getString(id)

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private val mainDisplay: Display
        get() = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)

    /** 当前方向下的整屏像素尺寸（含状态栏、导航栏）。 */
    @Suppress("DEPRECATION")
    fun screenSize(): Point = Point().also { mainDisplay.getRealSize(it) }

    fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun barRect() = bar?.rect()

    /** 设置页改了语言之后调用。 */
    fun refreshLabels() = card?.applyStrings()

    /** 历史记录里删了题之后调用：卡片正显示被删的题就收起来。 */
    fun onQaDeleted() { card?.let { c -> if (c.current != null && Engine.store.list.none { q -> q.id == c.current?.id }) c.hide() } }

    // ---------- 悬浮条按钮 ----------

    fun onCircleTap() {
        if (capturing || lasso != null) return
        capturing = true
        bar?.hide(); card?.hideForCapture()
        main.postDelayed({ capture() }, 220)   // 等一两帧，确保悬浮条和卡片已经从屏幕上消失
    }

    fun onCardTap() {
        val c = card ?: return
        if (c.visible) { c.hide(); return }
        val qa = c.current ?: Engine.store.list.lastOrNull()
        if (qa == null) { toast(str(R.string.no_answer_yet)); return }
        c.show(qa, place = c.current == null)
        bar?.setDot(false)
    }

    // ---------- 截屏和圈选 ----------

    private fun capture() {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bmp = try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    sw
                } catch (e: Exception) { null } finally { result.hardwareBuffer.close() }
                capturing = false
                if (bmp == null) { captureFailed(-1); return }
                openLasso(fixOrientation(bmp))
            }

            override fun onFailure(errorCode: Int) { capturing = false; captureFailed(errorCode) }
        })
    }

    private fun captureFailed(code: Int) {
        toast(str(R.string.capture_failed) + " ($code)")
        bar?.show(); card?.restoreAfterCapture()
    }

    /** 有些设备给的截图是自然方向的，横屏时要转过来。 */
    private fun fixOrientation(b: Bitmap): Bitmap {
        val s = screenSize()
        if ((s.x > s.y) == (b.width > b.height)) return b
        val deg = if (mainDisplay.rotation == Surface.ROTATION_270) 90f else -90f
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        b.recycle()
        return r
    }

    private fun openLasso(shot: Bitmap) {
        lasso = LassoOverlay(this, shot,
            onDone = { strokes -> lasso = null; bar?.show(); ask(shot, strokes) },
            onCancel = { lasso = null; bar?.show(); card?.restoreAfterCapture(); shot.recycle() },
        ).also { it.show() }
    }

    // ---------- 提问（交给 Engine） ----------

    private fun ask(shot: Bitmap, strokes: List<FloatArray>) {
        val scale = screenSize().x.toFloat() / shot.width
        val b = ImageUtil.bounds(strokes)
        val bounds = floatArrayOf(b.left * scale, b.top * scale, b.right * scale, b.bottom * scale)
        val qa = Engine.ask(shot, strokes, bounds, recycleWhenDone = true)
        card?.show(qa, place = true)
        updateBar()
    }

    /** Engine 里任何一道题有变化时调用。 */
    private val onQaChanged: (Qa, Boolean) -> Unit = { qa, finished ->
        card?.refresh(qa)
        updateBar()
        if (finished) {
            val c = card
            if (c == null || !c.visible || c.current !== qa) bar?.setDot(true)
        }
    }

    private fun updateBar() = bar?.setBusy(Engine.anyBusy)

    companion object {
        @Volatile
        var instance: QuanwenService? = null
            private set
    }
}
