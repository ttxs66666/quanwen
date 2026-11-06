package app.quanwen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/** 设置页：开无障碍、科目、语言、模型、历史记录入口。 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private lateinit var status: TextView
    private lateinit var subject: EditText
    private lateinit var model: EditText
    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var testResult: TextView
    private lateinit var updateBtn: TextView
    private lateinit var updateText: TextView
    private var pendingUpdate: Updater.Info? = null
    private var updateBusy = false

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.persistBuiltInKey()
        window.statusBarColor = Colors.PAPER
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        build()
        checkUpdate(silent = true)
    }

    override fun onResume() { super.onResume(); updateStatus() }
    override fun onPause() { super.onPause(); save() }

    private fun save() {
        prefs.subject = subject.text.toString()
        prefs.model = model.text.toString()
        prefs.baseUrl = baseUrl.text.toString()
        prefs.apiKey = apiKey.text.toString()
    }

    // ---------- 检查更新 ----------

    private fun onUpdateClick() {
        if (updateBusy) return
        val info = pendingUpdate
        if (info == null) checkUpdate(silent = false) else downloadAndInstall(info)
    }

    /** silent=true 时只在有新版本时才显示（打开设置页时自动查一次）。 */
    private fun checkUpdate(silent: Boolean) {
        updateBusy = true
        if (!silent) updateText.text = getString(R.string.checking)
        Thread {
            try {
                val info = Updater.check()
                runOnUiThread {
                    updateBusy = false
                    if (info != null) {
                        pendingUpdate = info
                        val notes = localNotes(info.notes)
                        updateText.text = getString(R.string.update_found, info.name, info.code) +
                            if (notes.isNotBlank()) "\n" + notes else ""
                        updateText.setTextColor(Colors.INK)
                        updateBtn.text = getString(R.string.download_install)
                    } else if (!silent) updateText.text = getString(R.string.up_to_date)
                }
            } catch (e: Exception) {
                runOnUiThread { updateBusy = false; if (!silent) updateText.text = getString(R.string.check_failed) + (e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    /** 更新说明是「中文 --- English」两段，只显示当前界面语言那一段。 */
    private fun localNotes(notes: String): String {
        val parts = notes.split(Regex("\\r?\\n-{3,}\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            parts.size < 2 -> notes.trim()
            prefs.lang == "en" -> parts[1]
            else -> parts[0]
        }
    }

    private fun downloadAndInstall(info: Updater.Info) {
        if (!packageManager.canRequestPackageInstalls()) {
            updateText.text = getString(R.string.allow_install)
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updateBusy = true
        updateText.text = getString(R.string.downloading, 0)
        Thread {
            try {
                val apk = Updater.download(this, info) { p -> runOnUiThread { updateText.text = getString(R.string.downloading, p) } }
                runOnUiThread { updateText.text = getString(R.string.installing) }
                Updater.install(this, apk)
            } catch (e: Exception) {
                runOnUiThread { updateText.text = getString(R.string.update_failed) + " " + (e.message ?: e.javaClass.simpleName) }
            } finally {
                runOnUiThread { updateBusy = false }
            }
        }.start()
    }

    private fun build() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Colors.PAPER); isFillViewport = true }
        val outer = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(24f), dpi(40f), dpi(24f), dpi(40f))
        }
        val w = minOf(resources.displayMetrics.widthPixels, dpi(640f))
        outer.addView(col, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.addView(outer)
        setContentView(scroll)

        col.addView(text(getString(R.string.app_name), 30f, Colors.INK, bold = true))
        col.addView(text(getString(R.string.main_tagline), 15f, Colors.MUTED).apply { setPadding(0, dpi(4f), 0, dpi(20f)) })

        // 无障碍状态
        status = text("", 16f, Colors.INK, bold = true)
        col.addView(status)
        col.addView(text(getString(R.string.a11y_help), 13f, Colors.MUTED).apply { setPadding(0, dpi(4f), 0, dpi(8f)) })
        col.addView(row(
            button(getString(R.string.open_a11y)) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            button(getString(R.string.open_app_info)) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
        ))

        // App 内圈题（无障碍开不了时用）
        section(getString(R.string.sec_in_app))
        col.addView(text(getString(R.string.in_app_help), 13f, Colors.MUTED).apply { setPadding(0, 0, 0, dpi(8f)) })
        col.addView(row(
            button(getString(R.string.src_latest)) { CircleActivity.open(this, CircleActivity.SOURCE_LATEST) },
            button(getString(R.string.src_pick)) { CircleActivity.open(this, CircleActivity.SOURCE_PICK) },
            button(getString(R.string.src_camera)) { CircleActivity.open(this, CircleActivity.SOURCE_CAMERA) },
        ))

        // 更新
        section(getString(R.string.sec_update))
        col.addView(text(getString(R.string.version_now, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), 14f, Colors.INK))
        updateBtn = button(getString(R.string.check_update)) { onUpdateClick() }
        col.addView(row(updateBtn))
        updateText = text("", 13f, Colors.MUTED).apply { setPadding(0, dpi(6f), 0, 0) }
        col.addView(updateText)

        // 学习
        section(getString(R.string.sec_study))
        subject = field(getString(R.string.subject), prefs.subject, getString(R.string.subject_hint))
        col.addView(row(
            button(getString(R.string.new_session)) {
                prefs.sessionBreakAt = System.currentTimeMillis()
                Toast.makeText(this, R.string.new_session_done, Toast.LENGTH_SHORT).show()
            },
            button(getString(R.string.history)) { startActivity(Intent(this, HistoryActivity::class.java)) },
        ))

        // 语言
        section(getString(R.string.sec_lang))
        col.addView(choice(listOf("zh" to "中文", "en" to "English"), prefs.lang) { v ->
            save(); prefs.lang = v
            QuanwenService.instance?.refreshLabels()
            recreate()
        })

        // 模型
        section(getString(R.string.sec_model))
        model = field(getString(R.string.model), prefs.model, Prefs.DEFAULT_MODEL)
        col.addView(text(getString(R.string.effort), 13f, Colors.MUTED).apply { setPadding(0, dpi(6f), 0, dpi(4f)) })
        col.addView(choice(listOf("low" to "low", "medium" to "medium", "high" to "high"), prefs.effort) { prefs.effort = it })
        baseUrl = field(getString(R.string.base_url), prefs.baseUrl, BuildConfig.OPENAI_BASE_URL)
        apiKey = field(getString(R.string.api_key), prefs.apiKey, getString(R.string.api_key_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(row(button(getString(R.string.test)) { testConnection() }))
        testResult = text("", 13f, Colors.MUTED)
        if (prefs.apiKey.isBlank()) { testResult.text = getString(R.string.no_key); testResult.setTextColor(Colors.ACCENT) }
        col.addView(testResult)
    }

    private fun updateStatus() {
        val on = QuanwenService.instance != null
        status.text = getString(if (on) R.string.status_on else R.string.status_off)
        status.setTextColor(if (on) 0xFF2E7D4F.toInt() else Colors.ACCENT)
    }

    private fun testConnection() {
        save()
        testResult.text = getString(R.string.testing)
        val m = prefs.model
        val body = JSONObject().put("model", m)
            .put("instructions", "Reply with exactly: ok")
            .put("input", "ping")
            .put("reasoning", JSONObject().put("effort", "low"))
        val t0 = System.currentTimeMillis()
        Thread {
            val sb = StringBuilder()
            OpenAi(prefs.baseUrl, prefs.apiKey).stream(body, object : OpenAi.Listener {
                override fun onDelta(text: String) { sb.append(text) }
                override fun onDone(responseId: String?) {
                    val sec = (System.currentTimeMillis() - t0) / 1000.0
                    runOnUiThread { testResult.text = getString(R.string.test_ok, m, sec) + "  “" + sb.toString().trim().take(40) + "”" }
                }
                override fun onError(message: String) { runOnUiThread { testResult.text = message } }
            })
        }.start()
    }

    // ---------- 简单的界面构件 ----------

    private fun section(title: String) {
        col.addView(text(title, 13f, Colors.ACCENT, bold = true).apply { setPadding(0, dpi(28f), 0, dpi(6f)) })
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) paint.isFakeBoldText = true
    }

    private fun field(label: String, value: String, hint: String): EditText {
        col.addView(text(label, 13f, Colors.MUTED).apply { setPadding(0, dpi(8f), 0, dpi(2f)) })
        val e = EditText(this).apply {
            setText(value); this.hint = hint; textSize = 15f; setSingleLine()
            setTextColor(Colors.INK)
        }
        col.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(Colors.INK); gravity = Gravity.CENTER
        setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
        background = rounded(0x0F000000, dp(10f), 0x22000000, dpi(1f))
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dpi(6f), 0, 0)
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (i > 0) marginStart = dpi(10f) })
        }
    }

    private fun choice(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): LinearLayout {
        val r = row()
        val views = mutableListOf<TextView>()
        fun paint(sel: String) = views.forEachIndexed { i, v ->
            val on = options[i].first == sel
            v.setTextColor(if (on) 0xFFFFFFFF.toInt() else Colors.INK)
            v.background = if (on) rounded(Colors.INK, dp(10f)) else rounded(0x0F000000, dp(10f), 0x22000000, dpi(1f))
        }
        options.forEachIndexed { i, (key, label) ->
            val b = button(label) {}
            b.setOnClickListener { paint(key); onPick(key) }
            views.add(b)
            r.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (i > 0) marginStart = dpi(10f) })
        }
        paint(selected)
        return r
    }
}
