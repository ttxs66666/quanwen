package app.quanwen

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 「一同书写」（测试功能）：在笔记本里写下问号就是在问 AI，AI 把回答用字直接写在纸上，接在问题后面。
 *
 *  - 认问号、句号：[Punct]，只看笔画的形状和位置，不联网。
 *  - 问题是哪一句：上一个分界（上一个问号、句号）之后新写的笔画里，问号前面连着的那一块（[Punct.questionBlock]）。
 *  - 排版：字的大小、行首、行距照着他写的那一行来（[InkPlan]）；一个字一个字地写上去，碰到纸上已有的东西就换行或跳过（[InkWriter]）。
 *  - 问题不明确时 AI 另起一行写「询问：……」（琥珀色）。他在纸上写下回答、以句号结尾，询问化成粒子飘散，AI 重新回答。
 */
object AiInk {
    const val STANDARD = 0
    /** 第 17 版的龙藏体（朋友嫌丑，第 18 版去掉了）。这样写下的旧字改用宋体显示，横向压到原来的宽度，免得挤在一起。 */
    const val LEGACY_HAND = 1
    /** 宋体（思源宋体 Noto Serif SC，只留了 GB2312 的字和常用符号）。 */
    const val SONG = 2
    /** AI 的字：墨绿色，和他的黑红蓝三支笔分开。 */
    const val INK = 0xFF1D6B50.toInt()
    /** AI 在问他：琥珀色。 */
    const val ASK = 0xFFB0700A.toInt()

    private var song: Typeface? = null

    fun typeface(ctx: Context, font: Int): Typeface {
        if (font == STANDARD) return Typeface.DEFAULT
        song?.let { return it }
        val t = try { Typeface.createFromAsset(ctx.assets, "fonts/NotoSerifSC-Regular-sub.ttf") } catch (_: Exception) { Typeface.SERIF }
        song = t
        return t
    }

    /** 汉字从基线往上占字号的多少、往下占多少（量过字体：思源宋体「中」在 -0.08..0.84，系统黑体约 -0.07..0.81）。 */
    fun top(font: Int) = if (font == STANDARD) 0.84f else 0.86f
    fun bottom(font: Int) = if (font == STANDARD) 0.09f else 0.10f
    /** 汉字的高度是字号的多少倍。 */
    fun height(font: Int) = top(font) + bottom(font) - 0.06f
}

