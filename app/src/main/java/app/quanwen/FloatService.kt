package app.quanwen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager

/**
 * 悬浮窗方式的悬浮条：不用无障碍，只要「显示在其他应用上层」权限（很多手机叫「悬浮窗」）。
 * 浮层交给 [FloatHost]；截屏用录屏接口（[ScreenGrabber]）。第一次点「圈」时系统问一次录屏授权，
 * 之后一直能截，直到录屏被停止（手机重启、在通知里停止共享等），下次点「圈」再问一次。
 * 平时不镜像屏幕，只在点「圈」时取一帧（取帧方式见 [ScreenGrabber]）。
 *
 * 前台服务的类型：平时是 specialUse（一直显示的悬浮工具条）；拿到录屏授权后加上 mediaProjection；
 * 有麦克风权限时加上 microphone，这样在别的 App 上面也能语音追问。
 */
class FloatService : Service(), ScreenSource {

    private var host: FloatHost? = null
    private val main = Handler(Looper.getMainLooper())
    private var types = 0
    private var waitingConsent: ((String?) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs(this).floatMode = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MIC -> { addTypes(TYPE_MIC); return START_NOT_STICKY }   // 刚给了麦克风权限
        }
        if (!Settings.canDrawOverlays(this) || QuanwenService.instance != null) { stopSelf(); return START_NOT_STICKY }
        if (host == null) {
            val ok = addTypes(TYPE_BASE or (if (VoiceRecorder.hasPermission(this)) TYPE_MIC else 0)) || addTypes(TYPE_BASE)
            if (!ok) { stopSelf(); return START_NOT_STICKY }
            host = FloatHost(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, this).also { it.start() }
            instance = this
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        host?.onConfigurationChanged()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        waitingConsent?.invoke(null); waitingConsent = null
        host?.teardown(); host = null
        ScreenGrabber.stop(FloatService::class.java)
        super.onDestroy()
    }

    // ---------- 截屏 ----------

    /** 还没有录屏授权（或者被停止了）就先请求一次；悬浮条这时还在屏幕上。 */
    override fun prepare(ready: (String?) -> Unit) {
        if (ScreenGrabber.active) { ready(null); return }
        waitingConsent = ready
        CaptureConsentActivity.open(this)
    }

    /** [CaptureConsentActivity] 拿到结果后调用。 */
    fun onConsent(resultCode: Int, data: Intent?) {
        val ready = waitingConsent ?: return
        waitingConsent = null
        if (resultCode != Activity.RESULT_OK || data == null) { ready(str(R.string.capture_denied)); return }
        if (!addTypes(TYPE_PROJECTION)) { ready(str(R.string.capture_error, "startForeground")); return }
        try {
            ScreenGrabber.start(this, resultCode, data, FloatService::class.java)
        } catch (e: Exception) {
            ready(str(R.string.capture_error, e.javaClass.simpleName)); return
        }
        main.postDelayed({ ready(null) }, 500)   // 等授权框退掉
    }

    override fun grab(onShot: (Bitmap?, String?) -> Unit) {
        ScreenGrabber.grab { b, fail ->
            val info = ScreenGrabber.describe()
            when {
                b != null -> {
                    // 这台设备只能一直接着画面（方式 3）：悬浮窗方式会一直开着，一直镜像会拖卡系统（第 6～8 版的问题），
                    // 所以截完就停，下次点「圈」再授权一次
                    if (ScreenGrabber.mode == ScreenGrabber.Mode.KEEP) ScreenGrabber.stop(FloatService::class.java)
                    onShot(b, null)
                }
                fail == ScreenGrabber.Fail.BUSY -> onShot(null, null)
                fail == ScreenGrabber.Fail.NEED_CONSENT -> onShot(null, str(R.string.float_reconsent))
                fail == ScreenGrabber.Fail.STOPPED -> onShot(null, str(R.string.float_stopped))
                fail == ScreenGrabber.Fail.BLACK -> onShot(null, str(R.string.capture_black, info))
                else -> onShot(null, str(R.string.float_no_frame, info))
            }
        }
    }

    // ---------- 前台服务 ----------

    /** 加上前台服务类型；失败（比如这时不允许）返回 false，原来的类型不变。 */
    private fun addTypes(add: Int): Boolean {
        val want = types or add
        return try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification(), want)
            else startForeground(NOTIFICATION_ID, notification())   // 安卓 13 及以下用清单里声明的类型
            types = want
            true
        } catch (e: Exception) { false }
    }

    private fun notification(): Notification {
        val loc = Lang.wrap(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, loc.getString(R.string.float_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, FloatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lasso)
            .setContentTitle(loc.getString(R.string.app_name))
            .setContentText(loc.getString(R.string.float_notice))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, loc.getString(R.string.float_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    private fun str(id: Int, vararg args: Any): String = Lang.wrap(this).getString(id, *args)

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_MIC = "mic"
        private const val CHANNEL = "float"
        private const val NOTIFICATION_ID = 2

        private val TYPE_BASE = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        private val TYPE_PROJECTION = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        private val TYPE_MIC = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        @Volatile
        var instance: FloatService? = null
            private set

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, FloatService::class.java))

        fun stop(ctx: Context) { Prefs(ctx).floatMode = false; ctx.stopService(Intent(ctx, FloatService::class.java)) }

        /** 刚给了麦克风权限：让悬浮窗服务带上麦克风类型。 */
        fun micGranted(ctx: Context) {
            if (instance != null) ctx.startService(Intent(ctx, FloatService::class.java).setAction(ACTION_MIC))
        }
    }
}

/** 透明页面，只用来请求录屏授权（悬浮窗服务没法直接弹授权框）。 */
class CaptureConsentActivity : Activity() {
    private var delivered = false

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return   // 旋转后重建：授权框还开着，等结果
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val i = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else mpm.createScreenCaptureIntent()
        try { startActivityForResult(i, 1) } catch (e: Exception) { deliver(RESULT_CANCELED, null) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        deliver(resultCode, data)
    }

    override fun onDestroy() {
        if (!delivered && !isChangingConfigurations) { delivered = true; FloatService.instance?.onConsent(RESULT_CANCELED, null) }
        super.onDestroy()
    }

    private fun deliver(code: Int, data: Intent?) {
        if (delivered) return
        delivered = true
        finish()
        overridePendingTransition(0, 0)
        FloatService.instance?.onConsent(code, data)
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, CaptureConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }
}
