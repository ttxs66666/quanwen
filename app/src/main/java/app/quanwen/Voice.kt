package app.quanwen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 语音追问：录一段话（m4a），交给 OpenAI 转成文字。
 * 不用系统自带的语音识别：国内机型、卓易通里不一定有；用同一个 OpenAI 密钥哪里都能用，专业词也认得准。
 */
object VoiceRecorder {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    /** 录音过程中的最大音量（0..32767）。几乎没声音时就不去转写：转写模型对着静音会编出话来。 */
    private var peak = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sampler = object : Runnable {
        override fun run() {
            val r = rec ?: return
            peak = maxOf(peak, try { r.maxAmplitude } catch (_: Exception) { 0 })
            handler.postDelayed(this, 100)
        }
    }

    enum class Result { OK, TOO_SHORT, SILENT, ERROR }

    val recording: Boolean get() = rec != null

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context): Boolean {
        if (rec != null) return true
        val f = File(ctx.cacheDir, "voice.m4a").apply { delete() }
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(48000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r; file = f; startedAt = System.currentTimeMillis(); peak = 0
            r.maxAmplitude   // 第一次调用会清零
            handler.postDelayed(sampler, 100)
            true
        } catch (e: Exception) {
            r.release(); false
        }
    }

    /** 停止录音。返回结果和录音文件（只有 OK 时文件才可用）。 */
    fun stop(): Pair<Result, File?> {
        val r = rec ?: return Result.ERROR to null
        handler.removeCallbacks(sampler)
        peak = maxOf(peak, try { r.maxAmplitude } catch (_: Exception) { 0 })
        rec = null
        val ok = try { r.stop(); true } catch (_: Exception) { false }
        r.release()
        val f = file
        return when {
            System.currentTimeMillis() - startedAt < 600 -> Result.TOO_SHORT to null
            !ok || f == null || f.length() == 0L -> Result.ERROR to null
            peak < SILENCE_PEAK -> Result.SILENT to null
            else -> Result.OK to f
        }
    }

    /** 转成文字（后台线程）。网络时通时断，失败会重试几次。 */
    fun transcribe(baseUrl: String, apiKey: String, audio: File, hint: String): String {
        var last: IOException? = null
        for (attempt in 1..4) {
            try { return transcribeOnce(baseUrl, apiKey, audio, hint) }
            catch (e: IOException) { last = e; Thread.sleep(1000L * attempt) }
        }
        throw last ?: IOException("transcription failed")
    }

    private fun transcribeOnce(baseUrl: String, apiKey: String, audio: File, hint: String): String {
        val boundary = "----quanwen" + System.currentTimeMillis()
        val conn = URL("$baseUrl/audio/transcriptions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            DataOutputStream(conn.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                    out.write(value.toByteArray(Charsets.UTF_8)); out.write("\r\n".toByteArray())
                }
                field("model", MODEL)
                if (hint.isNotBlank()) field("prompt", hint)
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.m4a\"\r\n" +
                    "Content-Type: audio/mp4\r\n\r\n").toByteArray(Charsets.UTF_8))
                audio.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                if (code == 429 || code >= 500) throw IOException("HTTP $code ${msg ?: ""}")
                throw IllegalStateException("HTTP $code ${msg ?: body.take(200)}")
            }
            return JSONObject(body).optString("text").trim()
        } finally { conn.disconnect() }
    }

    const val MODEL = "gpt-4o-transcribe"
    const val MAX_MS = 60_000L
    /** 低于这个音量就当作没说话。正常说话通常在几千以上。 */
    const val SILENCE_PEAK = 900
}

/** 透明页面，只用来请求麦克风权限（悬浮卡片在无障碍服务里，没法直接弹权限框）。 */
class MicPermissionActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VoiceRecorder.hasPermission(this)) { finish(); return }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Toast.makeText(this, if (VoiceRecorder.hasPermission(this)) R.string.mic_granted else R.string.mic_needed, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
