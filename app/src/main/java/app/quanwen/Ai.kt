package app.quanwen

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** 消息里的一段：文字或一张 JPEG 图。 */
class AiPart private constructor(val text: String?, val jpeg: ByteArray?) {
    companion object {
        fun text(s: String) = AiPart(s, null)
        fun image(jpeg: ByteArray) = AiPart(null, jpeg)
    }
}

class AiMsg(val role: String, val parts: List<AiPart>) {
    val textOnly: Boolean get() = parts.all { it.jpeg == null }
    fun joinedText() = parts.mapNotNull { it.text }.joinToString("\n")

    companion object {
        fun user(parts: List<AiPart>) = AiMsg("user", parts)
        fun user(text: String) = AiMsg("user", listOf(AiPart.text(text)))
        fun assistant(text: String) = AiMsg("assistant", listOf(AiPart.text(text)))
    }
}

/**
 * 一次请求。latest 是这一轮新发的消息，history 是前面的对话（按需才生成，因为要读图片文件）。
 * previousId：OpenAI Responses 接口上一轮的 response id，有它就不用把前面的对话（和图片）再传一遍。
 */
class AiRequest(
    val system: String,
    val latest: AiMsg,
    val history: () -> List<AiMsg> = { emptyList() },
    val previousId: String? = null,
    val maxTokens: Int = 4096,
    val useEffort: Boolean = true,
)

/** 模型列表里的一项。vision：接口明说了能不能看图就是 true/false，没说是 null。 */
class ModelInfo(val id: String, val vision: Boolean?)

/**
 * 调各家的接口，流式返回。支持三种格式（[Protocol]）：OpenAI Responses、OpenAI 兼容的 Chat Completions、Anthropic Messages。
 * 网络时通时断，所以还没收到内容就断开时会自动重试。所有方法都在后台线程里调用，回调也在后台线程。
 */
class AiClient(private val cfg: AiConfig) {

    interface Listener {
        fun onDelta(text: String)
        fun onDone(responseId: String?)
        fun onError(message: String)
    }

    class HttpError(val code: Int, message: String) : IOException(message)

    private val p get() = cfg.provider
    private val hasEffort get() = p.effortStyle != EffortStyle.NONE && cfg.effort.isNotBlank()

    fun stream(req: AiRequest, listener: Listener) {
        var effort = req.useEffort && hasEffort
        var prevId = req.previousId.takeIf { p.protocol == Protocol.RESPONSES }
        val history by lazy { req.history() }
        var attempt = 0
        while (true) {
            attempt++
            var gotAny = false
            try {
                val msgs = if (prevId != null) listOf(req.latest) else history + req.latest
                val body = buildBody(req, msgs, effort, prevId)
                val id = streamOnce(body) { gotAny = true; listener.onDelta(it) }
                listener.onDone(id)
                return
            } catch (e: HttpError) {
                val msg = e.message ?: ""
                if (e.code == 400 && effort && !gotAny) { effort = false; continue }   // 这个模型不认推理强度参数，去掉再试
                if (e.code in 400..404 && prevId != null && msg.contains("previous", ignoreCase = true)) {
                    prevId = null; continue   // 上一轮的记录在服务器上过期了：把对话整个重发
                }
                if ((e.code == 429 || e.code >= 500) && attempt < 3 && !gotAny) { Thread.sleep(1500L * attempt); continue }
                listener.onError("HTTP ${e.code}: $msg"); return
            } catch (e: IOException) {
                if (!gotAny && attempt < 5) { Thread.sleep(1000L * attempt); continue }
                listener.onError(e.javaClass.simpleName + ": " + (e.message ?: "")); return
            } catch (e: Exception) {
                listener.onError(e.javaClass.simpleName + ": " + (e.message ?: "")); return
            }
        }
    }

    // ---------- 请求体 ----------

    private fun dataUrl(b: ByteArray) = "data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP)

