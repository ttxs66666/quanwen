package app.quanwen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 回答面板：标题栏（档位、AI 理解的题目、‹ › 翻页、×）、正文（WebView 渲染 Markdown + KaTeX）、
 * 底部「再提示一点」「揭晓」。悬浮卡片和 App 内圈题页共用。
 *
 * fill=true 时正文占满剩下的高度（放在页面里）；fill=false 时正文高度随内容变，最多 [maxBodyHeight]（悬浮卡片）。
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class AnswerView(
    private val ctx: Context,
    private val fill: Boolean,
    private val maxBodyHeight: () -> Int,
    private val onClose: () -> Unit,
    private val onResized: () -> Unit = {},
) : LinearLayout(ctx) {

    var current: Qa? = null
        private set
    private var loc: Context = Lang.wrap(ctx)

    val header = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dpi(12f), ctx.dpi(6f), ctx.dpi(4f), ctx.dpi(2f))
    }
    private val chip = TextView(ctx).apply {
        textSize = 12f; setTextColor(Color.WHITE)
        setPadding(ctx.dpi(8f), ctx.dpi(2f), ctx.dpi(8f), ctx.dpi(2f))
    }
    private val title = TextView(ctx).apply {
        textSize = 13f; setTextColor(Colors.MUTED); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        setPadding(ctx.dpi(8f), 0, ctx.dpi(4f), 0)
    }
    private val prevBtn = iconBtn("‹") { step(-1) }
    private val posText = TextView(ctx).apply { textSize = 12f; setTextColor(Colors.MUTED) }
    private val nextBtn = iconBtn("›") { step(1) }
    private val closeBtn = iconBtn("×") { onClose() }
    private val web = WebView(ctx)
    private val moreBtn = footerBtn()
    private val revealBtn = footerBtn()

    private var pageReady = false
    private var renderQueued = false

    init {
        orientation = VERTICAL
        background = rounded(Colors.PAPER, ctx.dp(14f), 0x33000000, ctx.dpi(1f))
        setPadding(ctx.dpi(2f), ctx.dpi(2f), ctx.dpi(2f), ctx.dpi(2f))

        header.addView(chip)
        header.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(prevBtn); header.addView(posText); header.addView(nextBtn); header.addView(closeBtn)
        addView(header)

        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true
        web.isVerticalScrollBarEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { pageReady = true; render() }
        }
        web.loadUrl("file:///android_asset/card.html")
        addView(web, if (fill) LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                     else LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(60f)))

        val footer = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(ctx.dpi(10f), ctx.dpi(4f), ctx.dpi(10f), ctx.dpi(10f))
        }
        footer.addView(moreBtn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ctx.dpi(8f) })
        footer.addView(revealBtn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        moreBtn.setOnClickListener { current?.let { Engine.followUp(it, Turn.KIND_MORE) } }
        revealBtn.setOnClickListener { current?.let { Engine.followUp(it, Turn.KIND_REVEAL) } }
        addView(footer)
        applyStrings()
    }

    /** 语言改了之后调用。 */
    fun applyStrings() {
        loc = Lang.wrap(ctx)
        moreBtn.text = loc.getString(R.string.btn_more)
        revealBtn.text = loc.getString(R.string.btn_reveal)
        renderHeader()
    }

    fun showQa(qa: Qa) { current = qa; render() }

    /** 内容有变化时调用；流式输出很频繁，合并到每 80ms 刷一次。 */
    fun refresh(qa: Qa) {
        if (qa !== current || renderQueued) return
        renderQueued = true
        postDelayed({ renderQueued = false; render() }, 80)
    }

    fun destroy() = web.destroy()

    private fun step(delta: Int) {
        val qa = current ?: return
        val list = Engine.store.session(qa)
        val i = list.indexOfFirst { it.id == qa.id } + delta
        if (i in list.indices) { current = list[i]; render() }
    }

    private fun renderHeader() {
        val qa = current ?: return
        val type = qa.type.ifBlank { if (qa.busy) "…" else "" }
        chip.text = type
        chip.visibility = if (type.isBlank()) View.GONE else View.VISIBLE
        chip.background = rounded(Colors.forType(qa.type), ctx.dp(10f))
        title.text = plainMath(qa.title).ifBlank { if (qa.busy) loc.getString(R.string.reading) else "" }
        val list = Engine.store.session(qa)
        val i = list.indexOfFirst { it.id == qa.id }
        posText.text = if (list.size > 1) "${i + 1}/${list.size}" else ""
        prevBtn.alpha = if (i > 0) 1f else 0.25f
        nextBtn.alpha = if (i in 0 until list.size - 1) 1f else 0.25f
        val canFollow = !qa.busy && qa.lastResponseId != null
        moreBtn.isEnabled = canFollow; revealBtn.isEnabled = canFollow
        moreBtn.alpha = if (canFollow) 1f else 0.4f; revealBtn.alpha = moreBtn.alpha
    }

    private fun render() {
        renderHeader()
        val qa = current ?: return
        if (!pageReady) return
        val turns = JSONArray()
        for (t in qa.turns) {
            turns.put(JSONObject()
                .put("label", when (t.kind) {
                    Turn.KIND_MORE -> loc.getString(R.string.label_more)
                    Turn.KIND_REVEAL -> loc.getString(R.string.label_reveal)
                    else -> ""
                })
                .put("text", t.text)
                .put("state", when (t.state) {
                    Turn.STATE_LOADING -> "loading"; Turn.STATE_STREAMING -> "stream"
                    Turn.STATE_ERROR -> "error"; else -> "done"
                })
                .put("error", t.error ?: ""))
        }
        val payload = JSONObject().put("key", qa.id).put("turns", turns)
            .put("s", JSONObject().put("loading", loc.getString(R.string.thinking)).put("retry", loc.getString(R.string.retry)))
        web.evaluateJavascript("render(${JSONObject.quote(payload.toString())})", null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun height(cssPx: Float) {
            if (fill) return
            post {
                val h = min(maxBodyHeight(), max(ctx.dpi(40f), ctx.dp(cssPx).toInt()))
                val lp = web.layoutParams
                if (lp.height != h) { lp.height = h; web.layoutParams = lp; onResized() }
            }
        }

        @JavascriptInterface
        fun retry(index: Int) { post { current?.let { Engine.retry(it, index) } } }
    }

    private fun iconBtn(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 20f; setTextColor(Colors.INK)
        gravity = Gravity.CENTER
        setPadding(ctx.dpi(9f), 0, ctx.dpi(9f), ctx.dpi(2f))
        setOnClickListener { onClick() }
    }

    private fun footerBtn() = TextView(ctx).apply {
        textSize = 14f; setTextColor(Colors.INK); gravity = Gravity.CENTER
        setPadding(0, ctx.dpi(9f), 0, ctx.dpi(9f))
        background = rounded(0x0F000000, ctx.dp(10f), 0x22000000, ctx.dpi(1f))
    }
}
