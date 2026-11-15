package app.quanwen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * 圈问自带的笔记本。
 *
 * 纸张是横向的，宽高比 √2（和用户给的参考图一样），页面坐标宽 [PAGE_W]、高 [PAGE_H]，
 * 跟屏幕大小无关，换设备、转屏幕都不变形。
 *
 * 存储：filesDir/notebooks/index.json 记所有笔记本和页面顺序；每一页单独一个二进制文件，
 * 自动保存时只写改过的页。
 */
object NotePaper {
    const val PAGE_W = 1000f
    const val PAGE_H = 707f

    /** 纸张颜色，取自用户给的参考图。 */
    const val PAPER = 0xFFF1EBDB.toInt()

    /** 三档笔的粗细（页面坐标），按参考图里「细 / 粗 / 很粗」量出来的比例。 */
    val WIDTHS = floatArrayOf(1.6f, 2.6f, 6.0f)

    /** 黑、红、蓝三支笔。 */
    val COLORS = intArrayOf(0xFF1A1A1A.toInt(), 0xFFE0322B.toInt(), 0xFF1559F0.toInt())
}

/** 一笔：颜色、粗细（页面坐标）和点（x0,y0,x1,y1,…，页面坐标）。画完就不再改（橡皮擦断一笔时换成新的几笔）。 */
class Stroke(val color: Int, val width: Float, val pts: FloatArray) {
    /** 这次打开笔记本以后第几笔（从 1 起，不存盘；读进来的旧笔迹是 0）。「一同书写」靠它分出问号前面新写的那句话。 */
    var seq = 0L

    /** 外框 [左, 上, 右, 下]（页面坐标）。橡皮擦先用它粗筛，只细看擦得到的笔。 */
    val box: FloatArray by lazy {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]; val y = pts[i + 1]
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            i += 2
        }
        floatArrayOf(l, t, r, b)
    }
}

/**
 * 「一同书写」里 AI 写在纸上的一个字：字、左端和基线（页面坐标）、字号、手写体的轻微倾斜（度）、颜色、字体、
 * 属于哪一次回答（group）、占的宽度。橡皮擦碰到就整个字擦掉。
 */
class NoteGlyph(
    val ch: String, val x: Float, val y: Float, val size: Float, val rot: Float,
    val color: Int, val font: Int, val group: String, val w: Float,
) {
    fun box() = android.graphics.RectF(x, y - size * AiInk.top(font), x + w, y + size * AiInk.bottom(font))
}

/** 笔记本里没关掉的回答框：哪道题、贴在哪一页的哪个位置（左上角，页面坐标）。下次打开笔记本还在。 */
class OpenPop(val qaId: String, val pageId: String, val x: Float, val y: Float)

/** 贴在页上的图片（比如「留下」的 AI 回答）：文件名和位置大小（页面坐标）。 */
class NoteImage(val id: String, var x: Float, var y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
}

class NotePage(val id: String) {
    val strokes = ArrayList<Stroke>()
    val images = ArrayList<NoteImage>()
    /** AI 写在纸上的字（「一同书写」）。 */
    val glyphs = ArrayList<NoteGlyph>()
    /** 每改一次加一，画面缓存靠它判断要不要重画。 */
    var version = 0
    var dirty = false

    fun changed() { version++; dirty = true }
}

class Notebook(
    val id: String,
    var title: String,
    var cover: Int,
    val created: Long,
    var modified: Long,
    val pageIds: MutableList<String>,
)

