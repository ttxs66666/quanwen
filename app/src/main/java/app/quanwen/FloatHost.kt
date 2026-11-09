package app.quanwen

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/** 悬浮条从哪里拿整屏截图。 */
interface ScreenSource {
    /**
     * 截屏前的准备（比如请求录屏授权），这时悬浮条还在屏幕上。
     * ready(null) 表示可以截了，否则给要提示的话。在主线程回调。
     */
    fun prepare(ready: (String?) -> Unit)

    /** 截一张整屏（悬浮条和卡片已经隐藏）。成功给图片；失败给 null 和要提示的话（null 表示不用提示）。在主线程回调。 */
    fun grab(onShot: (Bitmap?, String?) -> Unit)
}

/**
 * 悬浮条、圈选层、回答卡片这一套浮层的总管；提问和追问交给 [Engine]。可以挂在两种宿主上：
 *  - 无障碍服务 [QuanwenService]：窗口类型 TYPE_ACCESSIBILITY_OVERLAY，用无障碍的 takeScreenshot 截屏。
 *  - 悬浮窗服务 [FloatService]：只要「显示在其他应用上层」权限，窗口类型 TYPE_APPLICATION_OVERLAY，
 *    用录屏接口截屏（第一次点「圈」时系统问一次录屏授权）。很多 ROM 会把无障碍关掉，这种方式不受影响。
 * 同一时间只开一个宿主。
 */
class FloatHost(base: Context, val windowType: Int, private val source: ScreenSource) : ContextWrapper(base) {

    // 无障碍服务的 WindowManager 带着服务自己的窗口令牌，必须从宿主本身取
    val wm: WindowManager = base.getSystemService(WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var bar: FloatBar? = null
    private var card: AnswerCard? = null
    private var lasso: LassoOverlay? = null
    private var capturing = false

    fun start() {
        Engine.init(this)
        Engine.addListener(onQaChanged)
        bar = FloatBar(this).also { it.show() }
        card = AnswerCard(this)
        current = this
    }

    fun teardown() {
        if (current === this) current = null
        main.removeCallbacks(hideTip); hideTip.run()
        Engine.removeListener(onQaChanged)
        lasso?.dismiss(); lasso = null
        bar?.remove(); bar = null
        card?.remove(); card = null
    }

    /** 宿主收到屏幕旋转等配置变化时调用。 */
    fun onConfigurationChanged() {
        main.postDelayed({
            bar?.reclamp()
            lasso?.let { l -> lasso = null; l.dismissAsCancel() }   // 截图方向已经不对，取消这次圈选
        }, 300)
    }

    // ---------- 小工具 ----------

    fun str(id: Int): String = Lang.wrap(this).getString(id)
    fun str(id: Int, vararg args: Any): String = Lang.wrap(this).getString(id, *args)

    private var tip: TextView? = null
    private val hideTip = Runnable { tip?.let { v -> try { wm.removeView(v) } catch (_: Exception) { } }; tip = null }

    /**
     * 浮层上的提示气泡。不用系统 Toast：安卓 13 起，App 没有通知权限时，后台服务发的 Toast 会被系统吞掉，
     * 悬浮窗方式下就什么提示都看不到。
     */
    fun toast(msg: String, long: Boolean = false) {
        main.removeCallbacks(hideTip); hideTip.run()
        val v = TextView(this).apply {
            text = msg; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xF0222230.toInt(), dp(12f))
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
            maxWidth = (screenSize().x * 0.7f).toInt()
        }
        val p = overlayParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, focusable = false, type = windowType).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpi(96f)
            alpha = 0.8f   // 触摸要穿过别的 App 的窗口时，不透明度超过 0.8 系统会拦下触摸
        }
        try { wm.addView(v, p); tip = v } catch (_: Exception) {
            Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show(); return
        }
        main.postDelayed(hideTip, if (long) 6000L else 3000L)
    }

    /** 当前方向下的整屏像素尺寸（含状态栏、导航栏）。 */
    @Suppress("DEPRECATION")
    fun screenSize(): Point = Point().also {
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it)
    }

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
        source.prepare { problem ->
            if (problem != null) { capturing = false; toast(problem, long = true); return@prepare }
            bar?.hide(); card?.hideForCapture()
            main.postDelayed({ capture() }, 220)   // 等一两帧，确保悬浮条和卡片已经从屏幕上消失
        }
    }

    fun onCardTap() {
        val c = card ?: return
        if (c.visible) { c.hide(); return }
        bar?.setDot(false)
        if (c.hasContent) { c.reshow(); return }
        // 第一次打开：显示当前会话的最后一题；当前会话还没有题就显示空白状态（可以点 ☰ 切换会话）
        val qa = Engine.store.sessionQas(Engine.store.currentSessionId).lastOrNull()
        if (qa != null) c.show(qa, place = true) else c.showEmpty()
    }

    // ---------- 截屏和圈选 ----------

    private fun capture() {
        source.grab { shot, problem ->
            capturing = false
            if (shot == null) {
                problem?.let { toast(it, long = true) }
                bar?.show(); card?.restoreAfterCapture()
                return@grab
            }
            openLasso(shot)
        }
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
        /** 当前开着的那一套浮层（无障碍或悬浮窗），没开就是 null。 */
        @Volatile
        var current: FloatHost? = null
            private set
    }
}