    private fun buildBody(req: AiRequest, msgs: List<AiMsg>, effort: Boolean, prevId: String?): JSONObject {
        val body = JSONObject().put("model", cfg.model).put("stream", true)
        when (p.protocol) {
            Protocol.RESPONSES -> {
                body.put("instructions", req.system)
                if (prevId != null) body.put("previous_response_id", prevId)
                val input = JSONArray()
                for (m in msgs) {
                    if (m.role == "assistant") { input.put(JSONObject().put("role", "assistant").put("content", m.joinedText())); continue }
                    val content = JSONArray()
                    for (part in m.parts) content.put(
                        if (part.jpeg != null) JSONObject().put("type", "input_image").put("detail", "high").put("image_url", dataUrl(part.jpeg))
                        else JSONObject().put("type", "input_text").put("text", part.text)
                    )
                    input.put(JSONObject().put("role", "user").put("content", content))
                }
                body.put("input", input)
            }
            Protocol.CHAT -> {
                val arr = JSONArray()
                if (req.system.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", req.system))
                for (m in msgs) {
                    if (m.textOnly) { arr.put(JSONObject().put("role", m.role).put("content", m.joinedText())); continue }
                    val content = JSONArray()
                    for (part in m.parts) content.put(
                        if (part.jpeg != null) JSONObject().put("type", "image_url").put("image_url",
                            JSONObject().put("url", dataUrl(part.jpeg)).apply { if (p.imageDetail) put("detail", "high") })
                        else JSONObject().put("type", "text").put("text", part.text)
                    )
                    arr.put(JSONObject().put("role", m.role).put("content", content))
                }
                body.put("messages", arr)
            }
            Protocol.ANTHROPIC -> {
                body.put("max_tokens", req.maxTokens)
                if (req.system.isNotBlank()) body.put("system", req.system)
                val arr = JSONArray()
                for (m in msgs) {
                    val content = JSONArray()
                    for (part in m.parts) content.put(
                        if (part.jpeg != null) JSONObject().put("type", "image").put("source", JSONObject()
                            .put("type", "base64").put("media_type", "image/jpeg")
                            .put("data", Base64.encodeToString(part.jpeg, Base64.NO_WRAP)))
                        else JSONObject().put("type", "text").put("text", part.text)
                    )
                    arr.put(JSONObject().put("role", m.role).put("content", content))
                }
                body.put("messages", arr)
            }
        }
        if (effort) when (p.effortStyle) {
            EffortStyle.RESPONSES -> body.put("reasoning", JSONObject().put("effort", cfg.effort))
            EffortStyle.REASONING_EFFORT -> body.put("reasoning_effort", cfg.effort)
            EffortStyle.THINKING_TYPE -> body.put("thinking", JSONObject().put("type", if (cfg.effort == "on") "enabled" else "disabled"))
            EffortStyle.ENABLE_THINKING -> body.put("enable_thinking", cfg.effort == "on")
            EffortStyle.NONE -> {}
        }
        return body
    }

    private fun open(path: String, method: String, readTimeout: Int): HttpURLConnection {
        val conn = URL(cfg.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = readTimeout
        if (p.protocol == Protocol.ANTHROPIC) {
            conn.setRequestProperty("x-api-key", cfg.key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
        } else {
            conn.setRequestProperty("Authorization", "Bearer ${cfg.key}")
        }
        return conn
    }

    /** 返回 response id。 */
    private fun streamOnce(body: JSONObject, onDelta: (String) -> Unit): String? {
        val path = when (p.protocol) {
            Protocol.RESPONSES -> "/responses"
            Protocol.CHAT -> "/chat/completions"
            Protocol.ANTHROPIC -> "/messages"
        }
        val conn = open(path, "POST", 120_000)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw HttpError(code, errorMessage(err))
            }

            var responseId: String? = null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") return responseId
                    if (data.isEmpty()) continue
                    val ev = try { JSONObject(data) } catch (_: Exception) { continue }
                    when (p.protocol) {
                        Protocol.RESPONSES -> when (ev.optString("type")) {
                            "response.created" -> responseId = ev.optJSONObject("response")?.optString("id") ?: responseId
                            "response.output_text.delta" -> onDelta(ev.optString("delta"))
                            "response.completed", "response.incomplete" ->
                                return ev.optJSONObject("response")?.optString("id") ?: responseId
                            "response.failed" -> {
                                val m = ev.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                                throw HttpError(500, m ?: "response failed")
                            }
                            "error" -> throw HttpError(500, ev.optString("message", ev.optJSONObject("error")?.optString("message") ?: "error"))
                        }
                        Protocol.CHAT -> {
                            ev.optJSONObject("error")?.let { throw HttpError(500, it.optString("message", "error")) }
                            if (responseId == null) responseId = ev.optString("id").ifBlank { null }
                            val delta = ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                            // 只要正文；推理过程（reasoning_content）不显示
                            if (delta != null && delta.has("content") && !delta.isNull("content")) {
                                val t = delta.optString("content")
                                if (t.isNotEmpty()) onDelta(t)
                            }
                        }
                        Protocol.ANTHROPIC -> when (ev.optString("type")) {
                            "message_start" -> responseId = ev.optJSONObject("message")?.optString("id") ?: responseId
                            "content_block_delta" -> ev.optJSONObject("delta")?.let { d ->
                                if (d.optString("type") == "text_delta") onDelta(d.optString("text"))
                            }
                            "message_stop" -> return responseId
                            "error" -> throw HttpError(500, ev.optJSONObject("error")?.optString("message") ?: "error")
                        }
                    }
                }
            }
            return responseId
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 模型列表 ----------

    /** 拉这把密钥能用的模型（不花钱）。密钥不对抛 [HttpError]（401/403），网络不通抛 IOException。 */
    fun models(): List<ModelInfo> {
        val conn = open(if (p.protocol == Protocol.ANTHROPIC) "/models?limit=100" else "/models", "GET", 15_000)
        conn.connectTimeout = 10_000
        try {
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, errorMessage(raw))
            val root = try { JSONObject(raw) } catch (e: Exception) { throw HttpError(code, "not a model list") }
            val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: throw HttpError(code, "not a model list")
            val out = mutableListOf<ModelInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { o.optString("name") }.removePrefix("models/")
                if (id.isBlank() || !Providers.isChatModel(id)) continue
                val mods = o.optJSONArray("input_modalities") ?: o.optJSONObject("architecture")?.optJSONArray("input_modalities")
                val vision = mods?.let { m -> (0 until m.length()).any { m.optString(it) == "image" } }
                out.add(ModelInfo(id, vision))
            }
            return out.distinctBy { it.id }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** 各家的报错格式：{"error":{"message"}}、Gemini 的 [{"error":{...}}]、或者纯文字。 */
        fun errorMessage(raw: String): String = try {
            val t = raw.trim()
            val o = if (t.startsWith("[")) JSONArray(t).optJSONObject(0) else JSONObject(t)
            val e = o?.opt("error")
            when (e) {
                is JSONObject -> e.optString("message").ifBlank { e.toString().take(300) }
                is String -> e
                else -> o?.optString("message")?.ifBlank { null } ?: raw.take(300)
            }
        } catch (_: Exception) { raw.take(300) }
    }
}
