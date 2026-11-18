package app.quanwen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模式记号：圈完在圈外手写 1–3 个英文字母，这一次就按它的提示词回答。
 * 记号其实就是一段提前写好的提示词：AI 认出记号，就照这段话答（[Prompts.markText]）。
 * 默认两个：gg 根源、j 解题。用户可以改、删、加，也可以让 AI 把草稿改写好（[MarksActivity]）。
 */
class MarkMode(val letters: String, val name: String, val prompt: String)

object Marks {
    const val MAX_LETTERS = 3
    const val MAX_NAME = 8
    const val MAX_PROMPT = 1200

    fun defaults(lang: String): List<MarkMode> = if (lang == "en") listOf(
        MarkMode("gg", "Roots", ROOTS_EN), MarkMode("j", "Solve", SOLVE_EN),
    ) else listOf(
        MarkMode("gg", "根源", ROOTS_ZH), MarkMode("j", "解题", SOLVE_ZH),
    )

    /** 当前的记号。没改过就是默认的两个（跟着界面语言）。 */
    fun load(prefs: Prefs): List<MarkMode> {
        val raw = prefs.marksJson ?: return defaults(prefs.lang)
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }
                .map { MarkMode(it.optString("letters"), it.optString("name"), it.optString("prompt")) }
                .filter { validLetters(it.letters) && it.prompt.isNotBlank() }
        } catch (_: Exception) { defaults(prefs.lang) }
    }

    fun save(prefs: Prefs, list: List<MarkMode>) {
        val a = JSONArray()
        for (m in list) a.put(JSONObject().put("letters", m.letters).put("name", m.name).put("prompt", m.prompt))
        prefs.marksJson = a.toString()
    }

    fun reset(prefs: Prefs) { prefs.marksJson = null }

    fun validLetters(s: String) = s.length in 1..MAX_LETTERS && s.all { it in 'a'..'z' }

    /** 「gg 根源、j 解题」；没有记号时为空。 */
    fun summary(prefs: Prefs): String =
        load(prefs).joinToString(if (prefs.lang == "en") ", " else "、") { it.letters + " " + it.name }

    /** 提示文字里的那一截：「；圈完可以写记号换模式（gg 根源、j 解题）」。没有记号时为空。 */
    fun hint(ctx: Context): String {
        val s = summary(Prefs(ctx))
        return if (s.isEmpty()) "" else Lang.wrap(ctx).getString(R.string.marks_hint, s)
    }

    private const val ROOTS_ZH = "从这个知识最根本的地方讲起：它要解决什么问题、建立在哪些更基本的概念上、为什么会是这样，一层层讲回到他圈的内容。重在讲清思想和来龙去脉，不堆细节，正文不超过 10 句（公式另算）。"
    private const val SOLVE_ZH = "把这道题完整解出来并讲懂：先用一句话说思路，再用编号步骤解，关键步骤说明为什么这样做，最后写出答案。"
    private const val ROOTS_EN = "Start from the deepest root of this knowledge: what problem it solves, which more basic ideas it rests on, why it is the way it is, and work back up to what they circled. Focus on the idea and where it comes from, not on details; at most 10 sentences of body text (formulas aside)."
    private const val SOLVE_EN = "Solve the problem completely and make it understood: one sentence on the approach, then numbered steps, saying why at the key steps, and finally the answer."
}

