package app.quanwen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一道圈出的题里的一轮回答：第一次回答、再提示、揭晓。 */
class Turn(
    val kind: String,                 // KIND_FIRST / KIND_MORE / KIND_REVEAL
    var text: String = "",            // 显示给用户的回答正文
    var raw: String = "",             // 模型原始输出（第一轮含类型和题目行）
    var responseId: String? = null,
    var state: Int = STATE_LOADING,
    var error: String? = null,
) {
    fun toJson() = JSONObject().put("kind", kind).put("text", text).put("raw", raw)
        .put("responseId", responseId ?: "").put("state", if (state == STATE_DONE) STATE_DONE else STATE_ERROR)
        .put("error", error ?: "")

    companion object {
        const val KIND_FIRST = "first"
        const val KIND_MORE = "more"
        const val KIND_REVEAL = "reveal"
        const val STATE_LOADING = 0
        const val STATE_STREAMING = 1
        const val STATE_DONE = 2
        const val STATE_ERROR = 3

        fun fromJson(o: JSONObject) = Turn(
            kind = o.optString("kind", KIND_FIRST),
            text = o.optString("text"),
            raw = o.optString("raw"),
            responseId = o.optString("responseId").ifBlank { null },
            state = o.optInt("state", STATE_DONE),
            error = o.optString("error").ifBlank { null },
        )
    }
}

/** 一次圈题。 */
class Qa(
    val id: String,
    val sessionId: String,
    val subject: String,
    val time: Long,
    val lang: String,
    var type: String = "",            // 提问 / 判断 / 推一步（模型自己判断）
    var title: String = "",           // 模型转写的圈内问题
    /** 圈的外接框，屏幕坐标：left, top, right, bottom。用来把卡片放在圈旁边。 */
    var bounds: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
    val turns: MutableList<Turn> = mutableListOf(),
) {
    val busy: Boolean get() = turns.any { it.state == Turn.STATE_LOADING || it.state == Turn.STATE_STREAMING }

    /** 最近一轮成功回答的 response id，追问时接在它后面。 */
    val lastResponseId: String? get() = turns.lastOrNull { it.state == Turn.STATE_DONE && it.responseId != null }?.responseId

    fun toJson(): JSONObject {
        val arr = JSONArray(); turns.forEach { arr.put(it.toJson()) }
        val b = JSONArray(); bounds.forEach { b.put(it.toDouble()) }
        return JSONObject().put("id", id).put("sessionId", sessionId).put("subject", subject)
            .put("time", time).put("lang", lang).put("type", type).put("title", title)
            .put("bounds", b).put("turns", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Qa {
            val qa = Qa(
                id = o.getString("id"),
                sessionId = o.optString("sessionId", o.getString("id")),
                subject = o.optString("subject"),
                time = o.optLong("time"),
                lang = o.optString("lang", "zh"),
                type = o.optString("type"),
                title = o.optString("title"),
            )
            o.optJSONArray("bounds")?.let { b -> if (b.length() == 4) qa.bounds = FloatArray(4) { b.optDouble(it).toFloat() } }
            o.optJSONArray("turns")?.let { arr -> for (i in 0 until arr.length()) qa.turns.add(Turn.fromJson(arr.getJSONObject(i))) }
            return qa
        }
    }
}

/**
 * 问答存在 App 私有目录 files/qa/ 下：每道题一个 json，加两张图（整页、圈内）。
 * 只在主线程里改 list；写文件很小，直接同步写。
 */
class QaStore(context: Context) {
    private val dir = File(context.filesDir, "qa").apply { mkdirs() }
    private val prefs = Prefs(context)
    val list: MutableList<Qa> = loadAll()

    private fun loadAll(): MutableList<Qa> {
        val out = mutableListOf<Qa>()
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            try { out.add(Qa.fromJson(JSONObject(f.readText()))) } catch (_: Exception) { }
        }
        out.sortBy { it.time }
        return out
    }

    fun reload() { list.clear(); list.addAll(loadAll()) }

    /** 新建一道题：和上一道同科目、间隔不到 90 分钟、中间没点过「开始新会话」，就算同一次学习。 */
    fun create(subject: String, lang: String): Qa {
        val now = System.currentTimeMillis()
        val last = list.lastOrNull()
        val sameSession = last != null && last.subject == subject &&
            now - last.time < SESSION_GAP_MS && last.time >= prefs.sessionBreakAt
        val id = now.toString()
        val qa = Qa(id = id, sessionId = if (sameSession) last!!.sessionId else id, subject = subject, time = now, lang = lang)
        list.add(qa)
        return qa
    }

    fun session(qa: Qa): List<Qa> = list.filter { it.sessionId == qa.sessionId }

    /** 本次学习里在这道题之前的问答，作为文字上下文发给模型。 */
    fun historyText(qa: Qa): String {
        val prev = session(qa).filter { it.time < qa.time }.takeLast(6)
        return prev.mapIndexed { i, p ->
            val answer = p.turns.filter { it.state == Turn.STATE_DONE }.joinToString(" / ") { it.text.replace("\n", " ") }
            "${i + 1}. [${p.type}] ${p.title} → ${answer.take(220)}"
        }.joinToString("\n")
    }

    fun save(qa: Qa) {
        try { File(dir, "${qa.id}.json").writeText(qa.toJson().toString()) } catch (_: Exception) { }
    }

    fun saveImages(qa: Qa, page: ByteArray, crop: ByteArray) {
        File(dir, "${qa.id}_page.jpg").writeBytes(page)
        File(dir, "${qa.id}_crop.jpg").writeBytes(crop)
    }

    fun pageFile(qa: Qa) = File(dir, "${qa.id}_page.jpg")
    fun cropFile(qa: Qa) = File(dir, "${qa.id}_crop.jpg")

    fun delete(qa: Qa) {
        list.remove(qa)
        File(dir, "${qa.id}.json").delete(); pageFile(qa).delete(); cropFile(qa).delete()
    }

    companion object {
        const val SESSION_GAP_MS = 90 * 60 * 1000L

        /** 从第一轮的原始输出里拆出类型、题目和正文。还没收到分隔线时正文为 null。 */
        fun parseFirst(raw: String, finished: Boolean): Triple<String, String, String?> {
            val lines = raw.split("\n")
            val sep = lines.indexOfFirst { it.trim() == "---" }
            var type = ""; var title = ""
            val headerLines = if (sep >= 0) lines.subList(0, sep) else lines
            val rest = mutableListOf<String>()
            for (l in headerLines) {
                val t = l.trim()
                val v = t.substringAfter('：', t.substringAfter(':', "")).trim()
                when {
                    t.startsWith("类型") || t.startsWith("Type", ignoreCase = true) -> type = v
                    t.startsWith("题目") || t.startsWith("Topic", ignoreCase = true) -> title = v
                    else -> rest.add(l)
                }
            }
            val body = when {
                sep >= 0 -> lines.subList(sep + 1, lines.size).joinToString("\n").trim()
                finished -> rest.joinToString("\n").trim()
                else -> null
            }
            return Triple(type, title, body)
        }
    }
}