/** 认手写的问号、句号。坐标都是页面坐标。 */
object Punct {
    class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w get() = r - l
        val h get() = b - t
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
        fun rect() = RectF(l, t, r, b)
    }

    fun box(p: FloatArray): Box {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < p.size) { l = min(l, p[i]); r = max(r, p[i]); t = min(t, p[i + 1]); b = max(b, p[i + 1]); i += 2 }
        return Box(l, t, r, b)
    }

    /**
     * 问号 = 一笔钩 + 下面一个点。钩：从上面起笔，先往右弯出去（最右的地方在上半截），再收回来往下；
     * 点：很小，在钩的收笔下面、隔开一点。这样「7.」「2.」「!」「)」都不会被当成问号。
     */
    fun isQuestion(hook: FloatArray, dot: FloatArray): Boolean {
        if (hook.size < 8 || dot.isEmpty()) return false
        val h = box(hook)
        if (h.h < 6f) return false
        val d = box(dot)
        if (max(d.w, d.h) > max(4f, h.h * 0.3f)) return false   // 点要小
        val n = hook.size / 2
        val sy = hook[1]; val ex = hook[2 * n - 2]; val ey = hook[2 * n - 1]
        if (sy > h.t + 0.5f * h.h) return false   // 从上面起笔
        if (ey < h.t + 0.55f * h.h) return false  // 往下收笔
        if (h.w < 0.2f * h.h || h.w > 1.4f * h.h) return false
        var maxX = -Float.MAX_VALUE; var maxI = 0
        for (k in 0 until n) if (hook[2 * k] > maxX) { maxX = hook[2 * k]; maxI = k }
        if (maxI > n * 0.8f) return false                                  // 最右的地方不在收笔处
        if (hook[2 * maxI + 1] > h.t + 0.65f * h.h) return false           // 弯在上半截
        if (maxX - ex < 0.22f * max(h.w, h.h * 0.3f)) return false          // 收回来了
        if (d.t < ey - 0.02f * h.h) return false                           // 点在收笔下面，隔开
        if (d.cy > h.b + 0.9f * h.h) return false                          // 也不会太远
        if (abs(d.cx - ex) > 0.5f * h.h) return false
        return true
    }

    /**
     * 句号「。」：一个小圈。比旁边的字小得多（不到字高的 55%），差不多是圆的，首尾接上，绕了一圈，在这一行的下半部分。
     * ref：旁边几笔的范围（同一行最近写的笔画），没有时为 null。
     */
    fun isPeriod(p: FloatArray, ref: Box?): Boolean {
        if (p.size < 10) return false
        val b = box(p)
        val size = max(b.w, b.h)
        if (size < 2.5f) return false
        val lineH = ref?.h ?: 0f
        if (lineH > 0f) { if (size > lineH * 0.55f) return false } else if (size > 18f) return false
        if (b.w < 0.45f * b.h || b.h < 0.45f * b.w) return false
        val n = p.size / 2
        if (hypot(p[0] - p[2 * n - 2], p[1] - p[2 * n - 1]) > 0.5f * size) return false
        var len = 0f
        for (k in 1 until n) len += hypot(p[2 * k] - p[2 * k - 2], p[2 * k + 1] - p[2 * k - 1])
        if (len < 2.2f * size) return false
        if (ref != null && lineH > 0f && b.cy < ref.t + 0.45f * lineH) return false   // 在这一行的下半部分
        return true
    }

    /** 句号旁边同一行最近写的几笔的范围（判断句号大小、位置用）。 */
    fun lineRef(recent: List<Stroke>, s: Stroke): Box? {
        val b = box(s.pts)
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var bb = -Float.MAX_VALUE
        var any = false
        for (o in recent) {
            if (o === s) continue
            val ob = o.box
            val h = ob[3] - ob[1]
            if (h < 3f) continue
            // 竖着和句号所在的位置有重叠，或者就在它上面一点
            if (ob[3] < b.t - h * 0.6f || ob[1] > b.b + 2f) continue
            if (abs((ob[0] + ob[2]) / 2 - b.cx) > h * 8f) continue
            l = min(l, ob[0]); t = min(t, ob[1]); r = max(r, ob[2]); bb = max(bb, ob[3]); any = true
        }
        return if (any) Box(l, t, r, bb) else null
    }

    /**
     * 问号前面的那句话：从问号往前按写的先后倒着找，挨着的就算进来（同一行，或者紧挨着的上一行），
     * 碰到隔得远的就停（那是更早写在别处的东西）。cands 按写的先后排好，最后两笔是问号。
     */
    fun questionBlock(cands: List<Stroke>, q: Box): List<Stroke> {
        val lineH = max(q.h, 8f)
        var l = q.l; var t = q.t; var r = q.r; var b = q.b
        val out = ArrayList<Stroke>()
        for (i in cands.indices.reversed()) {
            val s = cands[i]
            val sb = s.box
            val near = sb[1] <= b + 0.9f * lineH && sb[3] >= t - 0.9f * lineH && sb[0] <= r + 3f * lineH
            if (!near && out.size >= 2) break
            if (!near) continue
            out.add(s)
            l = min(l, sb[0]); t = min(t, sb[1]); r = max(r, sb[2]); b = max(b, sb[3])
        }
        out.reverse()
        return out
    }
}

/**
 * AI 的回答怎么排：第 page 页，字号 size，行距 pitch；第一行从 firstX 写起（问号后面），基线 firstBaseline；
 * 换行后从 leftX 写起（他这一行的行首），写到 rightX 为止。都是页面坐标。
 */