/** 设置模式记号：每个记号一张卡片（字母、名称、提示词、AI 优化、删除）。离开页面时保存。 */
class MarksActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private lateinit var list: LinearLayout
    private val cards = ArrayList<Card>()

    private class Card(
        val view: View, val letters: EditText, val name: EditText, val prompt: EditText,
        val error: TextView, val optimize: TextView, val restore: TextView,
    ) {
        /** 「AI 优化」之前的提示词，「还原」用。 */
        var before: String? = null
        /** 正在优化：框里是还没写完的新提示词，保存时用 [before]。 */
        var busy = false
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = Colors.PAPER
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val scroll = ScrollView(this).apply { setBackgroundColor(Colors.PAPER); isFillViewport = true }
        val outer = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(24f), dpi(32f), dpi(24f), dpi(48f)) }
        outer.addView(col, LinearLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dpi(720f)), ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.addView(outer)
        setContentView(imeAware(scroll))

        col.addView(text(getString(R.string.marks_title), 26f, Colors.INK, bold = true))
        col.addView(text(getString(R.string.marks_help), 14f, Colors.MUTED).apply { setPadding(0, dpi(8f), 0, dpi(4f)); setLineSpacing(0f, 1.25f) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        for (m in Marks.load(prefs)) addCard(m)
        col.addView(row(
            button(getString(R.string.marks_add)) { addCard(null).letters.requestFocus(); validate() },
            button(getString(R.string.marks_reset)) { confirmReset() },
        ).apply { setPadding(0, dpi(18f), 0, 0) })
        validate()
    }

    override fun onPause() { super.onPause(); save() }

    // ---------- 卡片 ----------

    private fun addCard(m: MarkMode?): Card {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0x0A000000, dp(14f), 0x1F000000, dpi(1f))
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(12f))
        }
        val letters = EditText(this).apply {
            setText(m?.letters ?: ""); hint = "gg"; textSize = 18f; setSingleLine(); setTextColor(Colors.INK)
            paint.isFakeBoldText = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(LETTERS_ONLY, InputFilter.LengthFilter(Marks.MAX_LETTERS))
        }
        val name = EditText(this).apply {
            setText(m?.name ?: ""); hint = getString(R.string.marks_name_hint); textSize = 16f; setSingleLine(); setTextColor(Colors.INK)
            filters = arrayOf(InputFilter.LengthFilter(Marks.MAX_NAME))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(getString(R.string.marks_letters), 13f, Colors.MUTED))
        top.addView(letters, LinearLayout.LayoutParams(dpi(96f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(8f) })
        top.addView(text(getString(R.string.marks_name), 13f, Colors.MUTED).apply { setPadding(dpi(16f), 0, 0, 0) })
        top.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(8f) })
        box.addView(top)

        box.addView(text(getString(R.string.marks_prompt), 13f, Colors.MUTED).apply { setPadding(0, dpi(8f), 0, 0) })
        val prompt = EditText(this).apply {
            setText(m?.prompt ?: ""); hint = getString(R.string.marks_prompt_hint); textSize = 15f; setTextColor(Colors.INK)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 14; gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(Marks.MAX_PROMPT))
        }
        box.addView(prompt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val error = text("", 13f, Colors.ACCENT).apply { visibility = View.GONE }
        box.addView(error)

        val optimize = button(getString(R.string.marks_optimize)) {}
        val restore = button(getString(R.string.marks_restore)) {}.apply { visibility = View.GONE }
        val delete = button(getString(R.string.delete)) {}
        box.addView(row(optimize, restore, delete))

        val card = Card(box, letters, name, prompt, error, optimize, restore)
        optimize.setOnClickListener { optimize(card) }
        restore.setOnClickListener {
            card.before?.let { prompt.setText(it) }
            card.before = null; restore.visibility = View.GONE
        }
        delete.setOnClickListener {
            if (card.busy) return@setOnClickListener
            list.removeView(box); cards.remove(card); validate()
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!card.busy) validate() }
        }
        letters.addTextChangedListener(watcher)
        prompt.addTextChangedListener(watcher)
        list.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(14f) })
        cards.add(card)
        return card
    }

    /** 每张卡片下面标出没填好的地方：字母空着、和别的重复、提示词空着。 */
    private fun validate() {
        val seen = HashSet<String>()
        for (c in cards) {
            val l = c.letters.text.toString()
            val msg = when {
                !Marks.validLetters(l) -> getString(R.string.marks_err_letters)
                !seen.add(l) -> getString(R.string.marks_err_dup, l)
                c.prompt.text.isBlank() && !c.busy -> getString(R.string.marks_err_prompt)
                else -> null
            }
            c.error.text = msg ?: ""
            c.error.visibility = if (msg == null) View.GONE else View.VISIBLE
        }
    }

    /** 存下填好了的记号；没填完整或字母重复的跳过，并提示一句。 */
    private fun save() {
        val out = ArrayList<MarkMode>()
        val seen = HashSet<String>()
        var skipped = 0
        for (c in cards) {
            val l = c.letters.text.toString()
            val p = (if (c.busy) c.before ?: "" else c.prompt.text.toString()).trim()
            if (!Marks.validLetters(l) || p.isEmpty() || !seen.add(l)) { skipped++; continue }
            out.add(MarkMode(l, c.name.text.toString().trim().ifBlank { l.uppercase() }, p))
        }
        // 没改过就不存：默认的记号继续跟着界面语言
        val defaults = Marks.defaults(prefs.lang)
        val same = out.size == defaults.size && out.indices.all {
            out[it].letters == defaults[it].letters && out[it].name == defaults[it].name && out[it].prompt == defaults[it].prompt
        }
        if (!(prefs.marksJson == null && same && skipped == 0)) Marks.save(prefs, out)
        if (skipped > 0) Toast.makeText(this, getString(R.string.marks_skipped, skipped), Toast.LENGTH_LONG).show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this).setMessage(R.string.marks_reset_confirm)
            .setPositiveButton(R.string.marks_reset) { _, _ ->
                if (cards.any { it.busy }) return@setPositiveButton
                Marks.reset(prefs)
                cards.clear(); list.removeAllViews()
                for (m in Marks.defaults(prefs.lang)) addCard(m)
                validate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- AI 优化 ----------

    /** 把草稿发给模型改写成适合圈问的模式提示词，边写边显示；不满意可以「还原」。 */
    private fun optimize(card: Card) {
        if (card.busy) return
        if (prefs.apiKey.isBlank()) { Toast.makeText(this, R.string.no_key, Toast.LENGTH_LONG).show(); return }
        val draft = card.prompt.text.toString().trim()
        val name = card.name.text.toString().trim()
        if (draft.isEmpty() && name.isEmpty()) { Toast.makeText(this, R.string.marks_need_draft, Toast.LENGTH_LONG).show(); return }
        val old = card.prompt.text.toString()
        card.busy = true
        card.before = old
        card.optimize.text = getString(R.string.marks_optimizing); card.optimize.isEnabled = false
        card.restore.visibility = View.GONE
        card.prompt.isEnabled = false
        val (instructions, input) = Prompts.optimizeMark(prefs.lang, card.letters.text.toString(), name, draft)
        val req = AiRequest(instructions, AiMsg.user(input), maxTokens = 1024)
        Thread {
            val sb = StringBuilder()
            AiClient(prefs.ai()).stream(req, object : AiClient.Listener {
                override fun onDelta(text: String) {
                    sb.append(text)
                    val s = sb.toString()
                    runOnUiThread { if (!isDestroyed && card.busy) card.prompt.setText(s) }
                }
                override fun onDone(responseId: String?) {
                    val s = Prompts.cleanOptimized(sb.toString())
                    runOnUiThread { finishOptimize(card, old, s, null) }
                }
                override fun onError(message: String) { runOnUiThread { finishOptimize(card, old, null, message) } }
            })
        }.start()
    }

    private fun finishOptimize(card: Card, old: String, result: String?, error: String?) {
        if (isDestroyed) return
        card.busy = false
        card.optimize.isEnabled = true; card.optimize.text = getString(R.string.marks_optimize)
        card.prompt.isEnabled = true
        if (result.isNullOrBlank()) {
            card.prompt.setText(old); card.before = null
            Toast.makeText(this, getString(R.string.marks_optimize_failed) + " " + (error ?: ""), Toast.LENGTH_LONG).show()
        } else {
            card.prompt.setText(result)
            card.before = old
            card.restore.visibility = View.VISIBLE
        }
        validate()
    }

    // ---------- 简单的界面构件 ----------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) paint.isFakeBoldText = true
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(Colors.INK); gravity = Gravity.CENTER
        setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
        background = rounded(0x0F000000, dp(10f), 0x22000000, dpi(1f))
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dpi(8f), 0, 0)
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (i > 0) marginStart = dpi(10f) })
        }
    }

    companion object {
        /** 字母框只收英文字母，统一成小写。 */
        private val LETTERS_ONLY = InputFilter { source, start, end, _, _, _ ->
            val s = source.subSequence(start, end).toString()
            val out = s.filter { it in 'a'..'z' || it in 'A'..'Z' }.lowercase()
            if (out == s) null else out
        }
    }
}
