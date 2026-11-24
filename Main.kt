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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Display
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
class Field(val key: String, val label: String, val def: Float)

object Cfg {
    val FIELDS = listOf(
        // Vùng RADAR (minimap) - tỉ lệ 0..1 so với màn hình ngang. Nên đặt bằng nút RADAR trong 📷
        Field("mmL", "RADAR trái (MINIMAP_LEFT)", 0.36f),
        Field("mmT", "RADAR trên (MINIMAP_TOP)", 0.80f),
        Field("mmR", "RADAR phải (MINIMAP_RIGHT)", 0.64f),
        Field("mmB", "RADAR dưới (MINIMAP_BOTTOM)", 0.98f),
        // Joystick
        Field("jx", "Joystick X (JOY_X)", 0.14f),
        Field("jy", "Joystick Y (JOY_Y)", 0.72f),
        Field("jr", "Bán kính kéo (JOY_RADIUS, theo chiều cao)", 0.10f),
        // Nút hành động
        Field("ax", "Nút A - X", 0.88f),
        Field("ay", "Nút A - Y", 0.70f),
        Field("bx", "Nút B - X", 0.80f),
        Field("by", "Nút B - Y", 0.82f),
        Field("cx", "Nút C - X", 0.93f),
        Field("cy", "Nút C - Y", 0.55f),
        Field("wx", "Điểm bắt đầu vuốt - X", 0.75f),
        Field("wy", "Điểm bắt đầu vuốt - Y", 0.40f),
        Field("wlen", "Độ dài vuốt (theo chiều rộng)", 0.10f),
        // Màu trên radar (hue 0-360)
        Field("allyHue", "Màu đội mình - hue (0 = đỏ)", 0f),
        Field("allyTol", "Dung sai hue đội mình", 30f),
        Field("enemyHue", "Màu đối thủ - hue (-1 = TỰ ĐỘNG: mọi màu khác đội mình)", -1f),
        Field("enemyTol", "Dung sai hue đối thủ (chỉ dùng khi không để -1)", 35f),
        Field("pitchHue", "Màu nền sân trên radar - hue (120 = xanh lá)", 120f),
        Field("pitchTol", "Dung sai hue nền sân (bị bỏ qua)", 55f),
        Field("minSat", "Độ bão hòa tối thiểu (0-1)", 0.45f),
        Field("minVal", "Độ sáng tối thiểu (0-1)", 0.35f),
        // Chiến thuật
        Field("carryFrac", "Ngưỡng giữ bóng (tỉ lệ chiều rộng radar)", 0.02f),
        Field("danger", "Bán kính nguy hiểm khi bị áp sát (mét)", 7f),
        Field("dir", "Hướng tấn công ban đầu (1 = phải, -1 = trái)", 1f),
        Field("thresh", "Ngưỡng nhận diện nút menu (nhỏ = chặt)", 14f)
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

// ======================= QUÉT MÀN HÌNH =======================
class CaptureService : Service() {
    companion object {
        @Volatile
        var instance: CaptureService? = null
    }

    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    var w = 0
        private set
    var h = 0
        private set
    private var dpi = 0
    private var last: Bitmap? = null
    private var lastRoi: Roi? = null
    private var lastRoiKey = ""
    private var released = false

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
        measure()
        val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = rd
        vd = mp?.createVirtualDisplay(
            "dlsbot", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null
        )
        instance = this
        return START_NOT_STICKY
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

    private fun ensureSize() {
        val ow = w
        val oh = h
        measure()
        if (ow != w || oh != h) {
            reader?.close()
            val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = rd
            vd?.resize(w, h, dpi)
            vd?.surface = rd.surface
            last = null
            lastRoi = null
        }
    }

    /** Khung hình đầy đủ (dùng cho menu và chụp template). */
    @Synchronized
    fun grab(): Bitmap? {
        ensureSize()
        val img = try {
            reader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img != null) {
            try {
                val p = img.planes[0]
                val pad = p.rowStride - p.pixelStride * img.width
                val tmp = Bitmap.createBitmap(
                    img.width + pad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888
                )
                tmp.copyPixelsFromBuffer(p.buffer)
                val bmp = Bitmap.createBitmap(tmp, 0, 0, img.width, img.height)
                if (bmp !== tmp) tmp.recycle()
                last = bmp
            } finally {
                img.close()
            }
        }
        return last
    }

    /** Chỉ đọc vùng RADAR từ bộ đệm ảnh (nhanh, dùng cho vòng lặp 15-20 FPS). */
    @Synchronized
    fun grabRoi(f0: Float, f1: Float, f2: Float, f3: Float): Roi? {
        ensureSize()
        val key = "$w,$h,$f0,$f1,$f2,$f3"
        val img = try {
            reader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img == null) {
            val lr = lastRoi
            return if (lr != null && key == lastRoiKey) Roi(lr.px, lr.w, lr.h, false) else null
        }
        try {
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
            val roi = Roi(out, rw, rh, true)
            lastRoi = roi
            lastRoiKey = key
            return roi
        } finally {
            img.close()
        }
    }

    private fun release() {
        if (released) return
        released = true
        try { vd?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { mp?.stop() } catch (e: Exception) { }
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

// ======================= MÀN HÌNH CHÍNH =======================
class MainActivity : Activity() {
    private lateinit var status: TextView
    private val inputs = HashMap<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 32, 32, 32)
        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)

        status = TextView(this)
        status.textSize = 16f
        root.addView(status)

        fun add(label: String, a: () -> Unit) {
            val bt = Button(this)
            bt.text = label
            bt.setOnClickListener { a() }
            root.addView(bt)
        }

        add("1. Mở cài đặt Trợ năng (bật 'DLS Bot')") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        add("Nếu bị chặn: mở Thông tin ứng dụng") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        add("2. Bật quét màn hình") {
            val m = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(m.createScreenCaptureIntent(), 100)
        }

        val help = TextView(this)
        help.text = "\n3. Mở DLS (chế độ ngang, đang có trận). Thanh nổi: ≡ kéo, ▶ chạy/dừng, " +
            "📷 chụp màn hình rồi: kéo vòng quanh RADAR và bấm 'RADAR'; kéo quanh joystick và bấm 'Joystick'; " +
            "chạm/kéo quanh nút A, B, C, điểm vuốt rồi bấm đúng nút. 'Lưu: NÚT MENU' để dạy bot bấm nút menu. " +
            "🔍 bật khung kiểm tra radar (vòng cyan = đội mình, vòng đỏ = đối thủ, vòng vàng = bóng), M = chỉ bấm menu.\n\n" +
            "Các thông số (có thể sửa tay):\n"
        root.addView(help)

        for (f in Cfg.FIELDS) {
            val l = TextView(this)
            l.text = f.label
            root.addView(l)
            val e = EditText(this)
            e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            e.setText(Cfg.get(this, f.key).toString())
            inputs[f.key] = e
            root.addView(e)
        }
        add("Lưu cấu hình") {
            for (f in Cfg.FIELDS) {
                inputs[f.key]?.text?.toString()?.toFloatOrNull()?.let { Cfg.set(this, f.key, it) }
            }
            Toast.makeText(this, "Đã lưu cấu hình", Toast.LENGTH_SHORT).show()
        }
        add("Cập nhật ô nhập từ giá trị đã đặt bằng 📷") {
            for (f in Cfg.FIELDS) inputs[f.key]?.setText(Cfg.get(this, f.key).toString())
        }
        add("Xóa toàn bộ template menu") {
            Store.clear(this)
            refresh()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    override fun onResume() {
        super.onResume()
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
        status.text = "Trợ năng: " + (if (enabled) "ĐÃ BẬT ✅" else "chưa bật ❌") +
            "\nQuét màn hình: " + (if (cap) "ĐANG CHẠY ✅" else "chưa bật ❌") +
            "\nTemplate menu đã lưu: " + Store.count(this)
    }
}
