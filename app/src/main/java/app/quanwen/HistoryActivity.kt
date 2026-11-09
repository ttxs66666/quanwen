package app.quanwen

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 历史记录：所有圈过的题，新的在上面。点开看详情，长按删除。 */
class HistoryActivity : Activity() {
    private lateinit var store: QaStore
    private lateinit var adapter: Adapter
    private val fmt = SimpleDateFormat("M/d HH:mm", Locale.getDefault())

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Colors.PAPER
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        Engine.init(this)
        store = Engine.store
        val list = ListView(this).apply {
            setBackgroundColor(Colors.PAPER)
            divider = null
            setPadding(dpi(16f), dpi(24f), dpi(16f), dpi(24f)); clipToPadding = false
        }
        adapter = Adapter()
        list.adapter = adapter
        list.emptyView = null
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, DetailActivity::class.java).putExtra("id", adapter.getItem(pos).id))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val qa = adapter.getItem(pos)
            AlertDialog.Builder(this).setMessage(R.string.delete_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    store.delete(qa); adapter.notifyDataSetChanged()
                    QuanwenService.instance?.onQaDeleted()
                }
                .setNegativeButton(R.string.cancel, null).show()
            true
        }
        setContentView(list)
        if (store.list.isEmpty()) {
            setContentView(TextView(this).apply {
                text = getString(R.string.history_empty); textSize = 15f; setTextColor(Colors.MUTED)
                setBackgroundColor(Colors.PAPER); setPadding(dpi(24f), dpi(48f), dpi(24f), dpi(24f))
            })
        }
    }

    private inner class Adapter : BaseAdapter() {
        private fun items() = store.list.asReversed()
        override fun getCount() = items().size
        override fun getItem(position: Int): Qa = items()[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val qa = getItem(position)
            val box = LinearLayout(this@HistoryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpi(14f), dpi(10f), dpi(14f), dpi(10f))
            }
            val head = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.HORIZONTAL }
            if (qa.type.isNotBlank()) head.addView(TextView(this@HistoryActivity).apply {
                text = qa.type; textSize = 12f; setTextColor(Color.WHITE)
                setPadding(dpi(7f), dpi(1f), dpi(7f), dpi(1f))
                background = rounded(Colors.forType(qa.type), dp(9f))
            })
            head.addView(TextView(this@HistoryActivity).apply {
                text = listOf(qa.subject, fmt.format(Date(qa.time))).filter { it.isNotBlank() }.joinToString(" · ")
                textSize = 12f; setTextColor(Colors.MUTED); setPadding(dpi(8f), 0, 0, 0)
            })
            box.addView(head)
            box.addView(TextView(this@HistoryActivity).apply {
                text = plainMath(qa.title.ifBlank { qa.turns.firstOrNull()?.text?.take(60) ?: "" })
                textSize = 15f; setTextColor(Colors.INK); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dpi(4f), 0, 0)
            })
            return box
        }
    }
}

/** 一道题的详情：圈内截图 + 所有回答。 */
class DetailActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Colors.PAPER
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        Engine.init(this)
        val store = Engine.store
        val qa = store.list.firstOrNull { it.id == intent.getStringExtra("id") } ?: run { finish(); return }
        val web = WebView(this).apply { setBackgroundColor(Colors.PAPER); settings.javaScriptEnabled = true }
        setContentView(web)
        val crop = store.cropFile(qa).takeIf { it.exists() }?.readBytes()
        val fmt = SimpleDateFormat("yyyy-M-d HH:mm", Locale.getDefault())
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val turns = JSONArray()
                qa.turns.forEach { t ->
                    turns.put(JSONObject()
                        .put("label", when (t.kind) {
                            Turn.KIND_MORE -> getString(R.string.label_more)
                            Turn.KIND_REVEAL -> getString(R.string.label_reveal)
                            Turn.KIND_ASK -> if (t.imagePath.isNotBlank() && t.question.isBlank()) getString(R.string.label_ask_hand)
                                             else getString(R.string.label_ask) + t.question
                            else -> ""
                        })
                        .put("text", t.text)
                        .put("image", if (t.imagePath.isNotBlank()) try {
                            "data:image/jpeg;base64," + Base64.encodeToString(java.io.File(t.imagePath).readBytes(), Base64.NO_WRAP)
                        } catch (_: Exception) { "" } else "")
                        .put("state", if (t.state == Turn.STATE_ERROR) "error" else "done")
                        .put("error", t.error ?: ""))
                }
                val meta = listOf(qa.type, qa.subject, fmt.format(Date(qa.time)), plainMath(qa.title)).filter { it.isNotBlank() }.joinToString(" · ")
                val payload = JSONObject().put("turns", turns).put("meta", meta)
                    .put("s", JSONObject().put("loading", "").put("retry", ""))
                if (crop != null) payload.put("image", "data:image/jpeg;base64," + Base64.encodeToString(crop, Base64.NO_WRAP))
                web.evaluateJavascript("document.body.style.padding='56px 28px 28px';render(${JSONObject.quote(payload.toString())})", null)
            }
        }
        web.loadUrl("file:///android_asset/card.html")
    }
}
