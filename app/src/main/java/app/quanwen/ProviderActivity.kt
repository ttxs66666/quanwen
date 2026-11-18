package app.quanwen

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream

/**
 * 「AI 服务」页：选哪一家、填密钥、选模型、测试连接。
 * 「导入 API」：粘贴一段密钥（中转站可以连地址一起），自动认出是哪一家、挑好能看图的模型，再测一下。
 */
class ProviderActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private val lang get() = prefs.lang

    private lateinit var heroIcon: ImageView
    private lateinit var heroName: TextView
    private lateinit var heroModel: TextView
    private lateinit var heroState: TextView

    private lateinit var importEdit: EditText
    private lateinit var importStatus: TextView
    private lateinit var importBtn: TextView
    private var importing = false

    private lateinit var grid: LinearLayout
    private lateinit var detail: LinearLayout

    /** 下面详情卡片里正在看、正在改的那一家（不一定是正在用的）。 */
    private lateinit var editing: Provider
    private var keyEdit: EditText? = null
    private var modelEdit: EditText? = null
    private var baseEdit: EditText? = null
    private var nameEdit: EditText? = null
    private var protocol = Protocol.CHAT
    private var effort = ""
    private var result: TextView? = null
    private var testing = false
    private var keyShown = false

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.persistBuiltInKey()
        window.statusBarColor = Colors.PAPER
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        editing = prefs.provider
        build()
    }

    override fun onPause() { super.onPause(); saveDetail() }

    // ---------- 页面 ----------

    private fun build() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Colors.PAPER); isFillViewport = true }
        val outer = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20f), dpi(28f), dpi(20f), dpi(40f))
        }
        val w = minOf(resources.displayMetrics.widthPixels, dpi(720f))
        outer.addView(col, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.addView(outer)
        setContentView(imeAware(scroll))

        // 标题
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("‹", 30f, Colors.INK).apply {
            setPadding(dpi(4f), 0, dpi(14f), dpi(2f)); setOnClickListener { finish() }
        })
        head.addView(ImageView(this).apply { setImageResource(R.drawable.ic_ai_service) }, LinearLayout.LayoutParams(dpi(30f), dpi(30f)))
        head.addView(text(getString(R.string.ai_title), 26f, Colors.INK, bold = true).apply { setPadding(dpi(8f), 0, 0, 0) })
        col.addView(head)
        col.addView(text(getString(R.string.ai_sub), 14f, Colors.MUTED).apply { setPadding(0, dpi(8f), 0, dpi(16f)) })

        // 正在使用
        val hero = card()
        val heroRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heroIcon = ImageView(this)
        heroRow.addView(heroIcon, LinearLayout.LayoutParams(dpi(56f), dpi(56f)))
        val heroText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(16f), 0, 0, 0) }
        heroText.addView(text(getString(R.string.ai_using), 12f, Colors.MUTED))
        heroName = text("", 20f, Colors.INK, bold = true)
        heroModel = text("", 14f, Colors.INK).apply { typeface = Typeface.MONOSPACE }
        heroState = text("", 13f, Colors.MUTED).apply { setPadding(0, dpi(3f), 0, 0) }
        heroText.addView(heroName); heroText.addView(heroModel); heroText.addView(heroState)
        heroRow.addView(heroText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(heroRow)
        col.addView(hero)

        // 导入 API
        val imp = card(fill = 0xFFFFF5EF.toInt(), stroke = 0x22D2412F)
        val impHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        impHead.addView(ImageView(this).apply { setImageResource(R.drawable.ic_import) }, LinearLayout.LayoutParams(dpi(30f), dpi(30f)))
        val impText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12f), 0, 0, 0) }
        impText.addView(text(getString(R.string.import_title), 17f, Colors.INK, bold = true))
        impText.addView(text(getString(R.string.import_desc), 13f, Colors.MUTED))
        impHead.addView(impText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        imp.addView(impHead)
        importEdit = EditText(this).apply {
            hint = getString(R.string.import_hint); textSize = 14f; typeface = Typeface.MONOSPACE
            setTextColor(Colors.INK); minLines = 2; maxLines = 4; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = rounded(Color.WHITE, dp(12f), 0x22000000, dpi(1f))
            setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
        }
        imp.addView(importEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })
        importBtn = button(getString(R.string.import_go), primary = true) { onImport() }
        imp.addView(row(button(getString(R.string.paste)) { pasteInto(importEdit) }, importBtn))
        importStatus = text("", 13f, Colors.MUTED).apply { setPadding(0, dpi(8f), 0, 0); visibility = View.GONE }
        imp.addView(importStatus)
        col.addView(imp)

        // 厂商
        col.addView(text(getString(R.string.providers_title), 13f, Colors.ACCENT, bold = true).apply { setPadding(dpi(2f), dpi(22f), 0, dpi(8f)) })
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(grid)

        // 详情
        detail = card()
        col.addView(detail)

        col.addView(text(getString(R.string.ai_privacy), 12f, Colors.MUTED).apply { setPadding(dpi(2f), dpi(16f), 0, 0) })

        refreshAll()
    }

    private fun refreshAll() {
        refreshHero()
        buildGrid()
        buildDetail()
    }

    private fun refreshHero() {
        val cfg = prefs.ai()
        heroIcon.setImageDrawable(ProviderIcon.of(cfg.provider))
        heroName.text = cfg.provider.name(lang)
        heroModel.text = cfg.model.ifBlank { "—" }
        when {
            cfg.key.isBlank() -> { heroState.text = getString(R.string.ai_state_nokey); heroState.setTextColor(Colors.ACCENT) }
            prefs.providerValue(cfg.provider.id, "ok") == cfg.model -> { heroState.text = getString(R.string.ai_state_ok); heroState.setTextColor(GREEN) }
            else -> { heroState.text = getString(R.string.ai_state_untested); heroState.setTextColor(Colors.MUTED) }
        }
    }

    // ---------- 厂商格子 ----------

    private fun buildGrid() {
        grid.removeAllViews()
        val items: List<Provider?> = Providers.all(prefs) + listOf(null)   // null：添加自定义接口
        val width = minOf(resources.displayMetrics.widthPixels, dpi(720f)) / resources.displayMetrics.density
        val cols = if (width >= 560) 4 else 3
        items.chunked(cols).forEach { chunk ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                val p = chunk.getOrNull(i)
                val v = if (i < chunk.size) tile(p) else View(this)
                r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
                })
            }
            grid.addView(r)
        }
    }

    private fun tile(p: Provider?): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(6f), dpi(14f), dpi(6f), dpi(12f))
        }
        val selected = p != null && p.id == editing.id
        t.background = rounded(Color.WHITE, dp(16f), if (selected) Colors.INK else 0x1A000000, if (selected) dpi(2f) else dpi(1f))
        t.elevation = dp(1f)
        t.addView(ImageView(this).apply { setImageDrawable(if (p != null) ProviderIcon.of(p) else ProviderIcon.add()) },
            LinearLayout.LayoutParams(dpi(42f), dpi(42f)))
        val name = text(p?.name(lang) ?: getString(R.string.custom_add), 13.5f, Colors.INK, bold = true).apply {
            gravity = Gravity.CENTER; setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(0, dpi(8f), 0, 0)
        }
        t.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val (sub, color) = when {
            p == null -> getString(R.string.custom_add_sub) to Colors.MUTED
            p.id == prefs.providerId -> getString(R.string.using_badge) to GREEN
            prefs.config(p).key.isNotBlank() -> getString(R.string.key_set) to Colors.MUTED
            else -> getString(R.string.not_set) to 0xFFB5B1A3.toInt()
        }
        t.addView(text(sub, 11.5f, color).apply { gravity = Gravity.CENTER; setSingleLine(); setPadding(0, dpi(2f), 0, 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        t.setOnClickListener {
            saveDetail()
            editing = p ?: newCustom()
            keyShown = false
            buildGrid(); buildDetail()
            detail.post { (col.parent?.parent as? ScrollView)?.smoothScrollTo(0, detail.top - dpi(12f)) }
        }
        return t
    }

    private fun newCustom(): Provider {
        val n = Providers.customs(prefs).size + 1
        val p = Providers.customProvider(Providers.newCustomId(), getString(R.string.custom_default_name, n), "", Protocol.CHAT)
        Providers.saveCustom(prefs, p)
        return p
    }

    // ---------- 详情 ----------

    private fun buildDetail() {
        detail.removeAllViews()
        val p = editing
        val cfg = prefs.config(p)
        protocol = p.protocol
        effort = cfg.effort

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageDrawable(ProviderIcon.of(p)) }, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(14f), 0, dpi(8f), 0) }
        ht.addView(text(p.name(lang), 19f, Colors.INK, bold = true))
        if (p.note(lang).isNotBlank()) ht.addView(text(p.note(lang), 12.5f, Colors.MUTED))
        head.addView(ht, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (p.keyUrl.isNotBlank()) head.addView(text(getString(R.string.get_key), 13.5f, Colors.ACCENT, bold = true).apply {
            setPadding(dpi(8f), dpi(8f), dpi(4f), dpi(8f))
            setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl))) } catch (_: Exception) { } }
        })
        detail.addView(head)

        nameEdit = null
        if (p.custom) {
            nameEdit = field(getString(R.string.custom_name), p.nameZh, "")
            detail.addView(label(getString(R.string.custom_protocol)))
            detail.addView(choice(listOf(
                Protocol.CHAT.name to getString(R.string.proto_chat),
                Protocol.RESPONSES.name to getString(R.string.proto_responses),
                Protocol.ANTHROPIC.name to getString(R.string.proto_anthropic),
            ), protocol.name) { protocol = Protocol.valueOf(it) })
        }

        // 密钥
        detail.addView(label(getString(R.string.api_key)))
        val keyRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val ke = input(cfg.key, getString(R.string.api_key_hint)).apply {
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (keyShown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
        }
        keyEdit = ke
        keyRow.addView(ke, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        keyRow.addView(smallButton(getString(R.string.paste)) { pasteInto(ke) })
        keyRow.addView(smallButton(getString(if (keyShown) R.string.hide else R.string.show)) { v ->
            keyShown = !keyShown
            val sel = ke.selectionEnd
            ke.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (keyShown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            ke.typeface = Typeface.MONOSPACE
            ke.setSelection(sel.coerceIn(0, ke.text.length))
            (v as TextView).text = getString(if (keyShown) R.string.hide else R.string.show)
        })
        detail.addView(keyRow)

        // 模型
        detail.addView(label(getString(R.string.model)))
        val modelRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val me = input(cfg.model, p.defaultModel.ifBlank { getString(R.string.model_hint) }).apply { typeface = Typeface.MONOSPACE }
        modelEdit = me
        modelRow.addView(me, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modelRow.addView(smallButton(getString(R.string.pick_model)) { pickModel() })
        detail.addView(modelRow)

        // 推理强度
        if (p.efforts.isNotEmpty()) {
            detail.addView(label(getString(R.string.effort)))
            detail.addView(choice(p.efforts.map { it to effortLabel(it) }, effort) { effort = it; saveDetail() })
        }

        // 接口地址
        detail.addView(label(getString(R.string.base_url)))
        baseEdit = input(prefs.providerValue(p.id, "base").ifBlank { p.baseUrl }, if (p.custom) "https://…/v1" else p.baseUrl).apply {
            textSize = 13.5f; typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        detail.addView(baseEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 按钮
        val buttons = mutableListOf<View>(button(getString(R.string.test)) { runTest() })
        if (prefs.providerId != p.id) buttons.add(button(getString(R.string.use_this), primary = true) { useThis() })
        if (p.custom) buttons.add(button(getString(R.string.delete)) { deleteCustom(p) })
        detail.addView(row(*buttons.toTypedArray()).apply { setPadding(0, dpi(14f), 0, 0) })
        result = text("", 13.5f, Colors.MUTED).apply { setPadding(0, dpi(10f), 0, 0) }
        detail.addView(result)
    }

    private fun effortLabel(v: String) = when (v) {
        "off" -> getString(R.string.think_off)
        "on" -> getString(R.string.think_on)
        else -> v
    }

    /** 把详情卡片里填的存起来。 */
    private fun saveDetail() {
        val p = editing
        val key = keyEdit?.text?.toString()?.trim() ?: return
        prefs.setProviderValue(p.id, "key", key)
        prefs.setProviderValue(p.id, "model", modelEdit?.text?.toString()?.trim().orEmpty().let { if (it == p.defaultModel) "" else it })
        val base = baseEdit?.text?.toString()?.trim()?.trimEnd('/').orEmpty()
        if (p.custom) {
            val name = nameEdit?.text?.toString()?.trim().orEmpty()
            val np = Providers.customProvider(p.id, name, base, protocol)
            Providers.saveCustom(prefs, np)
            editing = np
        } else {
            prefs.setProviderValue(p.id, "base", if (base == p.baseUrl.trimEnd('/')) "" else base)
        }
        if (editing.efforts.isNotEmpty() && effort in editing.efforts) prefs.setProviderValue(p.id, "effort", effort)
        // 正在用的那一家还没密钥、这一家刚填了：直接换过来
        if (key.isNotBlank() && prefs.ai().key.isBlank() && prefs.providerId != p.id) prefs.providerId = p.id
    }

    private fun useThis() {
        saveDetail()
        prefs.providerId = editing.id
        Toast.makeText(this, getString(R.string.using_now, editing.name(lang)), Toast.LENGTH_SHORT).show()
        refreshAll()
    }

    private fun deleteCustom(p: Provider) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_custom_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                keyEdit = null   // 不要再把它存回去
                Providers.deleteCustom(prefs, p.id)
                editing = prefs.provider
                refreshAll()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 选模型 ----------

    private fun pickModel() {
        saveDetail()
        val p = editing
        val cfg = prefs.config(p)
        if (cfg.key.isBlank() || cfg.baseUrl.isBlank()) { showModels(p, emptyList(), getString(R.string.need_key)); return }
        showResult(getString(R.string.models_loading), Colors.MUTED)
        Thread {
            val (models, err) = try { AiClient(cfg).models() to null } catch (e: Exception) { emptyList<ModelInfo>() to (e.message ?: e.javaClass.simpleName) }
            runOnUiThread { if (!isDestroyed && editing.id == p.id) showModels(p, models, err) }
        }.start()
    }

    private fun showModels(p: Provider, models: List<ModelInfo>, err: String?) {
        showResult(if (err != null) getString(R.string.models_failed, err) else "", Colors.MUTED)
        val list = if (models.isNotEmpty()) models.sortedWith(compareBy<ModelInfo>({ rank(it) }, { it.id !in p.models }, { it.id }))
            else p.models.map { ModelInfo(it, true) }
        if (list.isEmpty()) return
        val labels = list.map { m ->
            val tag = when {
                m.vision == true -> getString(R.string.vision_yes)
                m.vision == false -> getString(R.string.vision_no)
                Providers.looksVisual(m.id) -> getString(R.string.vision_maybe)
                else -> ""
            }
            if (tag.isEmpty()) m.id else "${m.id}   · $tag"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_model_title)
            .setItems(labels.toTypedArray()) { _, i ->
                modelEdit?.setText(list[i].id)
                saveDetail(); refreshHero()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rank(m: ModelInfo) = when {
        m.vision == true -> 0
        m.vision == null && Providers.looksVisual(m.id) -> 1
        m.vision == null -> 2
        else -> 3
    }

    // ---------- 测试连接 ----------

    /** 发一张写着 OK 的小图，看它读不读得出来：圈问要能看图的模型。 */
    private fun runTest(after: (() -> Unit)? = null) {
        if (testing) return
        saveDetail()
        val p = editing
        val cfg = prefs.config(p)
        if (cfg.key.isBlank()) { showResult(getString(R.string.need_key), Colors.ACCENT); return }
        if (cfg.baseUrl.isBlank()) { showResult(getString(R.string.need_base), Colors.ACCENT); return }
        testing = true
        showResult(getString(R.string.testing), Colors.MUTED)
        val req = AiRequest(
            "You are a connection test. Reply with only the word you see in the image, nothing else.",
            AiMsg.user(listOf(AiPart.text("What word is written in this image?"), AiPart.image(testImage()))),
            maxTokens = 64,
        )
        val t0 = System.currentTimeMillis()
        Thread {
            val sb = StringBuilder()
            AiClient(cfg).stream(req, object : AiClient.Listener {
                override fun onDelta(text: String) { sb.append(text) }
                override fun onDone(responseId: String?) {
                    val sec = (System.currentTimeMillis() - t0) / 1000.0
                    val reply = sb.toString().trim()
                    runOnUiThread {
                        testing = false
                        if (reply.contains("ok", ignoreCase = true)) {
                            prefs.setProviderValue(p.id, "ok", cfg.model)
                            show(p, getString(R.string.test_vision_ok, cfg.model, sec), GREEN)
                        } else {
                            prefs.setProviderValue(p.id, "ok", "")
                            show(p, getString(R.string.test_no_vision, reply.take(40)), AMBER)
                        }
                        refreshHero(); after?.invoke()
                    }
                }
                override fun onError(message: String) {
                    runOnUiThread {
                        testing = false
                        prefs.setProviderValue(p.id, "ok", "")
                        show(p, explain(message), Colors.ACCENT)
                        refreshHero(); after?.invoke()
                    }
                }
            })
        }.start()
    }

    private fun show(p: Provider, msg: String, color: Int) { if (!isDestroyed && editing.id == p.id) showResult(msg, color) }

    private fun explain(msg: String): String {
        val m = msg.lowercase()
        return when {
            Regex("image|vision|multimodal|modalit|picture|图片|图像|视觉").containsMatchIn(m) &&
                !m.contains("http 401") && !m.contains("http 403") -> getString(R.string.test_vision_err, msg)
            m.startsWith("http 401") || m.startsWith("http 403") || m.contains("api key") || m.contains("apikey") -> getString(R.string.test_err_key, msg)
            m.startsWith("http 402") || m.contains("insufficient") || m.contains("balance") || m.contains("quota") || m.contains("余额") -> getString(R.string.test_err_money, msg)
            m.startsWith("http 404") || m.contains("model") && (m.contains("not") || m.contains("exist")) -> getString(R.string.test_err_404, msg)
            else -> getString(R.string.test_err, msg)
        }
    }

    private fun testImage(): ByteArray {
        val bmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 64f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        c.drawText("OK", 100f, 72f, paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    // ---------- 导入 ----------

    private fun onImport() {
        if (importing) return
        val raw = importEdit.text.toString().trim()
        if (raw.isEmpty()) { setImport(getString(R.string.import_empty), Colors.ACCENT); return }
        saveDetail()
        importing = true
        importBtn.alpha = 0.5f
        setImport(getString(R.string.import_working), Colors.MUTED)
        Thread {
            val res: Any = try { Providers.detect(prefs, raw) } catch (e: Exception) { e }
            runOnUiThread {
                importing = false
                importBtn.alpha = 1f
                if (isDestroyed) return@runOnUiThread
                when (res) {
                    is Providers.Detected -> applyDetected(res)
                    is Providers.DetectError -> setImport(when (res.reason) {
                        Providers.DetectError.Reason.NO_KEY -> getString(R.string.import_no_key)
                        Providers.DetectError.Reason.UNKNOWN_FORMAT -> getString(R.string.import_unknown)
                        Providers.DetectError.Reason.REJECTED -> getString(R.string.import_rejected)
                        Providers.DetectError.Reason.NETWORK -> getString(R.string.import_network, res.detail)
                    }, Colors.ACCENT)
                    else -> setImport(getString(R.string.import_network, (res as Exception).message ?: ""), Colors.ACCENT)
                }
            }
        }.start()
    }

    private fun applyDetected(d: Providers.Detected) {
        d.custom?.let { Providers.saveCustom(prefs, it) }
        val p = d.custom ?: d.provider
        prefs.setProviderValue(p.id, "key", d.key)
        val model = Providers.pickModel(p, prefs.config(p).model, d.models)
        prefs.setProviderValue(p.id, "model", if (model == p.defaultModel) "" else model)
        prefs.providerId = p.id
        editing = p
        keyShown = false
        importEdit.setText("")
        refreshAll()
        if (model.isBlank()) {
            setImport(getString(R.string.import_need_model, p.name(lang)), AMBER)
            return
        }
        setImport(getString(R.string.import_done, p.name(lang), model) + "\n" + getString(R.string.import_testing), GREEN)
        runTest {
            val ok = prefs.providerValue(p.id, "ok") == model
            setImport(getString(R.string.import_done, p.name(lang), model) + "\n" +
                getString(if (ok) R.string.import_tested_ok else R.string.import_tested_bad), if (ok) GREEN else AMBER)
        }
    }

    private fun setImport(msg: String, color: Int) {
        importStatus.text = msg; importStatus.setTextColor(color)
        importStatus.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showResult(msg: String, color: Int) { result?.text = msg; result?.setTextColor(color) }

    private fun pasteInto(e: EditText) {
        val cm = getSystemService(ClipboardManager::class.java)
        val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (t.isNullOrEmpty()) { Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show(); return }
        e.setText(t); e.setSelection(e.text.length)
    }

    // ---------- 界面构件 ----------

    private fun card(fill: Int = Color.WHITE, stroke: Int = 0x14000000) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(18f), dpi(16f), dpi(18f), dpi(16f))
        background = rounded(fill, dp(18f), stroke, dpi(1f))
        elevation = dp(1.5f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(6f); bottomMargin = dpi(6f); marginStart = dpi(4f); marginEnd = dpi(4f)
        }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) paint.isFakeBoldText = true
    }

    private fun label(s: String) = text(s, 12.5f, Colors.MUTED).apply { setPadding(0, dpi(12f), 0, dpi(4f)) }

    private fun input(value: String, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 15f; setSingleLine(); setTextColor(Colors.INK)
        background = rounded(0xFFFAF8F2.toInt(), dp(10f), 0x22000000, dpi(1f))
        setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
    }

    private fun field(title: String, value: String, hint: String): EditText {
        detail.addView(label(title))
        val e = input(value, hint)
        detail.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else Colors.INK)
        setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
        background = if (primary) rounded(Colors.INK, dp(10f)) else rounded(0x0F000000, dp(10f), 0x22000000, dpi(1f))
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: (View) -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Colors.INK)
        setPadding(dpi(12f), dpi(9f), dpi(12f), dpi(9f))
        background = rounded(0x0F000000, dp(10f), 0x22000000, dpi(1f))
        setOnClickListener { onClick(it) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(8f) }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dpi(10f), 0, 0)
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (i > 0) marginStart = dpi(10f) })
        }
    }

    private fun choice(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): LinearLayout {
        val r = row().apply { setPadding(0, 0, 0, 0) }
        val views = mutableListOf<TextView>()
        fun paint(sel: String) = views.forEachIndexed { i, v ->
            val on = options[i].first == sel
            v.setTextColor(if (on) Color.WHITE else Colors.INK)
            v.background = if (on) rounded(Colors.INK, dp(10f)) else rounded(0x0F000000, dp(10f), 0x22000000, dpi(1f))
        }
        options.forEachIndexed { i, (key, label) ->
            val b = button(label) {}
            b.setOnClickListener { paint(key); onPick(key) }
            views.add(b)
            r.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (i > 0) marginStart = dpi(8f) })
        }
        paint(selected)
        return r
    }

    companion object {
        val GREEN = 0xFF2E7D4F.toInt()
        val AMBER = 0xFFB7791F.toInt()

        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, ProviderActivity::class.java))
    }
}
