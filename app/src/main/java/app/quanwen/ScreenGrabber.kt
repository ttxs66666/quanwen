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
import java.io.File

/**
 * 用系统的录屏接口（MediaProjection）截屏，不需要无障碍。
 * 在圈题页里点「截屏」时系统弹授权；圈题页留在屏幕上期间都能截，离开屏幕一会儿就停。
 *
 * 不是每个系统都完全照标准安卓实现录屏（比如鸿蒙的卓易通），所以有三种取帧方式。
 * 先用最省的，不出画面就自动换下一种，并记住这台设备用哪种：
 *  1. [Mode.DETACH] 一个虚拟屏，平时断开画面（不镜像），截屏时接上，拿到一帧马上断开。
 *     第 9 版只有这一种；卓易通上「后接上」的画面一直不来，截屏失败。
 *  2. [Mode.RECREATE] 每次截屏新建一个创建时就接好画面的虚拟屏，截完释放。只有安卓 13 及以下允许。
 *  3. [Mode.KEEP] 一直接着画面（第 8 版的做法，卓易通上可用），只在圈题页留在屏幕上时开着。
 * 不管哪种，授权后的第一张都从「创建时就接好画面」的虚拟屏取，和第 8 版一样。
 */
object ScreenGrabber {
    enum class Mode { DETACH, RECREATE, KEEP }

    /** 截屏失败的原因。 */
    enum class Fail { NO_FRAME, BLACK, STOPPED, NEED_CONSENT, BUSY }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private var w = 0
    private var h = 0
    private var dpi = 0
    private lateinit var app: Context

    var mode = Mode.DETACH
        private set

    /** 撑着这次录屏的前台服务：圈题页用 [CaptureService]，悬浮窗方式用 [FloatService]。 */
    private var owner: Class<out Service>? = null

    // 最新一帧：只在接着画面时更新（后台线程写，主线程读）
    private val lock = Any()
    private var latest: Image? = null
    private var seq = 0          // 收到的帧数
    private var checkedSeq = 0   // 已经检查过的帧
    @Volatile private var surfaceOn = false

    /** 正在等的那一次截屏。 */
    private var waiting: ((Bitmap?, Fail?) -> Unit)? = null
    private var surfaceSwapped = false   // 这次截屏中途给虚拟屏换过画面（接上或旋转后换接收器）
    private var recovering = false
    private var candidate: Mode? = null  // 换了方式，拿到画面才记下来
    private var blanks = 0
    private var fault = ""               // 测试用的模拟故障，见 [testFault]
    private val timeout = Runnable { onTimeout() }

    /** 授权后服务启动完调一次：参数为 null 表示成功，否则是出错说明。 */
    var onReady: ((String?) -> Unit)? = null

    val active: Boolean get() = projection != null
    val busy: Boolean get() = waiting != null

    /** 给出错提示用的设备信息。 */
    fun describe(): String = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · M${mode.ordinal + 1}"

