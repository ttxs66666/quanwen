package app.quanwen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display

/**
 * 用系统的录屏接口（MediaProjection）截屏，不需要无障碍。
 * 在圈题页里第一次点「截屏」时系统弹一次授权；之后这个页面开着就一直能截，离开页面就停。
 * 一直保留屏幕最新的一帧，点「截屏」时把它转成图片。
 */
object ScreenGrabber {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private val lock = Any()
    private var latest: Image? = null
    private var w = 0
    private var h = 0
    private var dpi = 0
    private lateinit var app: Context

    /** 服务启动好以后调用一次（只调一次）。 */
    var onReady: (() -> Unit)? = null

    val active: Boolean get() = projection != null

    fun start(ctx: Context, resultCode: Int, data: Intent): Boolean {
        app = ctx.applicationContext
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(resultCode, data) ?: return false
        projection = p
        thread = HandlerThread("quanwen-capture").also { it.start() }
        bg = Handler(thread!!.looper)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { release() }
        }, Handler(Looper.getMainLooper()))
        val (sw, sh, d) = screen()
        w = sw; h = sh; dpi = d
        reader = newReader()
        display = p.createVirtualDisplay("quanwen-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        return true
    }

    /** 截一张。屏幕尺寸变了（旋转）会先调整，再等新的一帧。回调在主线程。 */
    fun grab(onResult: (Bitmap?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val (sw, sh, d) = screen()
        var delay = 0L
        if (sw != w || sh != h) {
            w = sw; h = sh; dpi = d
            val old = reader
            reader = newReader()
            display?.resize(w, h, dpi)
            display?.surface = reader!!.surface
            synchronized(lock) { latest?.close(); latest = null }
            old?.close()
            delay = 400
        }
        main.postDelayed({
            val b = try { toBitmap() } catch (_: Exception) { null }
            onResult(b)
        }, delay)
    }

    fun stop() {
        projection?.stop()   // 会触发 onStop → release()
        release()
    }

    private fun release() {
        synchronized(lock) { latest?.close(); latest = null }
        display?.release(); display = null
        reader?.close(); reader = null
        projection = null
        thread?.quitSafely(); thread = null; bg = null
    }

    private fun newReader(): ImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
        setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            synchronized(lock) { latest?.close(); latest = img }
        }, bg)
    }

    private fun toBitmap(): Bitmap? = synchronized(lock) {
        val img = latest ?: return null
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        full.copyPixelsFromBuffer(plane.buffer)
        if (full.width == img.width) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun screen(): Triple<Int, Int, Int> {
        val d = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point(); d.getRealSize(p)
        val m = DisplayMetrics(); d.getRealMetrics(m)
        return Triple(p.x, p.y, m.densityDpi)
    }
}

/** 录屏要求有一个前台服务撑着；它只负责启动和停止 [ScreenGrabber]。 */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ScreenGrabber.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                            else intent?.getParcelableExtra(EXTRA_DATA)
        val ok = data != null && try { ScreenGrabber.start(this, code, data) } catch (_: Exception) { false }
        if (!ok) { stopSelf(); ScreenGrabber.onReady = null; return START_NOT_STICKY }
        ScreenGrabber.onReady?.invoke(); ScreenGrabber.onReady = null
        return START_NOT_STICKY
    }

    override fun onDestroy() { ScreenGrabber.stop(); super.onDestroy() }

    private fun notification(): Notification {
        val loc = Lang.wrap(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, loc.getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lasso)
            .setContentTitle(loc.getString(R.string.app_name))
            .setContentText(loc.getString(R.string.capture_notice))
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "capture"
    }
}
