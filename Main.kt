package com.dlsbot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ======================= CẤU HÌNH (sửa được trong app) =======================
class Field(val key: String, val short: String, val def: Float)
class Row(val label: String, val keys: List<String>)
class Tab(val title: String, val rows: List<Row>)

object Cfg {
    val FIELDS = listOf(
        // Radar (tỉ lệ 0..1 so với màn hình ngang). Nên đặt bằng nút RADAR trong 📷
        Field("mmL", "Trái", 0.36f), Field("mmT", "Trên", 0.80f),
        Field("mmR", "Phải", 0.64f), Field("mmB", "Dưới", 0.98f),
        // Vị trí các nút
        Field("jx", "X", 0.14f), Field("jy", "Y", 0.72f), Field("jr", "Bán kính", 0.10f),
        Field("ax", "X", 0.88f), Field("ay", "Y", 0.70f),
        Field("bx", "X", 0.80f), Field("by", "Y", 0.82f),
        Field("cx", "X", 0.93f), Field("cy", "Y", 0.55f),
        Field("lx", "X", 0.95f), Field("ly", "Y", 0.80f),
        Field("wx", "X", 0.75f), Field("wy", "Y", 0.40f), Field("wlen", "Dài", 0.10f),
        // Màu radar
        Field("allyHue", "Hue", 0f), Field("allyTol", "Sai số ±", 30f),
        Field("enemyHue", "Hue (-1 = tự động)", -1f), Field("enemyTol", "Sai số ±", 35f),
        Field("pitchHue", "Hue", 120f), Field("pitchTol", "Sai số ±", 55f),
        Field("minSat", "Bão hòa ≥", 0.45f), Field("minVal", "Sáng ≥", 0.35f),
        // Chiến thuật
        Field("carryFrac", "Giữ bóng", 0.02f), Field("danger", "Nguy hiểm (m)", 7f),
        Field("dir", "1 = phải", 1f),
        Field("dead", "Vùng chết °", 12f), Field("turn", "Xoay °/s", 200f), Field("evade", "Né °/s", 600f),
        Field("thresh", "Ngưỡng", 14f),
        // Lực (ms giữ phím)
        Field("passBase", "Nền ms", 100f), Field("passPerM", "ms mỗi m", 9.5f), Field("passMax", "Tối đa", 560f),
        Field("lobBase", "Nền ms", 110f), Field("lobPerM", "ms mỗi m", 8.5f), Field("lobMax", "Tối đa", 520f),
        Field("shotNear", "Gần ms", 110f), Field("shotFar", "Xa ms", 340f),
        Field("gsBase", "Nền ms", 300f), Field("gsPerM", "ms mỗi m", 14f), Field("gsMax", "Tối đa", 650f)
    )

    val TABS = listOf(
        Tab("Nút", listOf(
            Row("Joystick", listOf("jx", "jy", "jr")),
            Row("A (sút)", listOf("ax", "ay")),
            Row("B (chuyền)", listOf("bx", "by")),
            Row("C (đổi người)", listOf("cx", "cy")),
            Row("L (bổng/tạt)", listOf("lx", "ly")),
            Row("Vuốt", listOf("wx", "wy", "wlen"))
        )),
        Tab("Radar", listOf(
            Row("Vùng radar", listOf("mmL", "mmT", "mmR", "mmB")),
            Row("Đội mình", listOf("allyHue", "allyTol")),
            Row("Đối thủ", listOf("enemyHue", "enemyTol")),
            Row("Nền sân", listOf("pitchHue", "pitchTol")),
            Row("Ngưỡng màu", listOf("minSat", "minVal"))
        )),
        Tab("Chiến thuật", listOf(
            Row("Bóng / áp sát", listOf("carryFrac", "danger")),
            Row("Hướng tấn công", listOf("dir")),
            Row("Rê bóng", listOf("dead", "turn", "evade")),
            Row("Nút menu", listOf("thresh"))
        )),
        Tab("Lực", listOf(
            Row("Chuyền sệt B", listOf("passBase", "passPerM", "passMax")),
            Row("Chuyền bổng L", listOf("lobBase", "lobPerM", "lobMax")),
            Row("Sút A", listOf("shotNear", "shotFar")),
            Row("Sút sệt B", listOf("gsBase", "gsPerM", "gsMax"))
        ))
    )

