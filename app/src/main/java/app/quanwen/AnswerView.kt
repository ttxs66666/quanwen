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
    private val menuBtn = iconBtn("☰") { if (sessionsShown) hideSessions() else showSessions() }
    private val closeBtn = iconBtn("×") { onClose() }
    private val web = WebView(ctx)
    private val footer = LinearLayout(ctx)
    /** 会话列表（点 ☰ 打开），盖在正文的位置上。 */
    private val sessionScroll = android.widget.ScrollView(ctx).apply { visibility = View.GONE }
    private val sessionList = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.dpi(8f), ctx.dpi(4f), ctx.dpi(8f), ctx.dpi(8f)) }
    private var sessionsShown = false
    /** 当前是不是「新会话、还没有题」的空白状态。 */
    var emptyShown = false
        private set
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
        header.addView(prevBtn); header.addView(posText); header.addView(nextBtn); header.addView(menuBtn); header.addView(closeBtn)
        addView(header)
        sessionScroll.addView(sessionList)
        addView(sessionScroll, if (fill) LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                               else LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(200f)))

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

        footer.orientation = HORIZONTAL
        footer.setPadding(ctx.dpi(10f), ctx.dpi(4f), ctx.dpi(10f), ctx.dpi(10f))
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

    fun showQa(qa: Qa) {
        if (sessionsShown) hideSessions()
        current = qa; emptyShown = false; render()
    }

    /** 新会话、还没有题时显示的空白状态（仍然可以点 ☰ 切回别的会话）。 */
    fun showEmpty() {
        if (sessionsShown) hideSessions()
        current = null; emptyShown = true; render()
    }

    // ---------- 会话列表 ----------

    fun showSessions() {
        sessionsShown = true
        sessionList.removeAllViews()
        sessionList.addView(sessionRow(loc.getString(R.string.session_new), "", Colors.ACCENT, bold = true) {
            Engine.store.newSession(); showEmpty()
        })
        val cur = Engine.store.currentSessionId
        val fmt = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.getDefault())
        for (sn in Engine.store.sessions().take(30)) {
            val isCur = sn.id == cur
            val name = plainMath(sn.title).ifBlank { loc.getString(R.string.session_untitled) }
            val meta = listOf(
                if (isCur) loc.getString(R.string.session_current) else "",
                loc.getString(R.string.session_count, sn.qas.size),
                sn.subject,
                fmt.format(java.util.Date(sn.lastTime)),
            ).filter { it.isNotBlank() }.joinToString(" · ")
            sessionList.addView(sessionRow(name, meta, if (isCur) Colors.INK else Colors.MUTED, bold = isCur) {
                Engine.store.switchSession(sn.id)
                val last = sn.qas.lastOrNull()
                if (last != null) showQa(last) else showEmpty()
            })
        }
        web.visibility = View.GONE; footer.visibility = View.GONE
        if (!fill) {
            val lp = sessionScroll.layoutParams
            lp.height = minOf(maxBodyHeight(), ctx.dpi(58f) * sessionList.childCount + ctx.dpi(16f))
            sessionScroll.layoutParams = lp
        }
        sessionScroll.visibility = View.VISIBLE
        sessionScroll.scrollTo(0, 0)
        renderHeader()
        onResized()
    }

    private fun hideSessions() {
        sessionsShown = false
        sessionScroll.visibility = View.GONE
        web.visibility = View.VISIBLE; footer.visibility = View.VISIBLE
        render()
        onResized()
    }

    private fun sessionRow(name: String, meta: String, color: Int, bold: Boolean, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = VERTICAL
        setPadding(ctx.dpi(10f), ctx.dpi(8f), ctx.dpi(10f), ctx.dpi(8f))
        background = rounded(0x08000000, ctx.dp(10f))
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(6f) }
        addView(TextView(ctx).apply {
            text = name; textSize = 15f; setTextColor(color); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            if (bold) paint.isFakeBoldText = true
        })
        if (meta.isNotBlank()) addView(TextView(ctx).apply { text = meta; textSize = 12f; setTextColor(Colors.MUTED) })
        setOnClickListener { onClick() }
    }

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
        if (sessionsShown || current == null) {
            chip.visibility = View.GONE
            prevBtn.visibility = View.GONE; nextBtn.visibility = View.GONE; posText.visibility = View.GONE
            title.text = loc.getString(if (sessionsShown) R.string.sessions else R.string.session_untitled)
            val can = false
            moreBtn.isEnabled = can; revealBtn.isEnabled = can; moreBtn.alpha = 0.4f; revealBtn.alpha = 0.4f
            return
        }
        prevBtn.visibility = View.VISIBLE; nextBtn.visibility = View.VISIBLE; posText.visibility = View.VISIBLE
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
        if (!pageReady) return
        val qa = current
        if (qa == null) {
            if (emptyShown) {
                val payload = JSONObject().put("key", "empty").put("turns", JSONArray()).put("empty", loc.getString(R.string.session_empty))
                    .put("s", JSONObject().put("loading", "").put("retry", ""))
                web.evaluateJavascript("render(${JSONObject.quote(payload.toString())})", null)
            }
            return
        }
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