object NoteStore {
    private lateinit var root: File
    private val books = ArrayList<Notebook>()
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        root = File(ctx.filesDir, "notebooks").apply { mkdirs() }
        books.clear()
        val f = File(root, "index.json")
        if (f.exists()) try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ids = o.optJSONArray("pages") ?: JSONArray()
                books.add(Notebook(o.getString("id"), o.optString("title"), o.optInt("cover"),
                    o.optLong("created"), o.optLong("modified"),
                    MutableList(ids.length()) { ids.getString(it) }))
            }
        } catch (_: Exception) { }
        loaded = true
    }

    /** 最近改过的在前。 */
    fun list(): List<Notebook> = books.sortedByDescending { it.modified }

    fun get(id: String): Notebook? = books.firstOrNull { it.id == id }

    fun create(title: String, cover: Int): Notebook {
        val now = System.currentTimeMillis()
        val nb = Notebook(newId(), title, cover, now, now, mutableListOf(newId()))
        books.add(nb)
        saveIndex()
        return nb
    }

    fun rename(nb: Notebook, title: String) { nb.title = title; saveIndex() }
    fun setCover(nb: Notebook, cover: Int) { nb.cover = cover; saveIndex() }

    fun delete(nb: Notebook) {
        books.remove(nb)
        File(root, nb.id).deleteRecursively()
        saveIndex()
    }

    /** 在最后加一页，返回新页。 */
    fun addPage(nb: Notebook): NotePage {
        val p = NotePage(newId())
        nb.pageIds.add(p.id)
        saveIndex()
        return p
    }

    fun loadPage(nb: Notebook, pageId: String): NotePage {
        val page = NotePage(pageId)
        val f = pageFile(nb, pageId)
        if (!f.exists()) return page
        try {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { inp ->
                val magic = inp.readInt()
                if (magic != MAGIC && magic != MAGIC2 && magic != MAGIC3) return page
                val n = inp.readInt()
                repeat(n) {
                    val color = inp.readInt()
                    val width = inp.readFloat()
                    val count = inp.readInt()
                    val pts = FloatArray(count)
                    for (i in 0 until count) pts[i] = inp.readFloat()
                    page.strokes.add(Stroke(color, width, pts))
                }
                if (magic != MAGIC) repeat(inp.readInt()) {
                    page.images.add(NoteImage(inp.readUTF(), inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readFloat()))
                }
                if (magic == MAGIC3) repeat(inp.readInt()) {
                    page.glyphs.add(NoteGlyph(inp.readUTF(), inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readFloat(),
                        inp.readInt(), inp.readInt(), inp.readUTF(), inp.readFloat()))
                }
            }
        } catch (_: Exception) { }
        return page
    }

    /** 在后台线程调用；strokes 是调用方拍下的快照。先写临时文件再改名，写到一半断电也不会坏。 */
    fun writePage(nb: Notebook, pageId: String, strokes: List<Stroke>, images: List<NoteImage>, glyphs: List<NoteGlyph>) {
        val f = pageFile(nb, pageId)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
            out.writeInt(MAGIC3)
            out.writeInt(strokes.size)
            for (s in strokes) {
                out.writeInt(s.color)
                out.writeFloat(s.width)
                out.writeInt(s.pts.size)
                for (v in s.pts) out.writeFloat(v)
            }
            out.writeInt(images.size)
            for (im in images) { out.writeUTF(im.id); out.writeFloat(im.x); out.writeFloat(im.y); out.writeFloat(im.w); out.writeFloat(im.h) }
            out.writeInt(glyphs.size)
            for (g in glyphs) {
                out.writeUTF(g.ch); out.writeFloat(g.x); out.writeFloat(g.y); out.writeFloat(g.size); out.writeFloat(g.rot)
                out.writeInt(g.color); out.writeInt(g.font); out.writeUTF(g.group); out.writeFloat(g.w)
            }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    @Synchronized
    fun saveIndex() {
        val arr = JSONArray()
        for (b in books) arr.put(JSONObject()
            .put("id", b.id).put("title", b.title).put("cover", b.cover)
            .put("created", b.created).put("modified", b.modified)
            .put("pages", JSONArray(b.pageIds)))
        val f = File(root, "index.json")
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun pageFile(nb: Notebook, pageId: String) = File(File(root, nb.id), "$pageId.page")

    /** 存一张贴图，返回它的 id。回答截图存 PNG（字清楚）；照片存 JPEG（小得多）。 */
    fun saveImage(nb: Notebook, bmp: android.graphics.Bitmap, photo: Boolean = false): String {
        val id = newId()
        val f = File(File(root, nb.id), if (photo) "img_$id.jpg" else "img_$id.png")
        f.parentFile?.mkdirs()
        FileOutputStream(f).use {
            if (photo) bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
            else bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        return id
    }

    fun imageFile(nb: Notebook, id: String): File {
        val png = File(File(root, nb.id), "img_$id.png")
        return if (png.exists()) png else File(File(root, nb.id), "img_$id.jpg")
    }

    /** 笔记本目录里的一个小文本文件（比如「一同书写」里 AI 正在等他回答的询问）；没有就是 null。 */
    fun loadText(nb: Notebook, name: String): String? {
        val f = File(File(root, nb.id), name)
        return if (f.exists()) try { f.readText() } catch (_: Exception) { null } else null
    }

    /** text 为 null 时删掉这个文件。 */
    fun saveText(nb: Notebook, name: String, text: String?) {
        val f = File(File(root, nb.id).apply { mkdirs() }, name)
        if (text == null) f.delete() else try { f.writeText(text) } catch (_: Exception) { }
    }

    fun loadPops(nb: Notebook): List<OpenPop> {
        val f = File(File(root, nb.id), "pops.json")
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }
                .map { OpenPop(it.getString("qa"), it.getString("page"), it.optDouble("x", 0.0).toFloat(), it.optDouble("y", 0.0).toFloat()) }
        } catch (_: Exception) { emptyList() }
    }

    fun savePops(nb: Notebook, pops: List<OpenPop>) {
        val dir = File(root, nb.id).apply { mkdirs() }
        val a = JSONArray()
        for (p in pops) a.put(JSONObject().put("qa", p.qaId).put("page", p.pageId).put("x", p.x.toDouble()).put("y", p.y.toDouble()))
        try { File(dir, "pops.json").writeText(a.toString()) } catch (_: Exception) { }
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)

    private const val MAGIC = 0x51575031    // "QWP1"：只有笔迹
    private const val MAGIC2 = 0x51575032   // "QWP2"：笔迹 + 贴图
    private const val MAGIC3 = 0x51575033   // "QWP3"：笔迹 + 贴图 + AI 写的字
}
