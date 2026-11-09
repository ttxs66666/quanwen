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
 * 在圈题页里第一次点「截屏」时系统弹一次授权；圈题页留在屏幕上期间都能截，离开屏幕就停。
 *
 * 按需取帧：平时虚拟屏幕不接画面（surface 为空），系统不做任何镜像；
 * 点「截屏」时才接上，拿到一帧马上断开。第 6~8 版是一直镜像，切后台也不停，会把系统拖卡。
 */
object ScreenGrabber {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private var w = 0
    private var h = 0
    private var dpi = 0
    private lateinit var app: Context

    /** 正在等的那一次截屏。 */
    @Volatile private var waiting: ((Bitmap?) -> Unit)? = null
    private val timeout = Runnable { finishGrab(null) }

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
        }, main)
        val (sw, sh, d) = screen()
        w = sw; h = sh; dpi = d
        reader = newReader()
        // 先不接画面：surface 为 null，系统不镜像
        display = p.createVirtualDisplay("quanwen-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null)
        return true
    }

    /** 截一张：接上画面，等第一帧，转成图片后马上断开。回调在主线程，失败给 null。 */
    fun grab(onResult: (Bitmap?) -> Unit) {
        val d = display
        if (d == null || waiting != null) { onResult(null); return }
        val (sw, sh, dd) = screen()
        if (sw != w || sh != h || reader == null) {   // 旋转过：换一个对应尺寸的接收器
            w = sw; h = sh; dpi = dd
            reader?.close()
            reader = newReader()
            d.resize(w, h, dpi)
        }
        waiting = onResult
        main.postDelayed(timeout, 2500)
        d.surface = reader!!.surface
    }

    fun stop() {
        projection?.stop()   // 会触发 onStop → release()
        release()
    }

    private fun finishGrab(b: Bitmap?) {
        main.removeCallbacks(timeout)
        try { display?.surface = null } catch (_: Exception) { }   // 断开，不再镜像
        val cb = waiting
        waiting = null
        cb?.invoke(b)
    }

    private fun release() {
        val cb = waiting
        waiting = null
        main.removeCallbacks(timeout)
        display?.release(); display = null
        reader?.close(); reader = null
        projection = null
        thread?.quitSafely(); thread = null; bg = null
        cb?.invoke(null)
    }

    private fun newReader(): ImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
        setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            if (waiting == null) { img.close(); return@setOnImageAvailableListener }
            val b = try { toBitmap(img) } catch (_: Exception) { null } finally { img.close() }
            if (b != null && looksBlank(b)) { b.recycle(); return@setOnImageAvailableListener }   // 刚接上时偶尔是黑帧，等下一帧
            main.post { if (waiting != null) finishGrab(b) else b?.recycle() }
        }, bg)
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        full.copyPixelsFromBuffer(plane.buffer)
        return if (full.width == img.width) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
    }

    /** 整张几乎全黑（抽样判断）。 */
    private fun looksBlank(b: Bitmap): Boolean {
        for (i in 1..9) for (j in 1..9) {
            val c = b.getPixel(b.width * i / 10, b.height * j / 10)
            if ((c and 0xFFFFFF) != 0) return false
        }
        return true
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
