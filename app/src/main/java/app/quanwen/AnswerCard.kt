package app.quanwen

import android.annotation.SuppressLint
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
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
 * 浮动回答卡片。关掉只是隐藏，点悬浮条上的「卡」再拿出来。
 * 正文在 WebView 里渲染（Markdown + KaTeX 公式）。
 */
@SuppressLint("SetJavaScriptEnabled")
class AnswerCard(private val svc: QuanwenService) {
    var current: Qa? = null
        private set
    var visible = false
        private set
    private var attached = false
    private var visibleBeforeCapture = false

    private val screen get() = svc.screenSize()
    private val cardW: Int get() = screen.x.let { w -> max(svc.dpi(300f), min(svc.dpi(460f), (w * 0.6f).toInt())) }
    private val maxBodyH: Int get() = (screen.y * 0.5f).toInt()

    private val root = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Colors.PAPER, svc.dp(14f), 0x33000000, svc.dpi(1f))
        setPadding(svc.dpi(2f), svc.dpi(2f), svc.dpi(2f), svc.dpi(2f))
    }
    private val chip = TextView(svc).apply {
        textSize = 12f; setTextColor(Color.WHITE)
        setPadding(svc.dpi(8f), svc.dpi(2f), svc.dpi(8f), svc.dpi(2f))
    }
    private val title = TextView(svc).apply {
        textSize = 13f; setTextColor(Colors.MUTED); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        setPadding(svc.dpi(8f), 0, svc.dpi(4f), 0)
    }
    private val prevBtn = iconBtn("‹") { step(-1) }
    private val posText = TextView(svc).apply { textSize = 12f; setTextColor(Colors.MUTED) }
    private val nextBtn = iconBtn("›") { step(1) }
    private val closeBtn = iconBtn("×") { hide() }
    private val web = WebView(svc)
    private val moreBtn = footerBtn()
    private val revealBtn = footerBtn()

    private var pageReady = false
    private var renderQueued = false
    private val params = overlayParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, focusable = false)

    init {
        val header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(svc.dpi(12f), svc.dpi(6f), svc.dpi(4f), svc.dpi(2f))
        }
        header.addView(chip)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(prevBtn); header.addView(posText); header.addView(nextBtn); header.addView(closeBtn)
        attachDrag(header)
        root.addView(header)

        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true
        web.isVerticalScrollBarEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { pageReady = true; render() }
        }
        web.loadUrl("file:///android_asset/card.html")
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, svc.dpi(60f)))

        val footer = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(svc.dpi(10f), svc.dpi(4f), svc.dpi(10f), svc.dpi(10f))
        }
        footer.addView(moreBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = svc.dpi(8f) })
        footer.addView(revealBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        moreBtn.setOnClickListener { current?.let { svc.followUp(it, Turn.KIND_MORE) } }
        revealBtn.setOnClickListener { current?.let { svc.followUp(it, Turn.KIND_REVEAL) } }
        root.addView(footer)
        applyStrings()
    }

    fun applyStrings() {
        moreBtn.text = svc.str(R.string.btn_more)
        revealBtn.text = svc.str(R.string.btn_reveal)
        renderHeader()
    }

    /** 显示某道题。place=true 时把卡片挪到圈的旁边。 */
    fun show(qa: Qa, place: Boolean) {
        current = qa
        params.width = cardW
        if (place) placeNear(qa.bounds)
        if (!attached) { svc.wm.addView(root, params); attached = true } else svc.wm.updateViewLayout(root, params)
        root.visibility = View.VISIBLE
        visible = true
        render()
    }

    fun hide() { root.visibility = View.GONE; visible = false }

    fun hideForCapture() { visibleBeforeCapture = visible; if (attached) root.visibility = View.GONE }
    fun restoreAfterCapture() { if (visibleBeforeCapture && attached) { root.visibility = View.VISIBLE; visible = true } }

    fun remove() {
        if (attached) try { svc.wm.removeView(root) } catch (_: Exception) { }
        attached = false; visible = false
        web.destroy()
    }

    /** 内容有变化时调用；流式输出很频繁，合并到每 80ms 刷一次。 */
    fun refresh(qa: Qa) {
        if (qa !== current) return
        if (renderQueued) return
        renderQueued = true
        root.postDelayed({ renderQueued = false; render() }, 80)
    }

    private fun step(delta: Int) {
        val qa = current ?: return
        val list = svc.store.session(qa)
        val i = list.indexOfFirst { it.id == qa.id } + delta
        if (i in list.indices) { current = list[i]; render() }
    }

    private fun renderHeader() {
        val qa = current ?: return
        val type = qa.type.ifBlank { if (qa.busy) "…" else "" }
        chip.text = type
        chip.visibility = if (type.isBlank()) View.GONE else View.VISIBLE
        chip.background = rounded(Colors.forType(qa.type), svc.dp(10f))
        title.text = plainMath(qa.title).ifBlank { if (qa.busy) svc.str(R.string.reading) else "" }
        val list = svc.store.session(qa)
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
                    Turn.KIND_MORE -> svc.str(R.string.label_more)
                    Turn.KIND_REVEAL -> svc.str(R.string.label_reveal)
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
            .put("s", JSONObject().put("loading", svc.str(R.string.thinking)).put("retry", svc.str(R.string.retry)))
        web.evaluateJavascript("render(${JSONObject.quote(payload.toString())})", null)
    }

    /** 先放圈的右边，再左边，都放不下就放圈的下方或上方；不盖住悬浮条。 */
    private fun placeNear(b: FloatArray) {
        val s = screen
        val gap = svc.dpi(16f)
        val w = cardW
        var minX = gap
        var maxX = s.x - gap
        svc.barRect()?.let { r -> if (r.centerX() > s.x / 2) maxX = min(maxX, r.left - gap) else minX = max(minX, r.right + gap) }
        val estH = svc.dpi(220f)
        val top = svc.dpi(24f)
        when {
            b[2] + gap + w <= maxX -> { params.x = (b[2] + gap).toInt(); params.y = b[1].toInt() }
            b[0] - gap - w >= minX -> { params.x = (b[0] - gap - w).toInt(); params.y = b[1].toInt() }
            else -> {
                params.x = b[0].toInt().coerceIn(minX, max(minX, maxX - w))
                params.y = if (b[3] + gap + estH <= s.y) (b[3] + gap).toInt() else max(top, (b[1] - gap - estH).toInt())
            }
        }
        params.y = params.y.coerceIn(top, max(top, s.y - svc.dpi(200f)))
    }

    /** 内容高度变了以后，保证卡片底部不出屏幕。 */
    private fun keepOnScreen() {
        if (!attached) return
        root.post {
            val s = screen
            val bottom = params.y + root.height
            if (bottom > s.y - svc.dpi(8f)) {
                params.y = max(0, s.y - svc.dpi(8f) - root.height)
                svc.wm.updateViewLayout(root, params)
            }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun height(cssPx: Float) {
            root.post {
                val h = min(maxBodyH, max(svc.dpi(40f), svc.dp(cssPx).toInt()))
                val lp = web.layoutParams
                if (lp.height != h) { lp.height = h; web.layoutParams = lp; keepOnScreen() }
            }
        }

        @JavascriptInterface
        fun retry(index: Int) { root.post { current?.let { svc.retry(it, index) } } }
    }

    private fun iconBtn(label: String, onClick: () -> Unit) = TextView(svc).apply {
        text = label; textSize = 20f; setTextColor(Colors.INK)
        gravity = Gravity.CENTER
        setPadding(svc.dpi(9f), 0, svc.dpi(9f), svc.dpi(2f))
        setOnClickListener { onClick() }
    }

    private fun footerBtn() = TextView(svc).apply {
        textSize = 14f; setTextColor(Colors.INK); gravity = Gravity.CENTER
        setPadding(0, svc.dpi(9f), 0, svc.dpi(9f))
        background = rounded(0x0F000000, svc.dp(10f), 0x22000000, svc.dpi(1f))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(handle: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    val s = screen
                    params.x = (startX + e.rawX - downX).toInt().coerceIn(-params.width / 2, s.x - params.width / 2)
                    params.y = (startY + e.rawY - downY).toInt().coerceIn(0, s.y - svc.dpi(60f))
                    svc.wm.updateViewLayout(root, params)
                }
            }
            true
        }
    }
}