class InkPlan(
    val page: Int, val size: Float, val pitch: Float,
    val leftX: Float, val firstX: Float, val firstBaseline: Float, val rightX: Float,
) {
    fun toJson(pageId: String) = JSONObject().put("page", pageId).put("size", size.toDouble()).put("pitch", pitch.toDouble())
        .put("left", leftX.toDouble()).put("first", firstX.toDouble()).put("base", firstBaseline.toDouble()).put("right", rightX.toDouble())

    /** 告诉 AI 大概能写多宽：问号后面这一行还能写几个字，往下每行几个字。 */
    fun firstChars() = ((rightX - firstX) / size).toInt().coerceAtLeast(0)
    fun lineChars() = ((rightX - leftX) / size).toInt().coerceAtLeast(4)

    companion object {
        const val RIGHT_MARGIN = 24f
        /** AI 的字是他手写字的几分之几大。 */
        const val SIZE_RATIO = 0.5f

        fun fromJson(o: JSONObject, page: Int) = InkPlan(page, o.getDouble("size").toFloat(), o.getDouble("pitch").toFloat(),
            o.getDouble("left").toFloat(), o.getDouble("first").toFloat(), o.getDouble("base").toFloat(), o.getDouble("right").toFloat())

        /**
         * 照着他写的那一行排：他的字高 = 问号所在这一行笔画的高度（夹在问号高度的 0.85–1.7 倍之间），
         * AI 的字只有他的一半大（朋友试过，和手写一样大显得太大）；字的底边和他这一行的底边对齐。
         * 行首 = 从问号往左连着的同一行笔画的最左边；AI 自己的几行之间按它的字号排得紧凑一些（1.6 倍字号）。
         */
        fun plan(page: NotePage, pi: Int, block: List<Stroke>, q: Punct.Box, font: Int): InkPlan {
            val qH = max(q.h, 8f)
            val bandTop = q.t - 0.45f * qH; val bandBottom = q.b + 0.15f * qH
            val line = page.strokes.filter {
                val b = it.box; val cy = (b[1] + b[3]) / 2
                cy in bandTop..bandBottom && b[3] - b[1] < 2.2f * qH && b[0] < q.r
            }
            var top = q.t; var bottom = q.b
            for (s in line) { top = min(top, s.box[1]); bottom = max(bottom, s.box[3]) }
            val charH = (bottom - top).coerceIn(qH * 0.85f, qH * 1.7f)
            val size = (charH / AiInk.height(font) * 0.95f * SIZE_RATIO).coerceIn(7f, 60f)
            val baseline = bottom - AiInk.bottom(font) * size * 0.8f
            // 行首：从问号往左，隔得不远的同一行笔画
            var left = q.l
            for (s in line.sortedByDescending { it.box[2] }) {
                if (s.box[2] > q.r) continue
                if (s.box[2] >= left - 2.5f * charH) left = min(left, s.box[0])
            }
            return InkPlan(pi, size, 1.6f * size, left, q.r + 0.3f * charH, baseline, NotePaper.PAGE_W - RIGHT_MARGIN)
        }
    }
}

/**
 * 把 AI 的回答一个字一个字写到纸上。回答边收边写（每个字间隔几十毫秒，看起来像在写）。
 * 汉字一个字一个单位，英文一个词一个单位（不在词中间断行）；逗号句号这类标点不放在行首，挤在上一行末尾。
 * 以「询问：」开头的一行是 AI 在问他，用琥珀色，属于 askGroup。
 */