    private fun p(c: Context) = c.getSharedPreferences("cfg3", Context.MODE_PRIVATE)

    fun get(c: Context, key: String): Float =
        p(c).getFloat(key, FIELDS.first { it.key == key }.def)

    fun set(c: Context, key: String, v: Float) {
        p(c).edit().putFloat(key, v).apply()
    }
}

// ======================= NHẬN DIỆN NÚT MENU =======================
class Gray(val w: Int, val h: Int, val d: ByteArray)
class Hit(val x: Int, val y: Int, val mad: Float)
class Template(val name: String, val ingame: Boolean, val gray: Gray)
class Roi(val px: IntArray, val w: Int, val h: Int, val fresh: Boolean)

object Vision {
    const val S = 4

    fun gray(px: IntArray, W: Int, H: Int): Gray {
        val w = W / S
        val h = H / S
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (dy in 0 until S) {
                    val base = (y * S + dy) * W + x * S
                    for (dx in 0 until S) {
                        val p = px[base + dx]
                        sum += (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8
                    }
                }
                out[y * w + x] = (sum / (S * S)).toByte()
            }
        }
        return Gray(w, h, out)
    }

    fun match(f: Gray, t: Gray): Hit? {
        if (t.w < 2 || t.h < 2 || t.w > f.w || t.h > f.h) return null
        val fd = f.d
        val td = t.d
        var best = Int.MAX_VALUE
        var bx = -1
        var by = -1
        for (y in 0..(f.h - t.h)) {
            for (x in 0..(f.w - t.w)) {
                var sad = 0
                var row = 0
                while (row < t.h) {
                    val fo = (y + row) * f.w + x
                    val to = row * t.w
                    var i = 0
                    while (i < t.w) {
                        sad += abs((fd[fo + i].toInt() and 255) - (td[to + i].toInt() and 255))
                        i++
                    }
                    if (sad >= best) break
                    row++
                }
                if (row == t.h && sad < best) {
                    best = sad
                    bx = x
                    by = y
                }
            }
        }
        return if (bx < 0) null else Hit(bx, by, best.toFloat() / (t.w * t.h))
    }
}

object Store {
    fun dir(c: Context): File = File(c.filesDir, "templates").also { it.mkdirs() }

    fun count(c: Context): Int = dir(c).listFiles()?.count { it.name.endsWith(".png") } ?: 0

    fun clear(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
    }

    fun load(c: Context): List<Template> {
        val out = ArrayList<Template>()
        dir(c).listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (!f.name.endsWith(".png")) return@forEach
            val bmp = android.graphics.BitmapFactory.decodeFile(f.path) ?: return@forEach
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            out.add(Template(f.name, f.name.startsWith("ingame"), Vision.gray(px, w, h)))
        }
        return out
    }

    fun save(c: Context, bmp: Bitmap, f: FloatArray, ingame: Boolean): Boolean {
        val s = Vision.S
        var x0 = (min(f[0], f[2]) * bmp.width).toInt() / s * s
        var y0 = (min(f[1], f[3]) * bmp.height).toInt() / s * s
        var x1 = ((max(f[0], f[2]) * bmp.width).toInt() / s + 1) * s
        var y1 = ((max(f[1], f[3]) * bmp.height).toInt() / s + 1) * s
        x1 = min(x1, bmp.width / s * s)
        y1 = min(y1, bmp.height / s * s)
        x0 = max(0, x0)
        y0 = max(0, y0)
        if (x1 - x0 < 2 * s || y1 - y0 < 2 * s) return false
        val crop = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
        val name = (if (ingame) "ingame_" else "btn_") + System.currentTimeMillis() / 1000 + ".png"
        File(dir(c), name).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return true
    }
}

// ======================= QUÉT MÀN HÌNH (đường ống đa luồng) =======================
/**
 * ImageReader báo khung hình mới ngay khi có (không hỏi vòng), luồng "cap" cắt đúng vùng RADAR
 * từ bộ đệm ảnh rồi phân tích tại chỗ. Khung đầy đủ chỉ được tạo khi cần (menu / chụp template).
 */
class CaptureService : Service() {
    companion object {
        @Volatile
        var instance: CaptureService? = null
    }

    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var th: Handler? = null
    var w = 0
        private set
    var h = 0
        private set
    private var dpi = 0
    private var released = false
    private var lastFullT = 0L

