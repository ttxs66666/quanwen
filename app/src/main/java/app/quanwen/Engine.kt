package app.quanwen

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 提问引擎：建题、发请求、收流式回答、追问、重试。
 * 悬浮条（无障碍服务）和 App 内圈题页共用这一份，问答记录也共用同一个 [store]。
 * 除了标明「后台线程」的方法，都在主线程调用；回调也在主线程。
 */
object Engine {
    private lateinit var app: Context
    private lateinit var prefs: Prefs
    lateinit var store: QaStore
        private set
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    /** (题, 这一轮是否结束)。结束指成功或失败。 */
    private val listeners = mutableListOf<(Qa, Boolean) -> Unit>()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = Prefs(app)
        prefs.persistBuiltInKey()
        store = QaStore(app)
    }

    fun addListener(l: (Qa, Boolean) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Qa, Boolean) -> Unit) { listeners.remove(l) }
    private fun notify(qa: Qa, finished: Boolean) = listeners.toList().forEach { it(qa, finished) }

    val anyBusy: Boolean get() = store.list.any { it.busy }

    /**
     * 圈了一道题。image 是截图或照片，strokes 是笔迹（图片像素坐标）。
     * screenBounds 是圈在屏幕上的位置，悬浮条模式用它把卡片摆在圈旁边；App 内圈题传 null。
     */
    fun ask(
        image: Bitmap, strokes: List<FloatArray>, screenBounds: FloatArray?, recycleWhenDone: Boolean,
        /** 发给模型的整页上怎么标出圈：红线（截屏）或橙色荧光（笔记本）。 */
        marker: Int = ImageUtil.MARK_RED,
        /** 笔记本里圈在页面上部时附上的上一页；用完回收。 */
        prevPage: Bitmap? = null,
        origin: String = "",
    ): Qa {
        val qa = store.create(prefs.subject, prefs.lang)
        qa.origin = origin
        if (screenBounds != null) qa.bounds = screenBounds
        val turn = Turn(Turn.KIND_FIRST)
        qa.turns.add(turn)
        val history = store.historyText(qa)
        store.save(qa)
        notify(qa, false)
        io.execute {
            try {
                val (page, crop) = ImageUtil.prepare(image, strokes, marker)
                if (recycleWhenDone) image.recycle()
                val prev = prevPage?.let { p ->
                    val s = ImageUtil.scaleToMax(p, ImageUtil.PAGE_MAX)
                    ImageUtil.jpeg(s).also { if (s !== p) s.recycle(); p.recycle() }
                }
                store.saveImages(qa, page, crop, prev)
                runFirst(qa, turn, page, crop, prev, history)
            } catch (e: Exception) {
                main.post { fail(qa, turn, e.toString()) }
            }
        }
        return qa
    }

    /**
     * 再提示一点 / 揭晓 / 追问：接在上一轮回答后面（previous_response_id），题目截图不用重发。
     * handwriting：手写追问的字迹图（JPEG），有的话和文字一起发给模型。
     */
    fun followUp(qa: Qa, kind: String, question: String = "", handwriting: ByteArray? = null, imagePath: String = "") {
        if (qa.busy) return
        val prev = qa.lastResponseId ?: return
        val path = if (handwriting != null) store.saveAskImage(qa, handwriting) else imagePath
        val turn = Turn(kind, question = question, imagePath = path)
        qa.turns.add(turn)
        notify(qa, false)
        val text = when (kind) {
            Turn.KIND_MORE -> Prompts.more(qa.lang)
            Turn.KIND_ASK -> if (path.isNotBlank() && question.isBlank()) Prompts.askHandwritten(qa.lang) else Prompts.ask(qa.lang, question)
            else -> Prompts.reveal(qa.lang)
        }
        val body = baseBody(qa.lang)
            .put("previous_response_id", prev)
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", text))
        if (path.isNotBlank()) {
            val bytes = handwriting ?: try { java.io.File(path).readBytes() } catch (_: Exception) { null }
            if (bytes != null) content.put(image(bytes))
        }
        body.put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        io.execute { streamInto(qa, turn, body) }
    }

    fun retry(qa: Qa, index: Int) {
        val turn = qa.turns.getOrNull(index) ?: return
        if (turn.state != Turn.STATE_ERROR || qa.busy) return
        if (turn.kind == Turn.KIND_FIRST) {
            turn.state = Turn.STATE_LOADING; turn.error = null; turn.text = ""; turn.raw = ""
            notify(qa, false)
            val history = store.historyText(qa)
            io.execute {
                try {
                    val prev = store.prevFile(qa).takeIf { it.exists() }?.readBytes()
                    runFirst(qa, turn, store.pageFile(qa).readBytes(), store.cropFile(qa).readBytes(), prev, history)
                } catch (e: Exception) { main.post { fail(qa, turn, e.toString()) } }
            }
        } else {
            qa.turns.removeAt(index)
            followUp(qa, turn.kind, turn.question, imagePath = turn.imagePath)
        }
    }

    /** 后台线程。 */
    private fun runFirst(qa: Qa, turn: Turn, page: ByteArray, crop: ByteArray, prev: ByteArray?, history: String) {
        val notebook = qa.origin == Qa.ORIGIN_NOTE
        val content = JSONArray()
            .put(JSONObject().put("type", "input_text").put("text",
                Prompts.firstTurnText(qa.lang, qa.subject, history, notebook, hasPrev = prev != null)))
            .put(image(page))
            .put(JSONObject().put("type", "input_text").put("text", Prompts.zoomLabel(qa.lang)))
            .put(image(crop))
        if (prev != null) content
            .put(JSONObject().put("type", "input_text").put("text", Prompts.prevPageLabel(qa.lang)))
            .put(image(prev))
        val body = baseBody(qa.lang)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        streamInto(qa, turn, body)
    }

    private fun image(bytes: ByteArray) = JSONObject().put("type", "input_image").put("detail", "high")
        .put("image_url", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))

    private fun baseBody(lang: String) = JSONObject()
        .put("model", prefs.model)
        .put("instructions", Prompts.system(lang))
        .put("reasoning", JSONObject().put("effort", prefs.effort))

    /** 后台线程。 */
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
                    store.save(qa)
                    notify(qa, true)
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
            // 兜底：模型偶尔会给追问也写上「类型 / 题目 / ---」，去掉这几行
            val (type, _, body) = QaStore.parseFirst(raw, finished)
            turn.text = if (type.isNotEmpty() && body != null) body else if (type.isNotEmpty()) "" else raw.trim()
        }
        if (turn.text.isNotEmpty()) turn.state = Turn.STATE_STREAMING
        if (!finished) notify(qa, false)
    }

    private fun fail(qa: Qa, turn: Turn, message: String) {
        turn.state = Turn.STATE_ERROR
        turn.error = message
        store.save(qa)
        notify(qa, true)
    }
}