class InkWriter(
    private val canvas: NoteCanvas,
    private val plan: InkPlan,
    val group: String,
    val askGroup: String,
    private val font: Int,
    private val onDone: (InkWriter) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private val pending = StringBuilder()
    private var consumed = 0
    private var finished = false
    private var stopped = false
    private var atPara = true
    var hasAsk = false
        private set
    private var inAsk = false

    // 写到哪了
    private var pi = plan.page
    private var x = plan.firstX
    private var base = plan.firstBaseline
    private var lineRight = plan.rightX
    private var lineStart = true
    /** 还在问号后面那一行。 */
    private var firstLine = true
    private var hang = 0f
    private var paraStart = true
    /** 刚写了「①」「1.」这样的序号：下一个字的位置就是这一段换行后对齐的地方。 */
    private var markerPending = false
    private val size = plan.size
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = AiInk.typeface(canvas.context, font); textSize = size
    }
    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            val tok = queue.removeFirstOrNull()
            if (tok != null) place(tok)
            when {
                queue.isNotEmpty() -> main.postDelayed(this, if (queue.size > 80) 18 else 42)
                finished && pending.isEmpty() -> { stopped = true; onDone(this@InkWriter) }
                else -> main.postDelayed(this, 60)
            }
        }
    }

    init {
        // 第一行：问号后面，看右边有没有挡着的
        val obs = canvas.obstacles(pi, lineRect(x), setOf(group, askGroup)).filter { it.right > x + 0.1f * size }
        lineRight = min(plan.rightX, obs.minOfOrNull { it.left - 0.4f * size } ?: plan.rightX)
        if (lineRight - x < 2f * size) newLine()
        main.post(tick)
    }

    /** 收到的回答（到目前为止的全部正文）。done：回答结束了。 */
    fun feed(text: String, done: Boolean) {
        if (stopped) return
        if (text.length > consumed) { pending.append(text, consumed, text.length); consumed = text.length }
        if (done) finished = true
        tokenize()
    }

    fun stop() { stopped = true; main.removeCallbacks(tick) }

    private fun tokenize() {
        while (pending.isNotEmpty()) {
            if (atPara) {
                val head = pending.toString()
                val trimmed = head.trimStart(' ', '\t')
                if (trimmed.length != head.length) { pending.delete(0, head.length - trimmed.length); continue }
                val m = ASK_PREFIXES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
                if (m != null) { queue.add(ASK); pending.delete(0, m.length); atPara = false; continue }
                if (!finished && trimmed.length < 10 && !trimmed.contains('\n') &&
                    ASK_PREFIXES.any { it.startsWith(trimmed, ignoreCase = true) }) return   // 还看不出是不是「询问：」
            }
            val c = pending[0]
            when {
                c == '\r' -> { pending.deleteCharAt(0); continue }
                c == '\n' -> { queue.add("\n"); pending.deleteCharAt(0); atPara = true; continue }
                c == ' ' || c == '\t' -> { queue.add(" "); pending.deleteCharAt(0) }
                isWordChar(c) -> {
                    var j = 0
                    while (j < pending.length && isWordChar(pending[j])) j++
                    if (j == pending.length && !finished) return   // 这个词还没收完
                    while (j < pending.length && pending[j] in ",.;:!?)%'\"、") j++
                    queue.add(pending.substring(0, j)); pending.delete(0, j)
                }
                else -> {
                    val n = if (Character.isHighSurrogate(c) && pending.length > 1) 2 else 1
                    queue.add(pending.substring(0, n)); pending.delete(0, n)
                }
            }
            atPara = false
        }
    }

    private fun place(tok: String) {
        when {
            tok == "\n" -> {
                if (!lineStart) newLine()
                paraStart = true; hang = 0f; inAsk = false; markerPending = false
                return
            }
            tok == ASK -> {   // 询问总是另起一行
                inAsk = true; hasAsk = true
                if (!lineStart || firstLine) newLine()
                return
            }
            tok.isBlank() -> { if (!lineStart) x += paint.measureText(" ") * 0.8f; return }
        }
        var w = 0f
        run { var i = 0; while (i < tok.length) { val n = Character.charCount(tok.codePointAt(i)); w += adv(tok.substring(i, i + n)); i += n } }
        val closing = tok.length <= 2 && tok[0] in CLOSING
        if (!lineStart && x + w > lineRight && !(closing && x + w <= lineRight + 0.8f * size)) newLine()
        if (markerPending) { hang = x - plan.leftX; markerPending = false }   // 「① 」「1.」后面换行时和正文对齐
        val marker = paraStart && LIST_MARKER.matches(tok)
        // 一个字一个字写；一个词比一整行还长时在字母之间断开
        var i = 0
        while (i < tok.length) {
            val n = Character.charCount(tok.codePointAt(i))
            val ch = tok.substring(i, i + n)
            val cw = adv(ch)
            if (!lineStart && x + cw > lineRight && !(closing && x + cw <= lineRight + 0.8f * size)) newLine()
            glyph(ch)
            i += n
        }
        if (marker) markerPending = true
        paraStart = false
    }

    /** 这个字占多宽。 */
    private fun adv(ch: String) = paint.measureText(ch)

    /** 写一个字：工工整整，不做手写的随机抖动。 */
    private fun glyph(ch: String) {
        val cw = adv(ch)
        canvas.addGlyph(pi, NoteGlyph(ch, x, base, size, 0f, if (inAsk) AiInk.ASK else AiInk.INK, font,
            if (inAsk) askGroup else group, cw))
        x += cw
        lineStart = false
    }

    private fun lineRect(x0: Float) = RectF(x0, base - size * 0.8f, NotePaper.PAGE_W, base + size * 0.12f)

    /** 换到下一行：从行首写；这一行开头被挡着就再往下，写到页底就换到下一页。 */
    private fun newLine() {
        base += plan.pitch
        lineStart = true
        firstLine = false
        var guard = 0
        while (guard++ < 300) {
            x = plan.leftX + hang
            if (base + size * 0.3f > NotePaper.PAGE_H - BOTTOM) {
                pi += 1
                canvas.ensurePage(pi)
                base = TOP + size * 0.85f
            }
            val obs = canvas.obstacles(pi, lineRect(x), setOf(group, askGroup))
            if (obs.any { it.left < x + 2f * size && it.right > x - 0.2f * size }) { base += plan.pitch; continue }
            lineRight = min(plan.rightX, obs.filter { it.left >= x }.minOfOrNull { it.left - 0.4f * size } ?: plan.rightX)
            if (lineRight - x < 3f * size) { base += plan.pitch; continue }
            return
        }
    }

    private fun isWordChar(c: Char) = (c.isLetterOrDigit() && c.code < 0x2E80) || c in WORD_EXTRA

    companion object {
        private const val ASK = "\u0000ASK"
        val ASK_PREFIXES = listOf("询问：", "询问:", "Ask:", "Question:")
        private const val CLOSING = "，。、；：？！）》」』”’,.;:!?)%"
        private const val WORD_EXTRA = "²³¹⁰⁴⁵⁶⁷⁸⁹ⁿ₀₁₂₃₄₅₆₇₈₉ₙ'_^"
        private val LIST_MARKER = Regex("^([①-⑳]|\\d{1,2}[.、)）]|[·•])$")
        private const val TOP = 30f
        private const val BOTTOM = 24f
    }
}

