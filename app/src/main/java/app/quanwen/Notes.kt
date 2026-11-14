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

/** 一笔：颜色、粗细（页面坐标）和点（x0,y0,x1,y1,…，页面坐标）。画完就不再改。 */
class Stroke(val color: Int, val width: Float, val pts: FloatArray)

/** 贴在页上的图片（比如「留下」的 AI 回答）：文件名和位置大小（页面坐标）。 */
class NoteImage(val id: String, var x: Float, var y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
}

class NotePage(val id: String) {
    val strokes = ArrayList<Stroke>()
    val images = ArrayList<NoteImage>()
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
                if (magic != MAGIC && magic != MAGIC2) return page
                val n = inp.readInt()
                repeat(n) {
                    val color = inp.readInt()
                    val width = inp.readFloat()
                    val count = inp.readInt()
                    val pts = FloatArray(count)
                    for (i in 0 until count) pts[i] = inp.readFloat()
                    page.strokes.add(Stroke(color, width, pts))
                }
                if (magic == MAGIC2) repeat(inp.readInt()) {
                    page.images.add(NoteImage(inp.readUTF(), inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readFloat()))
                }
            }
        } catch (_: Exception) { }
        return page
    }

    /** 在后台线程调用；strokes 是调用方拍下的快照。先写临时文件再改名，写到一半断电也不会坏。 */
    fun writePage(nb: Notebook, pageId: String, strokes: List<Stroke>, images: List<NoteImage>) {
        val f = pageFile(nb, pageId)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
            out.writeInt(MAGIC2)
            out.writeInt(strokes.size)
            for (s in strokes) {
                out.writeInt(s.color)
                out.writeFloat(s.width)
                out.writeInt(s.pts.size)
                for (v in s.pts) out.writeFloat(v)
            }
            out.writeInt(images.size)
            for (im in images) { out.writeUTF(im.id); out.writeFloat(im.x); out.writeFloat(im.y); out.writeFloat(im.w); out.writeFloat(im.h) }
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

    /** 存一张贴图（PNG），返回它的 id。 */
    fun saveImage(nb: Notebook, bmp: android.graphics.Bitmap): String {
        val id = newId()
        val f = imageFile(nb, id)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return id
    }

    fun imageFile(nb: Notebook, id: String) = File(File(root, nb.id), "img_$id.png")

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)

    private const val MAGIC = 0x51575031    // "QWP1"：只有笔迹
    private const val MAGIC2 = 0x51575032   // "QWP2"：笔迹 + 贴图
}
