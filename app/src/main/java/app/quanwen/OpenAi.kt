package app.quanwen

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 调 OpenAI Responses API，流式返回。
 * 网络时通时断，所以还没收到内容就断开时会自动重试。
 * 所有方法都在后台线程里调用，回调也在后台线程。
 */
class OpenAi(private val baseUrl: String, private val apiKey: String) {

    interface Listener {
        fun onDelta(text: String)
        fun onDone(responseId: String?)
        fun onError(message: String)
    }

    class HttpError(val code: Int, message: String) : IOException(message)

    fun stream(body: JSONObject, listener: Listener) {
        body.put("stream", true)
        var attempt = 0
        while (true) {
            attempt++
            var gotAny = false
            try {
                streamOnce(body) { gotAny = true; listener.onDelta(it) }.let { listener.onDone(it) }
                return
            } catch (e: HttpError) {
                val msg = e.message ?: ""
                if (e.code == 400 && body.has("reasoning") && msg.contains("reasoning", ignoreCase = true)) {
                    body.remove("reasoning")   // 这个模型不支持推理强度参数，去掉再试
                    continue
                }
                if ((e.code == 429 || e.code >= 500) && attempt < 3 && !gotAny) {
                    Thread.sleep(1500L * attempt); continue
                }
                listener.onError("HTTP ${e.code}: $msg"); return
            } catch (e: IOException) {
                if (!gotAny && attempt < 5) { Thread.sleep(1000L * attempt); continue }
                listener.onError(e.javaClass.simpleName + ": " + (e.message ?: "")); return
            } catch (e: Exception) {
                listener.onError(e.javaClass.simpleName + ": " + (e.message ?: "")); return
            }
        }
    }

    /** 返回 response id。 */
    private fun streamOnce(body: JSONObject, onDelta: (String) -> Unit): String? {
        val conn = URL("$baseUrl/responses").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
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
                    if (data.isEmpty() || data == "[DONE]") continue
                    val ev = try { JSONObject(data) } catch (_: Exception) { continue }
                    when (ev.optString("type")) {
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
                }
            }
            return responseId
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(raw: String): String = try {
        JSONObject(raw).optJSONObject("error")?.optString("message") ?: raw.take(300)
    } catch (_: Exception) { raw.take(300) }
}
