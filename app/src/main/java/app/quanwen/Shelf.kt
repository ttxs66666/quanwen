package app.quanwen

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** 封面样式：底色 + 花纹。 */
object Covers {
    class Style(val color: Int, val pattern: Int, val darkText: Boolean = false)

    const val PLAIN = 0
    const val DOTS = 1
    const val STRIPES = 2
    const val GRID = 3

    val styles = listOf(
        Style(0xFF2F5D50.toInt(), PLAIN),           // 墨绿
        Style(0xFF2B4570.toInt(), DOTS),            // 藏青
        Style(0xFF7B2D3B.toInt(), PLAIN),           // 酒红
        Style(0xFFC48B2C.toInt(), STRIPES),         // 赭黄
        Style(0xFF4A4D55.toInt(), GRID),            // 石墨
        Style(0xFFE6D5B3.toInt(), DOTS, darkText = true),   // 浅杏
    )

    fun style(i: Int) = styles[((i % styles.size) + styles.size) % styles.size]
}

/** 画一本笔记本的封面：左边书脊、花纹、中间偏上一张贴标签写书名。 */
@SuppressLint("ViewConstructor")
class CoverView(ctx: Context, var cover: Int, var title: String) : View(ctx) {
    var picked = false
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val s = Covers.style(cover)
        val w = width.toFloat(); val h = height.toFloat()
        val radius = context.dp(8f)
        r.set(0f, 0f, w, h)
        fill.color = s.color
        c.drawRoundRect(r, radius, radius, fill)
        c.save()
        c.clipRect(w * 0.12f, 0f, w, h)
        line.color = if (s.darkText) 0x22000000 else 0x26FFFFFF
        line.strokeWidth = context.dp(1.2f)
        fill.color = line.color
        val step = context.dp(12f)
        when (s.pattern) {
            Covers.DOTS -> { var y = step; while (y < h) { var x = w * 0.12f + step / 2; while (x < w) { c.drawCircle(x, y, context.dp(1.6f), fill); x += step }; y += step } }
            Covers.STRIPES -> { var x = -h; while (x < w) { c.drawLine(x, h, x + h, 0f, line); x += step } }
            Covers.GRID -> {
                var x = w * 0.12f; while (x < w) { c.drawLine(x, 0f, x, h, line); x += step }
                var y = 0f; while (y < h) { c.drawLine(0f, y, w, y, line); y += step }
            }
        }
        c.restore()
        // 书脊
        fill.color = blend(s.color, Color.BLACK, 0.28f)
        r.set(0f, 0f, w * 0.12f, h)
        c.drawRoundRect(r, radius, radius, fill)
        c.drawRect(w * 0.06f, 0f, w * 0.12f, h, fill)
        // 标签
        val lx = w * 0.22f; val lw = w * 0.68f; val ly = h * 0.16f; val lh = h * 0.30f
        fill.color = if (s.darkText) 0xFFFFFDF8.toInt() else 0xF2FFFDF8.toInt()
        r.set(lx, ly, lx + lw, ly + lh)
        c.drawRoundRect(r, context.dp(4f), context.dp(4f), fill)
        text.color = Colors.INK
        text.textSize = max(context.dp(11f), min3(w * 0.1f, context.dp(16f)))
        val pad = context.dp(6f)
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, text, max(1, (lw - 2 * pad).toInt()))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save()
        c.translate(lx + pad, ly + (lh - layout.height) / 2)
        c.clipRect(0f, 0f, lw - 2 * pad, lh)
        layout.draw(c)
        c.restore()
        if (picked) {
            line.color = Colors.ACCENT; line.strokeWidth = context.dp(3f)
            r.set(line.strokeWidth / 2, line.strokeWidth / 2, w - line.strokeWidth / 2, h - line.strokeWidth / 2)
            c.drawRoundRect(r, radius, radius, line)
        }
    }

    private fun min3(a: Float, b: Float) = if (a < b) a else b

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t).toInt()
        return Color.argb(255, ch(16), ch(8), ch(0))
    }
}

/**
 * 书架：所有笔记本的封面排成格子，不分组。第一格是「新建」，新建时选一个封面样式。
 * 长按一本：重命名、换封面、删除。
 */