    @Volatile var last: Bitmap? = null
    @Volatile var wantFull = true
    @Volatile var forceFull = false
    @Volatile var roiSpec: FloatArray? = null
    @Volatile var onRoi: ((Roi) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("cap", "Quét màn hình", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "cap")
            .setContentTitle("DLS Bot đang quét màn hình")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            intent?.getParcelableExtra("data")
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mp = mgr.getMediaProjection(code, data)
        mp?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                release()
            }
        }, Handler(Looper.getMainLooper()))
        val t = HandlerThread("cap")
        t.start()
        thread = t
        th = Handler(t.looper)
        measure()
        val rd = newReader()
        reader = rd
        vd = mp?.createVirtualDisplay(
            "dlsbot", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null
        )
        instance = this
        return START_NOT_STICKY
    }

    private fun newReader(): ImageReader {
        val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        rd.setOnImageAvailableListener({ r -> process(r) }, th)
        return rd
    }

    @Suppress("DEPRECATION")
    private fun measure() {
        val dm = DisplayMetrics()
        val d = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        d.getRealMetrics(dm)
        w = dm.widthPixels
        h = dm.heightPixels
        dpi = dm.densityDpi
    }

    /** Gọi định kỳ: nếu xoay màn hình (dọc <-> ngang) thì tạo lại vùng đệm cho đúng kích thước. */
    @Synchronized
    fun checkSize() {
        val ow = w
        val oh = h
        measure()
        if (ow != w || oh != h) {
            reader?.close()
            val rd = newReader()
            reader = rd
            vd?.resize(w, h, dpi)
            vd?.surface = rd.surface
            last = null
        }
    }

    fun grab(): Bitmap? = last

    private fun process(rd: ImageReader) {
        val img = try {
            rd.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img == null) return
        try {
            val spec = roiSpec
            val cb = onRoi
            if (spec != null && cb != null) {
                cb(readRoi(img, spec[0], spec[1], spec[2], spec[3]))
            }
            val now = System.currentTimeMillis()
            if (wantFull && (forceFull || now - lastFullT >= 250)) {
                forceFull = false
                lastFullT = now
                last = readFull(img)
            }
        } catch (e: Exception) {
        } finally {
            img.close()
        }
    }

    private fun readRoi(img: Image, f0: Float, f1: Float, f2: Float, f3: Float): Roi {
        val iw = img.width
        val ih = img.height
        val x0 = (min(f0, f2) * iw).toInt().coerceIn(0, iw - 2)
        val x1 = (max(f0, f2) * iw).toInt().coerceIn(x0 + 1, iw)
        val y0 = (min(f1, f3) * ih).toInt().coerceIn(0, ih - 2)
        val y1 = (max(f1, f3) * ih).toInt().coerceIn(y0 + 1, ih)
        val rw = x1 - x0
        val rh = y1 - y0
        val p = img.planes[0]
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val out = IntArray(rw * rh)
        for (yy in 0 until rh) {
            var off = (y0 + yy) * rs + x0 * ps
            for (xx in 0 until rw) {
                val r = buf.get(off).toInt() and 255
                val g = buf.get(off + 1).toInt() and 255
                val b = buf.get(off + 2).toInt() and 255
                out[yy * rw + xx] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                off += ps
            }
        }
        return Roi(out, rw, rh, true)
    }

    private fun readFull(img: Image): Bitmap {
        val p = img.planes[0]
        val pad = p.rowStride - p.pixelStride * img.width
        val tmp = Bitmap.createBitmap(img.width + pad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        tmp.copyPixelsFromBuffer(p.buffer)
        val bmp = Bitmap.createBitmap(tmp, 0, 0, img.width, img.height)
        if (bmp !== tmp) tmp.recycle()
        return bmp
    }

    private fun release() {
        if (released) return
        released = true
        onRoi = null
        try { vd?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { mp?.stop() } catch (e: Exception) { }
        try { thread?.quitSafely() } catch (e: Exception) { }
        instance = null
        stopSelf()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}

// ======================= VÙNG CHỌN TRÊN ẢNH CHỤP =======================
class SelectView(c: Context) : View(c) {
    private var x0 = 0f
    private var y0 = 0f
    private var x1 = 0f
    private var y1 = 0f
    private var has = false
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; x1 = x0; y1 = y0; has = true }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> { x1 = e.x; y1 = e.y }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (has) canvas.drawRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), paint)
    }

    fun fractions(): FloatArray? {
        if (!has || width == 0 || height == 0) return null
        return floatArrayOf(
            (min(x0, x1) / width).coerceIn(0f, 1f), (min(y0, y1) / height).coerceIn(0f, 1f),
            (max(x0, x1) / width).coerceIn(0f, 1f), (max(y0, y1) / height).coerceIn(0f, 1f)
        )
    }
}