    fun start(ctx: Context, resultCode: Int, data: Intent, by: Class<out Service>) {
        if (projection != null) { owner = null; stop() }   // 换一个授权：先停掉旧的（不关任何服务）
        app = ctx.applicationContext
        owner = by
        mode = savedMode()
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("getMediaProjection = null")
        projection = p
        thread = HandlerThread("quanwen-capture").also { it.start() }
        bg = Handler(thread!!.looper)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { release() }   // 被系统或用户停止（比如点了通知里的「停止共享」）
        }, main)
        val (sw, sh, d) = screen()
        w = sw; h = sh; dpi = d
        reader = newReader()
        createDisplay()   // 和第 8 版一样：创建时就接上画面，授权后的第一张从这里取
    }

    /** 截一张。回调在主线程：成功给图片，失败给原因。 */
    fun grab(onResult: (Bitmap?, Fail?) -> Unit) {
        if (projection == null) { onResult(null, Fail.STOPPED); return }
        if (waiting != null) { onResult(null, Fail.BUSY); return }
        waiting = onResult
        blanks = 0; surfaceSwapped = false; recovering = false; candidate = null
        fault = testFault()
        synchronized(lock) { if (latest != null) checkedSeq = seq - 1 }   // 一直接着画面时，现成的最新一帧可以直接用
        try {
            val (sw, sh, dd) = screen()
            if (sw != w || sh != h) {   // 旋转过：换一个对应尺寸的接收器
                w = sw; h = sh; dpi = dd
                synchronized(lock) { dropLatest() }
                reader?.close()
                reader = newReader()
                display?.resize(w, h, dpi)
                if (surfaceOn) { display?.surface = reader!!.surface; surfaceSwapped = true }
            }
            when {
                display == null -> createDisplay()   // RECREATE：每次新建
                !surfaceOn -> attach()               // DETACH：接上画面
            }
        } catch (e: Exception) {
            recover(); return
        }
        main.postDelayed(timeout, if (surfaceSwapped) 2000L else 2500L)
        main.post { check() }
    }

    /** 停止录屏。给了 from 时，只有这次录屏是它撑着的才停（圈题页和悬浮窗互不干扰）。 */
    fun stop(from: Class<out Service>? = null) {
        if (from != null && from != owner) return
        val p = projection
        release()
        try { p?.stop() } catch (_: Exception) { }
    }

    // ---------- 取帧 ----------

    private fun createDisplay() {
        val p = projection ?: throw IllegalStateException("no projection")
        synchronized(lock) { dropLatest() }
        surfaceOn = true
        display = try {
            p.createVirtualDisplay("quanwen-capture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
                ?: throw IllegalStateException("createVirtualDisplay = null")
        } catch (e: Exception) { surfaceOn = false; throw e }
    }

    private fun attach() {
        synchronized(lock) { dropLatest() }
        surfaceOn = true
        surfaceSwapped = true
        if (fault == "reattach") return   // 模拟「后接上不出画面」的系统
        display!!.surface = reader!!.surface
    }

    /** 不再镜像：按方式断开画面或释放虚拟屏。 */
    private fun idle() {
        when (mode) {
            Mode.DETACH -> {
                surfaceOn = false
                try { display?.surface = null } catch (_: Exception) { }
            }
            Mode.RECREATE -> {
                surfaceOn = false
                try { display?.release() } catch (_: Exception) { }
                display = null
            }
            Mode.KEEP -> return
        }
        synchronized(lock) { dropLatest() }
    }

    private fun dropLatest() {
        latest?.close(); latest = null
        checkedSeq = seq
    }

    /** 看最新一帧能不能用。在主线程。 */
    private fun check() {
        if (waiting == null) return
        val b = synchronized(lock) {
            val img = latest
            if (img == null || seq == checkedSeq) null
            else { checkedSeq = seq; try { toBitmap(img) } catch (_: Exception) { null } }
        } ?: return
        if (fault == "noframe") { b.recycle(); return }
        if (fault == "black" || looksBlank(b)) { blanks++; b.recycle(); return }   // 刚接上时偶尔是黑帧，等下一帧
        finish(b, null)
    }

    private fun onTimeout() {
        if (waiting == null) return
        when {
            blanks > 0 -> finish(null, Fail.BLACK)
            surfaceSwapped && !recovering -> recover()
            else -> {   // 创建时就接好画面也没有一帧：整个停掉，下次重新授权从头来
                val cb = waiting; waiting = null
                release()
                cb?.invoke(null, Fail.NO_FRAME)
            }
        }
    }

    /**
     * 给虚拟屏换画面以后不出帧（或者换的时候出错）：改成新建一个创建时就接好画面的虚拟屏。
     * 安卓 14 起同一个授权只能建一个虚拟屏，建不了就改成一直接着画面，请用户再授权一次。
     */
    private fun recover() {
        main.removeCallbacks(timeout)
        recovering = true
        surfaceSwapped = false
        surfaceOn = false
        try { display?.release() } catch (_: Exception) { }
        display = null
        try {
            createDisplay()
            if (mode == Mode.DETACH) candidate = Mode.RECREATE
            main.postDelayed(timeout, 2500)
            main.post { check() }
        } catch (e: Exception) {
            saveMode(Mode.KEEP)
            val cb = waiting; waiting = null
            release()
            cb?.invoke(null, Fail.NEED_CONSENT)
        }
    }

    private fun finish(b: Bitmap?, fail: Fail?) {
        main.removeCallbacks(timeout)
        if (b != null) candidate?.let { saveMode(it) }
        candidate = null
        idle()
        val cb = waiting
        waiting = null
        if (cb != null) cb(b, fail) else b?.recycle()
    }

    private fun release() {
        val cb = waiting
        waiting = null
        main.removeCallbacks(timeout)
        surfaceOn = false
        synchronized(lock) { dropLatest() }
        try { display?.release() } catch (_: Exception) { }
        display = null
        reader?.close(); reader = null
        val wasActive = projection != null
        projection = null
        thread?.quitSafely(); thread = null; bg = null
        // 圈题页的录屏服务只为录屏而开，录屏停了就关掉它；悬浮窗服务还要留着悬浮条，下次点「圈」再授权
        if (wasActive && owner == CaptureService::class.java) try { app.stopService(Intent(app, CaptureService::class.java)) } catch (_: Exception) { }
        owner = null
        cb?.invoke(null, Fail.STOPPED)
    }

    private fun newReader(): ImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
        setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            synchronized(lock) {
                if (!surfaceOn || r !== reader) { img.close(); return@setOnImageAvailableListener }
                latest?.close(); latest = img; seq++
            }
            main.post { check() }
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

    private fun savedMode(): Mode = try { Mode.valueOf(Prefs(app).captureMode) } catch (_: Exception) { Mode.DETACH }

    private fun saveMode(m: Mode) { mode = m; Prefs(app).captureMode = m.name }

    /**
     * 测试用：模拟别的系统上的截屏故障。只有用 adb 往 App 自己的外部目录里放
     * capture-test.txt（内容 reattach / black / noframe）才生效，平时这个文件不存在。
     */
    private fun testFault(): String = try {
        File(app.getExternalFilesDir(null), "capture-test.txt").takeIf { it.exists() }?.readText()?.trim() ?: ""
    } catch (_: Exception) { "" }
}

/** 录屏要求有一个前台服务撑着；它只负责启动和停止 [ScreenGrabber]。 */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ScreenGrabber.stop(CaptureService::class.java)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val error = try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
            @Suppress("DEPRECATION")
            val data: Intent = (if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                                else intent?.getParcelableExtra(EXTRA_DATA)) ?: throw IllegalStateException("no consent data")
            ScreenGrabber.start(this, code, data, CaptureService::class.java)
            null
        } catch (e: Exception) {
            ScreenGrabber.stop(CaptureService::class.java)
            stopSelf()
            "${e.javaClass.simpleName}: ${e.message ?: ""}".take(160)
        }
        ScreenGrabber.onReady?.invoke(error)
        ScreenGrabber.onReady = null
        return START_NOT_STICKY
    }

    override fun onDestroy() { ScreenGrabber.stop(CaptureService::class.java); super.onDestroy() }

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
