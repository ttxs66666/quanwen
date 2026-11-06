package app.quanwen

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新：查 GitHub 上这个仓库最新的 Release，比版本号，下载 APK，交给系统安装。
 * Release 的 tag 写成 v<versionCode>（比如 v3），附件是一个 .apk。仓库是公开的，不需要任何凭证。
 * 方法都会阻塞，要在后台线程里调用。
 */
object Updater {

    class Info(val code: Int, val name: String, val notes: String, val apkUrl: String, val size: Long)

    private const val UA = "Quanwen-App"

    /** 有比当前更新的版本就返回它，否则返回 null。网络出错抛 IOException。 */
    fun check(): Info? {
        val json = getText("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val o = JSONObject(json)
        val code = o.optString("tag_name").trimStart('v', 'V').toIntOrNull() ?: return null
        if (code <= BuildConfig.VERSION_CODE) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return Info(code, o.optString("name").ifBlank { "v$code" }, o.optString("body").trim(),
                    a.getString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    /** 下载到缓存目录，onProgress 给 0..100。 */
    fun download(context: Context, info: Info, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "quanwen-${info.code}.apk")
        retry {
            val conn = open(info.apkUrl)
            try {
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
                conn.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L; var last = -1
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                        }
                    }
                }
            } finally { conn.disconnect() }
        }
        return out
    }

    /** 用 PackageInstaller 安装；系统会弹一次确认。结果回到 [UpdateReceiver]。 */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java)
            val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pi.intentSender)
        }
    }

    private fun getText(url: String): String {
        var result = ""
        retry {
            val conn = open(url)
            try {
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                result = conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }
        }
        return result
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", UA)
    }

    /** 国内网络时通时断，失败了再试几次。 */
    private fun retry(block: () -> Unit) {
        var last: IOException? = null
        for (attempt in 1..4) {
            try { block(); return } catch (e: IOException) { last = e; Thread.sleep(1000L * attempt) }
        }
        throw last ?: IOException("failed")
    }
}

/** 接收安装结果：需要用户确认就把系统的确认页拉起来。 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit   // 装好后系统会重启 App
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(context, Lang.wrap(context).getString(R.string.update_failed) + " " + msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