/**
 * 「一同书写」的总调度：收到新写完的一笔 → 认问号、句号 → 停笔一会儿 → 发给 AI → 把回答写到纸上；
 * AI 问他的时候，记着这个询问（存在笔记本目录的 write.json 里，下次打开还在），等他写完以句号结尾的回答。
 */
class WriteTogether(
    private val ctx: Context,
    private val canvas: NoteCanvas,
    private val book: Notebook,
    private val prefs: Prefs,
    private val say: (String) -> Unit,
) {
    var enabled = prefs.writeTogether
        set(v) { field = v; prefs.writeTogether = v; if (!v) cancelTrigger() }

    private val main = Handler(Looper.getMainLooper())
    /** 分界：这一笔（序号）以前写的不算下一个问题。 */
    private var boundary = 0L
    private var trigger: Runnable? = null
    private class Job(val qa: Qa, val turn: Int, val writer: InkWriter, val plan: InkPlan) { var failed = false }
    private val jobs = ArrayList<Job>()
    /** AI 问了他一句，等他回答。 */
    private class Pending(val qa: Qa, val plan: InkPlan, val askGroup: String)
    private var pending: Pending? = null

    private val listener: (Qa, Boolean) -> Unit = { qa, _ -> onQa(qa) }

    init {
        Engine.addListener(listener)
        restorePending()
    }

    fun stop() {
        Engine.removeListener(listener)
        cancelTrigger()
        for (j in jobs) j.writer.stop()
        jobs.clear()
    }

    /** 笔落下了：他还在接着写，先别发。 */
    fun onPenDown() = cancelTrigger()

    fun onStroke(pi: Int, s: Stroke) {
        if (!enabled) return
        val page = canvas.notePage(pi) ?: return
        val strokes = page.strokes
        val i = strokes.lastIndexOf(s)
        if (i < 0) return
        val hook = if (i >= 1) strokes[i - 1] else null
        if (hook != null && hook.seq == s.seq - 1 && hook.seq > boundary && Punct.isQuestion(hook.pts, s.pts)) {
            schedule { ask(pi, hook, s) }
            return
        }
        val recent = strokes.subList(max(0, i - 16), i)
        if (Punct.isPeriod(s.pts, Punct.lineRef(recent, s))) {
            if (pending != null) schedule { clarify(pi, s) } else boundary = s.seq
        }
    }

    private fun schedule(r: () -> Unit) {
        cancelTrigger()
        val t = Runnable { trigger = null; r() }
        trigger = t
        main.postDelayed(t, TRIGGER_MS)
    }

    private fun cancelTrigger() { trigger?.let { main.removeCallbacks(it) }; trigger = null }

    private fun stillThere(pi: Int, s: Stroke) = canvas.notePage(pi)?.strokes?.any { it === s } == true

    /** 他写完一个问题：找出问号前面那句话，发给 AI，回答写在问号后面。 */
    private fun ask(pi: Int, hook: Stroke, dot: Stroke) {
        if (!stillThere(pi, dot) || !stillThere(pi, hook)) return
        if (prefs.apiKey.isBlank()) { say(ctx.getString(R.string.no_key)); return }
        val page = canvas.notePage(pi) ?: return
        // 以前问的还没回答他就又问了：那个询问作废
        pending?.let { canvas.dissolveGroup(it.askGroup); pending = null; savePending() }
        val qb = Punct.box(hook.pts).let { a -> val b = Punct.box(dot.pts); Punct.Box(min(a.l, b.l), min(a.t, b.t), max(a.r, b.r), max(a.b, b.b)) }
        val cands = page.strokes.filter { it.seq > boundary && it.seq <= dot.seq }.sortedBy { it.seq }
        val block = Punct.questionBlock(cands, qb).ifEmpty { listOf(hook, dot) }
        boundary = dot.seq
        val plan = InkPlan.plan(page, pi, block, qb, prefs.aiHand)
        val region = regionOf(block)
        val w = ImageUtil.PAGE_MAX
        val k = w / NotePaper.PAGE_W
        val pageBmp = canvas.renderPage(pi, w)
        val prev = if (pi > 0 && region.top < NotePaper.PAGE_H * 0.35f) canvas.renderPage(pi - 1, w) else null
        val qa = Engine.askWrite(pageBmp, scaled(region, k), prev, book.title, Prompts.writeLayout(prefs.lang, plan.firstChars(), plan.lineChars()))
        start(qa, 0, plan)
    }

    /** 他回答了 AI 的询问（以句号结尾）：询问化成粒子飘散，把他的回答发给 AI，重新写在原来问题的后面。 */
    private fun clarify(pi: Int, dot: Stroke) {
        val p = pending ?: return
        if (!stillThere(pi, dot)) return
        if (p.qa.busy || !p.qa.canFollow) return
        val page = canvas.notePage(pi) ?: return
        pending = null; savePending()
        canvas.dissolveGroup(p.askGroup)
        val answer = page.strokes.filter { it.seq > boundary && it.seq <= dot.seq }
        boundary = dot.seq
        val region = regionOf(answer.ifEmpty { listOf(dot) })
        val w = ImageUtil.PAGE_MAX
        val pageBmp = canvas.renderPage(pi, w)
        val hint = Prompts.writeLayout(prefs.lang, p.plan.firstChars(), p.plan.lineChars())
        if (!Engine.clarifyWrite(p.qa, pageBmp, scaled(region, w / NotePaper.PAGE_W), hint)) { pageBmp.recycle(); return }
        start(p.qa, p.qa.turns.lastIndex, p.plan)
    }

    private fun start(qa: Qa, turn: Int, plan: InkPlan) {
        val group = qa.id + ":" + turn
        var job: Job? = null
        val writer = InkWriter(canvas, plan, group, "$group:ask", prefs.aiHand) { w -> job?.let { finishJob(it, w) } }
        job = Job(qa, turn, writer, plan)
        jobs.add(job)
        onQa(qa)
    }

    private fun finishJob(job: Job, w: InkWriter) {
        jobs.remove(job)
        if (w.hasAsk) {
            pending?.let { if (it.askGroup != w.askGroup) canvas.dissolveGroup(it.askGroup) }
            pending = Pending(job.qa, job.plan, w.askGroup)
            savePending()
        }
    }

    private fun onQa(qa: Qa) {
        for (j in jobs.toList()) {
            if (j.qa !== qa) continue
            val t = qa.turns.getOrNull(j.turn) ?: continue
            if (t.state == Turn.STATE_ERROR) {
                if (j.failed) continue
                j.failed = true
                say(ctx.getString(R.string.write_failed, t.error ?: ""))
                j.writer.feed("", true)
                continue
            }
            val done = t.state == Turn.STATE_DONE
            inkText(t.raw, done)?.let { j.writer.feed(it, done) }
        }
    }

    // ---------- 记住等他回答的询问 ----------

    private fun savePending() {
        val p = pending
        val pageId = p?.let { canvas.notePage(it.plan.page)?.id }
        NoteStore.saveText(book, PENDING_FILE, if (p == null || pageId == null) null else
            JSONObject().put("qa", p.qa.id).put("ask", p.askGroup).put("plan", p.plan.toJson(pageId)).toString())
    }

    private fun restorePending() {
        val raw = NoteStore.loadText(book, PENDING_FILE) ?: return
        try {
            val o = JSONObject(raw)
            val qa = Engine.store.list.firstOrNull { it.id == o.getString("qa") } ?: return
            val po = o.getJSONObject("plan")
            var pi = -1
            for (i in 0 until canvas.pageCount()) if (canvas.notePage(i)?.id == po.getString("page")) pi = i
            if (pi < 0) return
            val ask = o.getString("ask")
            if (canvas.notePage(pi)?.glyphs?.none { it.group == ask } != false) return   // 询问已经被擦掉了
            pending = Pending(qa, InkPlan.fromJson(po, pi), ask)
        } catch (_: Exception) { }
    }

    private fun regionOf(strokes: List<Stroke>): RectF {
        val r = RectF(strokes[0].box[0], strokes[0].box[1], strokes[0].box[2], strokes[0].box[3])
        for (s in strokes) r.union(s.box[0], s.box[1], s.box[2], s.box[3])
        return r
    }

    private fun scaled(r: RectF, k: Float) = RectF(r.left * k, r.top * k, r.right * k, r.bottom * k)

    companion object {
        /** 写完问号（或句号）停笔这么久才发：他可能还要接着写。 */
        const val TRIGGER_MS = 900L
        private const val PENDING_FILE = "write.json"

        /**
         * 从模型的原始输出里取出要写在纸上的正文：第一轮是「题目：……」「---」再正文；追问直接是正文
         * （偶尔也带题目行，一样去掉）。还没收到分隔线、看不出来时返回 null。
         */
        fun inkText(raw: String, finished: Boolean): String? {
            val t = raw.trimStart()
            if (t.isEmpty()) return if (finished) "" else null
            val lines = t.split("\n")
            val first = lines[0].trim()
            val header = HEADERS.any { first.startsWith(it, ignoreCase = true) }
            if (!header) {
                if (!finished && t.length < 4 && HEADERS.any { it.startsWith(t, ignoreCase = true) }) return null
                return t
            }
            val sep = lines.indexOfFirst { it.trim() == "---" }
            if (sep < 0) return if (finished) lines.drop(1).filterNot { l -> HEADERS.any { l.trim().startsWith(it, true) } }.joinToString("\n").trim() else null
            return lines.subList(sep + 1, lines.size).joinToString("\n").trimStart('\n', ' ')
        }

        private val HEADERS = listOf("题目", "类型", "Topic", "Type")
    }
}