class ShelfActivity : Activity() {
    private lateinit var grid: LinearLayout
    private var lastWidth = 0

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NoteStore.init(this)
        window.statusBarColor = Colors.PAPER
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Colors.PAPER); fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12f), dpi(12f), dpi(24f), dpi(4f))
        }
        header.addView(ToolIcon(this, ToolIcon.BACK).apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
        header.addView(TextView(this).apply {
            text = getString(R.string.shelf_title); textSize = 26f; setTextColor(Colors.INK); paint.isFakeBoldText = true
            setPadding(dpi(6f), 0, 0, 0)
        })
        root.addView(header)
        val scroll = ScrollView(this)
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(24f), dpi(12f), dpi(24f), dpi(40f)) }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        scroll.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            if (r - l != lastWidth) { lastWidth = r - l; v.post { refresh() } }
        }
    }

    override fun onResume() { super.onResume(); if (lastWidth > 0) refresh() }

    /** 按当前宽度排格子：每格约 150dp 宽。 */
    private fun refresh() {
        grid.removeAllViews()
        val avail = lastWidth - dpi(48f)
        val gapPx = dpi(20f)
        val cols = max(2, (avail + gapPx) / (dpi(150f) + gapPx))
        val cellW = (avail - gapPx * (cols - 1)) / cols
        val cells = mutableListOf<View>(newCell(cellW))
        for (nb in NoteStore.list()) cells.add(bookCell(nb, cellW))
        cells.chunked(cols).forEach { rowCells ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowCells.forEachIndexed { i, v ->
                row.addView(v, LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = gapPx })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dpi(24f) })
        }
    }

    private fun coverHeight(cellW: Int) = (cellW * 1.32f).toInt()

    private fun newCell(cellW: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@ShelfActivity).apply {
            text = "+"; textSize = 40f; setTextColor(Colors.MUTED); gravity = Gravity.CENTER
            background = rounded(0x08000000, dp(8f), 0x33000000, dpi(1.5f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, coverHeight(cellW)))
        addView(caption(getString(R.string.shelf_new), ""))
        setOnClickListener { newNotebook() }
    }

    private fun bookCell(nb: Notebook, cellW: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(CoverView(this@ShelfActivity, nb.cover, nb.title),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, coverHeight(cellW)))
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(nb.modified))
        addView(caption(nb.title, getString(R.string.shelf_meta, nb.pageIds.size, date)))
        setOnClickListener { NoteActivity.open(this@ShelfActivity, nb.id) }
        setOnLongClickListener { v -> bookMenu(v, nb); true }
    }

    private fun caption(title: String, meta: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(2f), dpi(8f), dpi(2f), 0)
        addView(TextView(this@ShelfActivity).apply {
            text = title; textSize = 14f; setTextColor(Colors.INK); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (meta.isNotEmpty()) addView(TextView(this@ShelfActivity).apply { text = meta; textSize = 12f; setTextColor(Colors.MUTED) })
    }

    // ---------- 新建、重命名、换封面、删除 ----------

    private fun newNotebook() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(24f), dpi(8f), dpi(24f), 0) }
        val name = EditText(this).apply {
            setText(getString(R.string.shelf_default_title, NoteStore.list().size + 1)); setSingleLine(); selectAll()
        }
        box.addView(name)
        box.addView(TextView(this).apply { text = getString(R.string.shelf_cover); textSize = 13f; setTextColor(Colors.MUTED); setPadding(0, dpi(14f), 0, dpi(8f)) })
        var picked = NoteStore.list().size % Covers.styles.size
        box.addView(coverPicker(picked) { picked = it })
        AlertDialog.Builder(this)
            .setTitle(R.string.shelf_new_title)
            .setView(box)
            .setPositiveButton(R.string.shelf_create) { _, _ ->
                val t = name.text.toString().trim().ifEmpty { getString(R.string.shelf_default_title, NoteStore.list().size + 1) }
                val nb = NoteStore.create(t, picked)
                NoteActivity.open(this, nb.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 一排小封面，点一个选中。 */
    private fun coverPicker(initial: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = Covers.styles.indices.map { i ->
            CoverView(this, i, "").apply { picked = i == initial }
        }
        views.forEachIndexed { i, v ->
            v.setOnClickListener { views.forEach { it.picked = false }; v.picked = true; onPick(i) }
            row.addView(v, LinearLayout.LayoutParams(dpi(54f), dpi(72f)).apply { if (i > 0) marginStart = dpi(10f) })
        }
        return HorizontalScrollView(this).apply { addView(row) }
    }

    private fun bookMenu(anchor: View, nb: Notebook) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.shelf_rename)
        menu.menu.add(0, 2, 1, R.string.shelf_change_cover)
        menu.menu.add(0, 3, 2, R.string.delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> rename(nb)
                2 -> changeCover(nb)
                3 -> confirmDelete(nb)
            }
            true
        }
        menu.show()
    }

    private fun rename(nb: Notebook) {
        val name = EditText(this).apply { setText(nb.title); setSingleLine(); selectAll() }
        val box = LinearLayout(this).apply { setPadding(dpi(24f), dpi(8f), dpi(24f), 0); addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(this).setTitle(R.string.shelf_rename).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = name.text.toString().trim()
                if (t.isNotEmpty()) { NoteStore.rename(nb, t); refresh() }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun changeCover(nb: Notebook) {
        var picked = nb.cover
        val box = LinearLayout(this).apply { setPadding(dpi(24f), dpi(12f), dpi(24f), 0); addView(coverPicker(nb.cover) { picked = it }) }
        AlertDialog.Builder(this).setTitle(R.string.shelf_change_cover).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> NoteStore.setCover(nb, picked); refresh() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun confirmDelete(nb: Notebook) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.shelf_delete_confirm, nb.title))
            .setPositiveButton(R.string.delete) { _, _ -> NoteStore.delete(nb); refresh() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, ShelfActivity::class.java))
    }
}
