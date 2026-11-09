package app.quanwen

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.max

/**
 * App 内圈题：不用悬浮条和无障碍（比如鸿蒙卓易通里开不了无障碍）。
 * 图片来源：最新截图、截屏、相册、粘贴、拖进来、拍照（拍照入口在设置页和桌面快捷方式）。
 * 分屏时一半笔记一半圈问，用系统截屏后这里会自动载入新截图；也可以直接点「截屏」。
 */
class CircleActivity : Activity() {
    private lateinit var lasso: ImageLassoView
    private lateinit var answer: AnswerView
    private lateinit var content: LinearLayout
    private lateinit var empty: TextView
    private lateinit var hint: TextView
    private lateinit var dropHint: TextView
    private val main = Handler(Looper.getMainLooper())

    /** 正在跟随最新截图：有新截图就自动换上。 */
    private var followLatest = false
    private var loadedImageId = -1L
    private var observer: ContentObserver? = null

    private val onQaChanged: (Qa, Boolean) -> Unit = { qa, _ -> answer.refresh(qa) }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        window.statusBarColor = Colors.PAPER
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        build()
        Engine.addListener(onQaChanged)
        when (intent.getStringExtra(EXTRA_SOURCE)) {
            SOURCE_PICK -> pick()
            SOURCE_CAMERA -> camera()
            else -> latest(userAsked = false)
        }
    }

    /** 圈问离开屏幕（切后台、被别的 App 盖住）就停止录屏；回来再截时重新授权一次。 */
    override fun onStop() {
        super.onStop()
        if (ScreenGrabber.active && !isChangingConfigurations) stopService(Intent(this, CaptureService::class.java))
    }

    override fun onDestroy() {
        if (ScreenGrabber.active) stopService(Intent(this, CaptureService::class.java))
        Engine.removeListener(onQaChanged)
        observer?.let { contentResolver.unregisterContentObserver(it) }
        answer.destroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)   // 排版由 content 的尺寸变化触发，见 build()
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colors.PAPER)
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(6f))
        }
        bar.addView(button(getString(R.string.src_latest)) { latest(userAsked = true) })
        bar.addView(button(getString(R.string.src_pick)) { pick() })
        bar.addView(button(getString(R.string.src_capture)) { captureScreen() })
        bar.addView(button(getString(R.string.src_paste)) { paste() })
        hint = TextView(this).apply {
            textSize = 13f; setTextColor(Colors.MUTED); setPadding(dpi(12f), 0, 0, 0)
            text = getString(R.string.circle_hint); maxLines = 2
        }
        bar.addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 右上角 ☰：新建会话、切换会话
        bar.addView(TextView(this).apply {
            text = "☰"; textSize = 22f; setTextColor(Colors.INK); gravity = Gravity.CENTER
            setPadding(dpi(14f), dpi(4f), dpi(10f), dpi(4f))
            setOnClickListener {
                answer.visibility = View.VISIBLE; arrange()
                answer.showSessions()
            }
        })
        root.addView(bar)

        content = LinearLayout(this)
        val canvasBox = FrameLayout(this)
        lasso = ImageLassoView(this) { strokes -> ask(strokes) }
        canvasBox.addView(lasso, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty = TextView(this).apply {
            text = getString(R.string.circle_empty); textSize = 15f; setTextColor(Colors.MUTED)
            gravity = Gravity.CENTER; setPadding(dpi(32f), dpi(32f), dpi(32f), dpi(32f))
        }
        canvasBox.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dropHint = TextView(this).apply {
            text = getString(R.string.drop_hint); textSize = 18f; setTextColor(Colors.ACCENT)
            gravity = Gravity.CENTER; visibility = View.GONE
            background = rounded(0xE6FFFDF8.toInt(), dp(14f), Colors.ACCENT, dpi(3f))
        }
        canvasBox.addView(dropHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            .apply { setMargins(dpi(8f), dpi(8f), dpi(8f), dpi(8f)) })
        setupDrop(canvasBox)
        content.addView(canvasBox)
        answer = AnswerView(this, fill = true, maxBodyHeight = { 0 }, onClose = { answer.visibility = View.GONE; arrange() })
        answer.visibility = View.GONE
        content.addView(answer)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        // 尺寸真正变了（旋转、分屏拖动分隔条）再决定横排还是竖排
        content.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val want = if (r - l > b - t) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (content.orientation != want) content.post { arrange() }
        }
        content.post { arrange() }
    }

    /** 宽屏时回答放右边，窄屏（比如分屏的一半）放下面。 */
    private fun arrange() {
        val wide = content.width > content.height
        content.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val canvasBox = content.getChildAt(0)
        val m = dpi(8f)
        canvasBox.layoutParams = if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f)
                                 else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f)
        answer.layoutParams = (if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f)
                               else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f)).apply { setMargins(m, m, m, m) }
        content.requestLayout()
    }

    // ---------- 提问 ----------

    private fun ask(strokes: List<FloatArray>) {
        val b = lasso.bitmap ?: return
        val qa = Engine.ask(b, strokes, screenBounds = null, recycleWhenDone = false)
        if (answer.visibility != View.VISIBLE) { answer.visibility = View.VISIBLE; arrange() }
        answer.showQa(qa)
    }

    // ---------- 图片来源 ----------

    private fun show(b: Bitmap) {
        lasso.setImage(b)
        empty.visibility = View.GONE
    }

    /** 最新截图。userAsked=false 时（刚打开页面）没权限就先不弹窗请求。 */
    private fun latest(userAsked: Boolean) {
        if (!hasPhotoPermission()) {
            if (userAsked || intent.getStringExtra(EXTRA_SOURCE) == SOURCE_LATEST) requestPermissions(arrayOf(photoPermission()), REQ_PERMISSION)
            return
        }
        followLatest = true
        watchScreenshots()
        val found = queryLatestImage()
        if (found == null) { if (userAsked) toast(getString(R.string.no_screenshot)); return }
        loadInto(found.first, found.second, announce = userAsked)
    }

    private fun pick() {
        followLatest = false
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PICK)
    }

    private fun camera() {
        followLatest = false
        val file = CameraFileProvider.file(this).apply { delete() }
        val uri = CameraFileProvider.uri(this)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(i, REQ_CAMERA) } catch (e: Exception) { toast(getString(R.string.no_camera)) }
        file.parentFile?.mkdirs()
    }

    // ---------- 截屏（录屏接口，不需要无障碍） ----------

    /** 第一次点：系统弹一次录屏授权；之后这个页面开着，每点一次截一张。 */
    private fun captureScreen() {
        followLatest = false
        if (ScreenGrabber.active) { grabNow(); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val i = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else mpm.createScreenCaptureIntent()
        try { startActivityForResult(i, REQ_CAPTURE) } catch (e: Exception) { toast(getString(R.string.capture_failed)) }
    }

    private fun grabNow() {
        ScreenGrabber.grab { b ->
            if (b == null) { toast(getString(R.string.capture_failed)); return@grab }
            loadedImageId = -1L
            show(cropOutSelf(b))
        }
    }

    /** 分屏时把圈问自己这半屏裁掉，只留笔记那一半；全屏时不裁。 */
    private fun cropOutSelf(b: Bitmap): Bitmap {
        val dv = window.decorView
        val loc = IntArray(2); dv.getLocationOnScreen(loc)
        val sx = b.width; val sy = b.height
        val r = Rect(loc[0], loc[1], loc[0] + dv.width, loc[1] + dv.height)
        val keep: Rect? = when {
            r.width() >= sx * 0.85f && r.height() >= sy * 0.85f -> null
            r.left >= sx * 0.25f -> Rect(0, 0, r.left, sy)
            r.right <= sx * 0.75f -> Rect(r.right, 0, sx, sy)
            r.top >= sy * 0.25f -> Rect(0, 0, sx, r.top)
            r.bottom <= sy * 0.75f -> Rect(0, r.bottom, sx, sy)
            else -> null
        }
        if (keep == null || keep.width() < 64 || keep.height() < 64) return b
        return Bitmap.createBitmap(b, keep.left, keep.top, keep.width(), keep.height()).also { if (it !== b) b.recycle() }
    }

    // ---------- 拖进来、粘贴 ----------

    /**
     * 把图片拖到画布上就载入（比如截屏后拖动系统的截图缩略图，或者从相册里拖一张）。
     * 来源千奇百怪（鸿蒙经卓易通转过来的未必标成图片类型），所以什么拖动都先接住，松手时再找里面的图片。
     */
    private fun setupDrop(target: View) {
        target.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> { dropHint.visibility = View.VISIBLE; true }
                DragEvent.ACTION_DRAG_EXITED -> { dropHint.visibility = View.GONE; true }
                DragEvent.ACTION_DROP -> {
                    dropHint.visibility = View.GONE
                    val perms = try { requestDragAndDropPermissions(e) } catch (_: Exception) { null }
                    val uri = firstImageUri(e.clipData)
                    if (uri == null) { perms?.release(); toast(getString(R.string.drop_failed)); false }
                    else { followLatest = false; loadInto(uri, -1L, announce = false) { perms?.release() }; true }
                }
                DragEvent.ACTION_DRAG_ENDED -> { dropHint.visibility = View.GONE; true }
                else -> true
            }
        }
    }

    private fun paste() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val uri = firstImageUri(cm.primaryClip)
        if (uri == null) { toast(getString(R.string.paste_empty)); return }
        followLatest = false
        loadInto(uri, -1L, announce = false)
    }

    /** 从拖动或剪贴板的内容里找第一个能当图片打开的地址。 */
    private fun firstImageUri(clip: ClipData?): Uri? {
        clip ?: return null
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            item.uri?.let { return it }
            val t = item.text?.toString()?.trim() ?: continue
            if (t.startsWith("content://") || t.startsWith("file://")) return Uri.parse(t)
            if (t.startsWith("/") && File(t).exists()) return Uri.fromFile(File(t))
        }
        return null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) { toast(getString(R.string.capture_denied)); return }
            ScreenGrabber.onReady = { main.postDelayed({ grabNow() }, 600) }   // 等第一帧画面进来
            startForegroundService(Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, resultCode).putExtra(CaptureService.EXTRA_DATA, data))
            return
        }
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK -> data?.data?.let { loadInto(it, -1L, announce = false) }
            REQ_CAMERA -> {
                val f = CameraFileProvider.file(this)
                if (f.exists() && f.length() > 0) loadInto(Uri.fromFile(f), -1L, announce = false)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION) {
            if (hasPhotoPermission()) latest(userAsked = true) else toast(getString(R.string.need_photo_permission))
        }
    }

    /** 后台解码（大图缩到长边 3200，照片按 EXIF 转正），再显示。 */
    private fun loadInto(uri: Uri, imageId: Long, announce: Boolean, onFinished: () -> Unit = {}) {
        Thread {
            val b = try { decode(uri) } catch (e: Exception) { null }
            runOnUiThread {
                onFinished()
                if (b == null) { toast(getString(R.string.load_failed)); return@runOnUiThread }
                loadedImageId = imageId
                show(b)
                if (announce) toast(getString(R.string.latest_loaded))
            }
        }.start()
    }

    private fun decode(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        val b = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        if (rotation == 0f) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rotation) }, true)
    }

    // ---------- 最新截图 ----------

    private fun photoPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPhotoPermission() = checkSelfPermission(photoPermission()) == PackageManager.PERMISSION_GRANTED

    /** 优先找截图文件夹里最新的一张；没有就取所有图片里最新的一张。返回 (uri, id)。 */
    private fun queryLatestImage(): Pair<Uri, Long>? {
        val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Images.Media._ID)
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val shotSel = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val shotArgs = arrayOf("%creenshot%", "%creenshot%")
        for ((sel, args) in listOf(shotSel to shotArgs, null to null)) {
            try {
                contentResolver.query(col, proj, sel, args, order)?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(0)
                        return ContentUris.withAppendedId(col, id) to id
                    }
                }
            } catch (_: Exception) { }
        }
        return null
    }

    /** 有新截图就自动换上（分屏时用系统截屏，圈问这边立刻能圈）。 */
    private fun watchScreenshots() {
        if (observer != null) return
        val pending = Runnable {
            if (!followLatest) return@Runnable
            val found = queryLatestImage() ?: return@Runnable
            if (found.second != loadedImageId) loadInto(found.first, found.second, announce = true)
        }
        observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                main.removeCallbacks(pending)
                main.postDelayed(pending, 900)   // 截图文件写完要一会儿
            }
        }.also { contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, it) }
    }

    // ---------- 小工具 ----------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(Colors.INK); gravity = Gravity.CENTER
        setPadding(dpi(14f), dpi(8f), dpi(14f), dpi(8f))
        background = rounded(0x0F000000, dp(10f), 0x22000000, dpi(1f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dpi(8f) }
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val SOURCE_LATEST = "latest"
        const val SOURCE_PICK = "pick"
        const val SOURCE_CAMERA = "camera"
        private const val REQ_PICK = 1
        private const val REQ_CAMERA = 2
        private const val REQ_PERMISSION = 3
        private const val REQ_CAPTURE = 4

        fun open(context: Context, source: String) =
            context.startActivity(Intent(context, CircleActivity::class.java).putExtra(EXTRA_SOURCE, source))
    }
}

/** 只给相机用：提供一个可写的文件，拍的照片存到这里（不依赖 AndroidX 的 FileProvider）。 */
class CameraFileProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = file(context!!)
        f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }
    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        fun file(c: Context) = File(c.cacheDir, "camera/photo.jpg")
        fun uri(c: Context): Uri = Uri.parse("content://${c.packageName}.camera/photo.jpg")
    }
}
