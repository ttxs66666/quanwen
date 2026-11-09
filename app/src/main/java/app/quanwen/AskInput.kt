package app.quanwen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.roundToInt

/** 书写板：用笔把追问写下来，交给 AI 看字迹。用过笔以后只认笔，防手掌误触。 */
@SuppressLint("ViewConstructor")
class HandPadView(context: Context) : View(context) {
    private val prefs = Prefs(context)
    private val strokes = mutableListOf<FloatArray>()
    private var current: ArrayList<Float>? = null
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.INK; style = Paint.Style.STROKE
        strokeWidth = context.dp(2.6f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val guide = Paint().apply { color = 0x14000000; strokeWidth = context.dp(1f) }

    init { background = rounded(Color.WHITE, context.dp(10f), 0x22000000, context.dpi(1f)) }

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun clear() { strokes.clear(); current = null; invalidate() }

    override fun onDraw(c: Canvas) {
        val step = context.dp(36f)
        var y = step
        while (y < height) { c.drawLine(context.dp(10f), y, width - context.dp(10f), y, guide); y += step }
        val all = ArrayList(strokes); current?.let { all.add(it.toFloatArray()) }
        c.drawPath(ImageUtil.strokesPath(all), ink)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tool = e.getToolType(0)
        val pen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        if (pen && !prefs.stylusSeen) prefs.stylusSeen = true
        if (!pen && prefs.stylusSeen) return true
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> current = arrayListOf(e.x, e.y)
            MotionEvent.ACTION_MOVE -> current?.let { s ->
                for (i in 0 until e.historySize) { s.add(e.getHistoricalX(0, i)); s.add(e.getHistoricalY(0, i)) }
                s.add(e.x); s.add(e.y)
            }
            MotionEvent.ACTION_UP -> { current?.let { it.add(e.x); it.add(e.y); strokes.add(it.toFloatArray()) }; current = null }
            MotionEvent.ACTION_CANCEL -> current = null
        }
        invalidate()
        return true
    }

