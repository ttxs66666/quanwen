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
import android.util.Base64
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 整个 App 的核心：一个无障碍服务。
 * 它负责悬浮条、截屏、圈选层、回答卡片，以及调模型。
 * 截屏用无障碍服务自带的 takeScreenshot（Android 11+），在系统设置里开一次就行。
 */
class QuanwenService : AccessibilityService() {

    lateinit var wm: WindowManager
        private set
    lateinit var store: QaStore
        private set
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    private var bar: FloatBar? = null
    private var card: AnswerCard? = null
    private var lasso: LassoOverlay? = null
    private var capturing = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        prefs.persistBuiltInKey()
        store = QaStore(this)
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

    fun reloadStore() { store.reload(); card?.let { if (it.current != null && store.list.none { q -> q.id == it.current?.id }) it.hide() } }

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
        val qa = c.current ?: store.list.lastOrNull()
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

    // ---------- 提问 ----------

    private fun ask(shot: Bitmap, strokes: List<FloatArray>) {
        val qa = store.create(prefs.subject, prefs.lang)
        val scale = screenSize().x.toFloat() / shot.width
        val b = ImageUtil.bounds(strokes)
        qa.bounds = floatArrayOf(b.left * scale, b.top * scale, b.right * scale, b.bottom * scale)
        val turn = Turn(Turn.KIND_FIRST)
        qa.turns.add(turn)
        val history = store.historyText(qa)
        store.save(qa)
        card?.show(qa, place = true)
        updateBar()
        io.execute {
            try {
                val (page, crop) = ImageUtil.prepare(shot, strokes)
                shot.recycle()
                store.saveImages(qa, page, crop)
                runFirst(qa, turn, page, crop, history)
            } catch (e: Exception) {
                main.post { fail(qa, turn, e.toString()) }
            }
        }
    }

    /** 后台线程。 */
    private fun runFirst(qa: Qa, turn: Turn, page: ByteArray, crop: ByteArray, history: String) {
        val content = JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", Prompts.firstTurnText(qa.lang, qa.subject, history)))
            .put(image(page))
            .put(JSONObject().put("type", "input_text").put("text", Prompts.zoomLabel(qa.lang)))
            .put(image(crop))
        val body = baseBody(qa.lang)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        streamInto(qa, turn, body)
    }

    /** 再提示一点 / 揭晓：接在上一轮回答后面（previous_response_id），图片不用重发。 */
    fun followUp(qa: Qa, kind: String) {
        if (qa.busy) return
        val prev = qa.lastResponseId ?: return
        val turn = Turn(kind)
        qa.turns.add(turn)
        card?.refresh(qa)
        updateBar()
        val text = if (kind == Turn.KIND_MORE) Prompts.more(qa.lang) else Prompts.reveal(qa.lang)
        val body = baseBody(qa.lang)
            .put("previous_response_id", prev)
            .put("input", JSONArray().put(JSONObject().put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))))
        io.execute { streamInto(qa, turn, body) }
    }

    fun retry(qa: Qa, index: Int) {
        val turn = qa.turns.getOrNull(index) ?: return
        if (turn.state != Turn.STATE_ERROR || qa.busy) return
        if (turn.kind == Turn.KIND_FIRST) {
            turn.state = Turn.STATE_LOADING; turn.error = null; turn.text = ""; turn.raw = ""
            card?.refresh(qa); updateBar()
            val history = store.historyText(qa)
            io.execute {
                try {
                    runFirst(qa, turn, store.pageFile(qa).readBytes(), store.cropFile(qa).readBytes(), history)
                } catch (e: Exception) { main.post { fail(qa, turn, e.toString()) } }
            }
        } else {
            qa.turns.removeAt(index)
            followUp(qa, turn.kind)
        }
    }

    private fun image(bytes: ByteArray) = JSONObject().put("type", "input_image").put("detail", "high")
        .put("image_url", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))

    private fun baseBody(lang: String) = JSONObject()
        .put("model", prefs.model)
        .put("instructions", Prompts.system(lang))
        .put("reasoning", JSONObject().put("effort", prefs.effort))

    /** 后台线程里调用；结果回到主线程更新。 */
    private fun streamInto(qa: Qa, turn: Turn, body: JSONObject) {
        val sb = StringBuilder()
        OpenAi(prefs.baseUrl, prefs.apiKey).stream(body, object : OpenAi.Listener {
            override fun onDelta(text: String) {
                sb.append(text)
                val snap = sb.toString()
                main.post { applyRaw(qa, turn, snap, finished = false) }
            }

            override fun onDone(responseId: String?) {
                val snap = sb.toString()
                main.post {
                    turn.responseId = responseId
                    applyRaw(qa, turn, snap, finished = true)
                    turn.state = Turn.STATE_DONE
                    finishTurn(qa)
                }
            }

            override fun onError(message: String) { main.post { fail(qa, turn, message) } }
        })
    }

    private fun applyRaw(qa: Qa, turn: Turn, raw: String, finished: Boolean) {
        turn.raw = raw
        if (turn.kind == Turn.KIND_FIRST) {
            val (type, title, body) = QaStore.parseFirst(raw, finished)
            if (type.isNotEmpty()) qa.type = type
            if (title.isNotEmpty()) qa.title = title
            when {
                body != null -> turn.text = body
                raw.length > 200 && type.isEmpty() -> turn.text = raw.trim()   // 模型没按格式输出，直接显示
            }
        } else {
            turn.text = raw.trim()
        }
        if (turn.text.isNotEmpty()) turn.state = Turn.STATE_STREAMING
        card?.refresh(qa)
    }

    private fun finishTurn(qa: Qa) {
        store.save(qa)
        card?.refresh(qa)
        updateBar()
        val c = card
        if (c == null || !c.visible || c.current !== qa) bar?.setDot(true)
    }

    private fun fail(qa: Qa, turn: Turn, message: String) {
        turn.state = Turn.STATE_ERROR
        turn.error = message
        store.save(qa)
        card?.refresh(qa)
        updateBar()
        val c = card
        if (c == null || !c.visible || c.current !== qa) bar?.setDot(true)
    }

    private fun updateBar() = bar?.setBusy(store.list.any { it.busy })

    companion object {
        @Volatile
        var instance: QuanwenService? = null
            private set
    }
}
