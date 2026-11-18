package app.quanwen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import java.io.File
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
        /** 笔记本的名字，作为上下文告诉 AI。 */
        book: String = "",
    ): Qa {
        val qa = store.create("", prefs.lang)
        qa.origin = origin
        qa.book = book
        // 圈完写的模式记号（gg、j 或用户自己设的）不算圈；一个记号都没设时，小笔画也算圈
        val (circle, marks) = if (Marks.load(prefs).isEmpty()) strokes to emptyList() else ImageUtil.splitMarks(strokes)
        if (screenBounds != null) qa.bounds = screenBounds
        val turn = Turn(Turn.KIND_FIRST)
        qa.turns.add(turn)
        val history = store.historyText(qa)
        store.save(qa)
        notify(qa, false)
        io.execute {
            try {
                val (page, crop) = ImageUtil.prepare(image, circle, marker)
                if (recycleWhenDone) image.recycle()
                val mark = if (marks.isEmpty()) null else ImageUtil.renderMarks(marks).let { m -> ImageUtil.jpeg(m).also { m.recycle() } }
                val prev = prevPage?.let { p ->
                    val s = ImageUtil.scaleToMax(p, ImageUtil.PAGE_MAX)
                    ImageUtil.jpeg(s).also { if (s !== p) s.recycle(); p.recycle() }
                }
                store.saveImages(qa, page, crop, prev, mark)
                runFirst(qa, turn, page, crop, prev, mark, history)
            } catch (e: Exception) {
                main.post { fail(qa, turn, e.toString()) }
            }
        }
        return qa
    }

    /**
     * 再提示一点 / 揭晓 / 追问：接在上一轮回答后面。
     * OpenAI 用 previous_response_id 接上，题目截图不用重发；别家的接口不在服务器上记对话，就把前面的对话（连图片）一起发过去。
     * handwriting：手写追问的字迹图（JPEG），有的话和文字一起发给模型。
     */
    fun followUp(qa: Qa, kind: String, question: String = "", handwriting: ByteArray? = null, imagePath: String = "") {
        if (qa.busy || !qa.canFollow) return
        val path = if (handwriting != null) store.saveAskImage(qa, handwriting) else imagePath
        val text = when (kind) {
            Turn.KIND_MORE -> Prompts.more(qa.lang)
            Turn.KIND_ASK -> if (path.isNotBlank() && question.isBlank()) Prompts.askHandwritten(qa.lang) else Prompts.ask(qa.lang, question)
            else -> Prompts.reveal(qa.lang)
        }
        val prior = qa.turns.toList()
        val turn = Turn(kind, question = question, imagePath = path, prompt = text)
        qa.turns.add(turn)
        notify(qa, false)
        io.execute {
            val parts = mutableListOf(AiPart.text(text))
            if (path.isNotBlank()) {
                val bytes = handwriting ?: try { File(path).readBytes() } catch (_: Exception) { null }
                if (bytes != null) parts.add(AiPart.image(bytes))
            }
            send(qa, turn, Prompts.system(qa.lang), AiMsg.user(parts), prior)
        }
    }

    // ---------- 「一同书写」 ----------

    /**
     * 笔记本里写了一个问号：page 是整页（宽 [ImageUtil.PAGE_MAX]），region 是问题那一块在 page 上的范围（像素），
     * prevPage 是问题靠近页顶时附上的上一页，layout 告诉 AI 每行大约能写几个字。回答的正文会被写到纸上。
     */
    fun askWrite(page: Bitmap, region: RectF, prevPage: Bitmap?, book: String, layout: String): Qa {
        val qa = store.create("", prefs.lang)
        qa.origin = Qa.ORIGIN_WRITE
        qa.book = book
        qa.type = if (prefs.lang == "en") "Write" else "书写"
        val turn = Turn(Turn.KIND_FIRST)
        qa.turns.add(turn)
        val history = store.historyText(qa)
        store.save(qa)
        notify(qa, false)
        io.execute {
            try {
                val (pj, cj) = ImageUtil.prepareBox(page, region)
                page.recycle()
                val prev = prevPage?.let { p ->
                    val s = ImageUtil.scaleToMax(p, ImageUtil.PAGE_MAX)
                    ImageUtil.jpeg(s).also { if (s !== p) s.recycle(); p.recycle() }
                }
                store.saveImages(qa, pj, cj, prev, null)
                qa.firstText = Prompts.writeFirstText(qa.lang, qa.book, history, prev != null, layout)
                send(qa, turn, Prompts.writeSystem(qa.lang), firstMessage(qa, pj, cj, prev, null), emptyList())
            } catch (e: Exception) {
                main.post { fail(qa, turn, e.toString()) }
            }
        }
        return qa
    }

    /** 他在纸上回答了 AI 的询问（以句号结尾）：接着这道题的对话发过去，AI 重新回答。返回是否发出去了。 */
    fun clarifyWrite(qa: Qa, page: Bitmap, region: RectF, layout: String): Boolean {
        if (qa.busy || !qa.canFollow) return false
        val text = Prompts.writeClarify(qa.lang, layout)
        val prior = qa.turns.toList()
        val turn = Turn(Turn.KIND_ASK, prompt = text)
        qa.turns.add(turn)
        notify(qa, false)
        io.execute {
            try {
                val (pj, cj) = ImageUtil.prepareBox(page, region)
                page.recycle()
                // 存下来：换了别家接口、要把对话重发时还用得上
                turn.images = listOf(store.saveAskImage(qa, pj), store.saveAskImage(qa, cj))
                send(qa, turn, Prompts.writeSystem(qa.lang), AiMsg.user(listOf(AiPart.text(text), AiPart.image(pj), AiPart.image(cj))), prior)
            } catch (e: Exception) {
                main.post { fail(qa, turn, e.toString()) }
            }
        }
        return true
    }

    fun retry(qa: Qa, index: Int) {
        if (qa.origin == Qa.ORIGIN_WRITE) return   // 「一同书写」的回答写在纸上，不在这里重试
        val turn = qa.turns.getOrNull(index) ?: return
        if (turn.state != Turn.STATE_ERROR || qa.busy) return
        if (turn.kind == Turn.KIND_FIRST) {
            turn.state = Turn.STATE_LOADING; turn.error = null; turn.text = ""; turn.raw = ""
            notify(qa, false)
            val history = store.historyText(qa)
            io.execute {
                try {
                    val prev = store.prevFile(qa).takeIf { it.exists() }?.readBytes()
                    val mark = store.markFile(qa).takeIf { it.exists() }?.readBytes()
                    runFirst(qa, turn, store.pageFile(qa).readBytes(), store.cropFile(qa).readBytes(), prev, mark, history)
                } catch (e: Exception) { main.post { fail(qa, turn, e.toString()) } }
            }
        } else {
            qa.turns.removeAt(index)
            followUp(qa, turn.kind, turn.question, imagePath = turn.imagePath)
        }
    }

    /** 后台线程。 */
    private fun runFirst(qa: Qa, turn: Turn, page: ByteArray, crop: ByteArray, prev: ByteArray?, mark: ByteArray?, history: String) {
        val notebook = qa.origin == Qa.ORIGIN_NOTE
        qa.firstText = Prompts.firstTurnText(qa.lang, qa.book, history, notebook, hasPrev = prev != null)
        send(qa, turn, Prompts.system(qa.lang), firstMessage(qa, page, crop, prev, mark), emptyList())
    }

    /** 第一轮发给模型的消息：说明文字 + 整页 + 圈内放大图（+ 上一页）（+ 模式记号）。 */
    private fun firstMessage(qa: Qa, page: ByteArray, crop: ByteArray, prev: ByteArray?, mark: ByteArray?): AiMsg {
        val parts = mutableListOf(
            AiPart.text(qa.firstText), AiPart.image(page),
            AiPart.text(Prompts.zoomLabel(qa.lang)), AiPart.image(crop),
        )
        if (prev != null) { parts.add(AiPart.text(Prompts.prevPageLabel(qa.lang))); parts.add(AiPart.image(prev)) }
        if (qa.origin != Qa.ORIGIN_WRITE) {
            val modes = Marks.load(prefs)
            if (mark != null && modes.isNotEmpty()) {   // 模式记号：每个记号是一段用户设好的提示词
                parts.add(AiPart.text(Prompts.markText(qa.lang, modes))); parts.add(AiPart.image(mark))
            }
        }
        return AiMsg.user(parts)
    }

    /** 从存下的文件重建第一轮的消息（后台线程）。 */
    private fun storedFirst(qa: Qa): AiMsg {
        fun read(f: File) = if (f.exists()) try { f.readBytes() } catch (_: Exception) { null } else null
        val page = read(store.pageFile(qa))
        val crop = read(store.cropFile(qa))
        val prev = read(store.prevFile(qa))
        if (qa.firstText.isBlank()) qa.firstText =   // 第 18 版及以前没存这段文字，按当时的写法重新生成
            if (qa.origin == Qa.ORIGIN_WRITE) Prompts.writeFirstText(qa.lang, qa.book, "", prev != null, "")
            else Prompts.firstTurnText(qa.lang, qa.book, "", qa.origin == Qa.ORIGIN_NOTE, hasPrev = prev != null)
        if (page == null || crop == null) return AiMsg.user(qa.firstText)
        return firstMessage(qa, page, crop, prev, read(store.markFile(qa)))
    }

    /** 某一轮追问发给模型的消息（后台线程）。 */
    private fun turnMessage(qa: Qa, t: Turn): AiMsg {
        val text = t.prompt.ifBlank {
            when {
                qa.origin == Qa.ORIGIN_WRITE -> Prompts.writeClarify(qa.lang, "")
                t.kind == Turn.KIND_MORE -> Prompts.more(qa.lang)
                t.kind == Turn.KIND_ASK -> if (t.imagePath.isNotBlank() && t.question.isBlank()) Prompts.askHandwritten(qa.lang) else Prompts.ask(qa.lang, t.question)
                else -> Prompts.reveal(qa.lang)
            }
        }
        val parts = mutableListOf(AiPart.text(text))
        for (path in listOf(t.imagePath) + t.images) {
            if (path.isBlank()) continue
            try { parts.add(AiPart.image(File(path).readBytes())) } catch (_: Exception) { }
        }
        return AiMsg.user(parts)
    }

    /** 前面几轮的完整对话（只算答成功的轮次），给不在服务器上记对话的接口用（后台线程）。 */
    private fun historyFor(qa: Qa, prior: List<Turn>): List<AiMsg> {
        val out = mutableListOf<AiMsg>()
        prior.forEachIndexed { i, t ->
            if (t.state != Turn.STATE_DONE) return@forEachIndexed
            out.add(if (i == 0) storedFirst(qa) else turnMessage(qa, t))
            out.add(AiMsg.assistant(t.raw.ifBlank { t.text }))
        }
        return out
    }

    /** 后台线程：用当前这一家发出去。prior 是这一轮之前的轮次。 */
    private fun send(qa: Qa, turn: Turn, system: String, latest: AiMsg, prior: List<Turn>) {
        val cfg = prefs.ai()
        turn.via = cfg.provider.id
        // OpenAI 的上一轮记录只有同一家才接得上（第 18 版以前的都是 OpenAI 答的）
        val last = prior.lastOrNull { it.state == Turn.STATE_DONE }
        val previousId = last?.responseId?.takeIf { cfg.provider.protocol == Protocol.RESPONSES && last.via.ifBlank { Providers.OPENAI } == cfg.provider.id }
        val req = AiRequest(system, latest, history = { historyFor(qa, prior) }, previousId = previousId)
        streamInto(qa, turn, cfg, req)
    }

    /** 后台线程。 */
    private fun streamInto(qa: Qa, turn: Turn, cfg: AiConfig, req: AiRequest) {
        val sb = StringBuilder()
        AiClient(cfg).stream(req, object : AiClient.Listener {
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
