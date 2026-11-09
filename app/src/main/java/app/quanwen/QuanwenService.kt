package app.quanwen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍方式的悬浮条：浮层交给 [FloatHost]，截屏用无障碍服务自带的 takeScreenshot（Android 11+）。
 * 在系统设置里开一次就行；有些手机会在 App 被清理后把它关掉，那种手机可以改用悬浮窗方式（[FloatService]）。
 */
class QuanwenService : AccessibilityService(), ScreenSource {

    private var host: FloatHost? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        stopService(Intent(this, FloatService::class.java))   // 无障碍优先，免得屏幕上出现两个悬浮条
        host = FloatHost(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, this).also { it.start() }
        instance = this
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        host?.onConfigurationChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean { teardown(); return super.onUnbind(intent) }
    override fun onDestroy() { teardown(); super.onDestroy() }

    private fun teardown() {
        if (instance === this) instance = null
        host?.teardown(); host = null
    }

    // ---------- 截屏 ----------

    override fun prepare(ready: (String?) -> Unit) = ready(null)

    override fun grab(onShot: (Bitmap?, String?) -> Unit) {
        val fail = { code: Int -> onShot(null, Lang.wrap(this).getString(R.string.capture_failed) + " ($code)") }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bmp = try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    sw
                } catch (e: Exception) { null } finally { result.hardwareBuffer.close() }
                if (bmp == null) fail(-1) else onShot(fixOrientation(bmp), null)
            }

            override fun onFailure(errorCode: Int) = fail(errorCode)
        })
    }

    /** 有些设备给的截图是自然方向的，横屏时要转过来。 */
    private fun fixOrientation(b: Bitmap): Bitmap {
        val s = host?.screenSize() ?: return b
        if ((s.x > s.y) == (b.width > b.height)) return b
        val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val deg = if (display.rotation == Surface.ROTATION_270) 90f else -90f
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        b.recycle()
        return r
    }

    companion object {
        @Volatile
        var instance: QuanwenService? = null
            private set
    }
}