    /** 把字迹裁成一张白底图（JPEG），长边最多 1200。空白时返回 null。 */
    fun toJpeg(): ByteArray? {
        if (strokes.isEmpty()) return null
        val b = ImageUtil.bounds(strokes)
        val pad = context.dp(16f)
        val w = max(1, (b.width() + 2 * pad).roundToInt())
        val h = max(1, (b.height() + 2 * pad).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.translate(pad - b.left, pad - b.top)
        c.drawPath(ImageUtil.strokesPath(strokes), ink)
        val scaled = ImageUtil.scaleToMax(bmp, 1200)
        return ImageUtil.jpeg(scaled, 90).also { if (scaled !== bmp) scaled.recycle(); bmp.recycle() }
    }
}

/**
 * 追问的输入区：先选方式（书写板 / 语音 / 打字），再输入、发送。
 * 悬浮卡片和圈题页共用。打字需要键盘，悬浮卡片要临时变成能获得焦点的窗口，所以通过 [onNeedFocus] 通知外面。
 */
@SuppressLint("ViewConstructor")
class AskInputView(
    private val ctx: Context,
    private val currentQa: () -> Qa?,
    private val onClosed: () -> Unit,
    private val onNeedFocus: (Boolean) -> Unit,
    private val onResized: () -> Unit,
) : LinearLayout(ctx) {

    private enum class Mode { CHOOSE, PAD, VOICE, TYPE }
    private var mode = Mode.CHOOSE
    private var loc: Context = Lang.wrap(ctx)

    private val chooser = row()
    private val padBox = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val pad = HandPadView(ctx)
    private val voiceBox = row()
    private val voiceStatus = TextView(ctx).apply {
        textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setPadding(0, ctx.dpi(10f), 0, ctx.dpi(10f))
        background = rounded(Colors.ACCENT, ctx.dp(10f))
        setOnClickListener { if (voiceState == 1) stopVoice() }
    }
    private val typeBox = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val edit = EditText(ctx).apply {
        textSize = 15f; setTextColor(Colors.INK); minLines = 2; maxLines = 4
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = rounded(Color.WHITE, ctx.dp(10f), 0x22000000, ctx.dpi(1f))
        setPadding(ctx.dpi(10f), ctx.dpi(8f), ctx.dpi(10f), ctx.dpi(8f))
    }

    /** 语音：0 空闲，1 录音中，2 识别中。 */
    private var voiceState = 0
    private val autoStop = Runnable { if (voiceState == 1) stopVoice() }

    init {
        orientation = VERTICAL
        setPadding(ctx.dpi(10f), ctx.dpi(4f), ctx.dpi(10f), ctx.dpi(10f))
        addView(chooser)

        padBox.addView(pad, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(170f)))
        padBox.addView(row().apply {
            setPadding(0, ctx.dpi(8f), 0, 0)
            add(this, btn(R.string.ask_clear) { pad.clear() })
            add(this, btn(R.string.ask_cancel) { close() })
            add(this, btn(R.string.ask_send, primary = true) { sendPad() })
        })
        addView(padBox)

        add(voiceBox, voiceStatus, weight = 2f)
        add(voiceBox, btn(R.string.ask_cancel) { cancelVoice() })
        addView(voiceBox)

        typeBox.addView(edit, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        typeBox.addView(row().apply {
            setPadding(0, ctx.dpi(8f), 0, 0)
            add(this, btn(R.string.ask_cancel) { close() })
            add(this, btn(R.string.ask_send, primary = true) { sendTyped() })
        })
        addView(typeBox)
        buildChooser()
        show(Mode.CHOOSE)
    }

    fun applyStrings() { loc = Lang.wrap(ctx); buildChooser(); edit.hint = loc.getString(R.string.ask_type_hint) }

    /** 打开输入区（从选方式开始）。 */
    fun open() { show(Mode.CHOOSE) }

    private fun buildChooser() {
        chooser.removeAllViews()
        add(chooser, btn(R.string.ask_pad) { show(Mode.PAD) })
        add(chooser, btn(R.string.ask_voice) { startVoice() })
        add(chooser, btn(R.string.ask_type) { show(Mode.TYPE) })
        add(chooser, btn(R.string.ask_cancel) { close() }, weight = 0.7f)
        edit.hint = loc.getString(R.string.ask_type_hint)
    }

    private fun show(m: Mode) {
        mode = m
        chooser.visibility = if (m == Mode.CHOOSE) View.VISIBLE else View.GONE
        padBox.visibility = if (m == Mode.PAD) View.VISIBLE else View.GONE
        voiceBox.visibility = if (m == Mode.VOICE) View.VISIBLE else View.GONE
        typeBox.visibility = if (m == Mode.TYPE) View.VISIBLE else View.GONE
        if (m == Mode.TYPE) {
            onNeedFocus(true)
            // 窗口刚变成可获得焦点，要等焦点切换完再弹键盘，否则请求会被忽略
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            edit.requestFocus()
            for (delay in longArrayOf(120, 400)) edit.postDelayed({
                if (mode == Mode.TYPE) { edit.requestFocus(); imm.showSoftInput(edit, 0) }
            }, delay)
        } else hideKeyboard()
        onResized()
    }

    private fun close() {
        if (voiceState == 1) cancelVoice()
        pad.clear(); edit.setText("")
        hideKeyboard()
        show(Mode.CHOOSE)
        onClosed()
    }

    private fun hideKeyboard() {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(edit.windowToken, 0)
        onNeedFocus(false)
    }

    // ---------- 三种方式 ----------

    private fun sendPad() {
        val qa = currentQa() ?: return
        val jpeg = pad.toJpeg() ?: run { toast(R.string.pad_empty); return }
        Engine.followUp(qa, Turn.KIND_ASK, question = "", handwriting = jpeg)
        close()
    }

    private fun sendTyped() {
        val qa = currentQa() ?: return
        val text = edit.text.toString().trim()
        if (text.isEmpty()) { toast(R.string.type_empty); return }
        Engine.followUp(qa, Turn.KIND_ASK, text)
        close()
    }

    private fun startVoice() {
        if (!VoiceRecorder.hasPermission(ctx)) { MicPermissionActivity.open(ctx); return }
        if (!VoiceRecorder.start(ctx)) { toast(R.string.mic_failed); return }
        voiceState = 1
        voiceStatus.text = loc.getString(R.string.voice_listening)
        show(Mode.VOICE)
        postDelayed(autoStop, VoiceRecorder.MAX_MS)
    }

    private fun cancelVoice() {
        removeCallbacks(autoStop)
        if (voiceState == 1) VoiceRecorder.stop()
        voiceState = 0
        show(Mode.CHOOSE)
    }

    private fun stopVoice() {
        removeCallbacks(autoStop)
        val qa = currentQa()
        val (result, audio) = VoiceRecorder.stop()
        if (result != VoiceRecorder.Result.OK || audio == null || qa == null) {
            voiceState = 0
            show(Mode.CHOOSE)
            toast(when (result) {
                VoiceRecorder.Result.TOO_SHORT -> R.string.voice_too_short
                VoiceRecorder.Result.SILENT -> R.string.voice_unclear
                else -> R.string.mic_failed
            })
            return
        }
        voiceState = 2
        voiceStatus.text = loc.getString(R.string.voice_working)
        val prefs = Prefs(ctx)
        // 提示里只放科目：放题目的话，录音太轻时模型会把题目原样「认」出来
        val hint = Prompts.transcribeHint(qa.subject, "")
        Thread {
            val text = try { VoiceRecorder.transcribe(prefs.baseUrl, prefs.apiKey, audio, hint) } catch (e: Exception) { null }
            post {
                voiceState = 0
                when {
                    text == null -> { toast(R.string.voice_failed); show(Mode.CHOOSE) }
                    text.isBlank() -> { toast(R.string.voice_unclear); show(Mode.CHOOSE) }
                    else -> { Engine.followUp(qa, Turn.KIND_ASK, text); close() }
                }
            }
        }.start()
    }

    // ---------- 小工具 ----------

    private fun toast(id: Int) = Toast.makeText(ctx, loc.getString(id), Toast.LENGTH_SHORT).show()

    private fun row() = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun add(parent: LinearLayout, v: View, weight: Float = 1f) {
        parent.addView(v, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            if (parent.childCount > 0) marginStart = ctx.dpi(8f)
        })
    }

    private fun btn(textId: Int, primary: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        text = loc.getString(textId); textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else Colors.INK)
        setPadding(0, ctx.dpi(9f), 0, ctx.dpi(9f))
        background = if (primary) rounded(Colors.INK, ctx.dp(10f)) else rounded(0x0F000000, ctx.dp(10f), 0x22000000, ctx.dpi(1f))
        setOnClickListener { onClick() }
    }
}