// ======================= MÀN HÌNH CHÍNH: BẢNG CẤU HÌNH GỌN =======================
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private val inputs = HashMap<String, EditText>()
    private var tabIndex = 0

    private fun mkBtn(t: String, a: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.textSize = 12f
        b.isAllCaps = false
        b.setOnClickListener { a() }
        return b
    }

    private fun hrow(vararg v: View): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        for (x in v) l.addView(x, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return l
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    private fun fmt(v: Float): String =
        String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(16, 16, 16, 16)
        setContentView(root)

        status = TextView(this)
        status.textSize = 13f
        root.addView(status)

        root.addView(hrow(
            mkBtn("1. Trợ năng") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            mkBtn("2. Quét màn hình") { requestCapture() },
            mkBtn("Lưu") { saveVisible(); toast("Đã lưu") }
        ))
        root.addView(hrow(
            mkBtn("Thông tin app") {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            },
            mkBtn("Xóa mẫu menu") { Store.clear(this); refresh() }
        ))

        val tabs = ArrayList<View>()
        for ((i, t) in Cfg.TABS.withIndex()) {
            tabs.add(mkBtn(t.title) { saveVisible(); showTab(i) })
        }
        root.addView(hrow(*tabs.toTypedArray()))

        val help = TextView(this)
        help.textSize = 11f
        help.text = "Mở DLS (ngang) → thanh nổi → 📷: kéo chọn vùng rồi bấm RADAR / Joystick / A / B / C / L / Vuốt " +
            "để đặt vị trí nhanh. 🔍 hiện khung kiểm tra radar. Các ô bên dưới chỉnh tay."
        root.addView(help)

        val sv = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        sv.addView(content)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        showTab(0)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    private fun showTab(i: Int) {
        tabIndex = i
        inputs.clear()
        content.removeAllViews()
        val tab = Cfg.TABS[i]
        for (row in tab.rows) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            val lab = TextView(this)
            lab.text = row.label
            lab.textSize = 12f
            line.addView(lab, LinearLayout.LayoutParams(0, -2, 1.2f))
            for (k in row.keys) {
                val f = Cfg.FIELDS.first { it.key == k }
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                val cap = TextView(this)
                cap.text = f.short
                cap.textSize = 10f
                cap.gravity = Gravity.CENTER
                val e = EditText(this)
                e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
                e.textSize = 13f
                e.gravity = Gravity.CENTER
                e.setSingleLine()
                e.setPadding(4, 8, 4, 8)
                e.setText(fmt(Cfg.get(this, k)))
                inputs[k] = e
                cell.addView(cap, LinearLayout.LayoutParams(-1, -2))
                cell.addView(e, LinearLayout.LayoutParams(-1, -2))
                line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            content.addView(line)
        }
    }

    private fun saveVisible() {
        for ((k, e) in inputs) {
            val v = e.text.toString().trim().replace(',', '.').toFloatOrNull()
            if (v != null) Cfg.set(this, k, v)
        }
    }

    private fun requestCapture() {
        val m = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(m.createScreenCaptureIntent(), 100)
    }

    override fun onPause() {
        saveVisible()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        showTab(tabIndex) // nạp lại các giá trị vừa đặt bằng 📷
        refresh()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 800)
        }
    }

    private fun refresh() {
        val enabled = (Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: "").contains(packageName)
        val cap = CaptureService.instance != null
        status.text = "Trợ năng: " + (if (enabled) "BẬT ✅" else "chưa bật ❌") +
            "   |   Quét màn hình: " + (if (cap) "CHẠY ✅" else "chưa bật ❌") +
            "   |   Mẫu menu: " + Store.count(this)
    }
}
