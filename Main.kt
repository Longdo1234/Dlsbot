package com.dlsbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
import android.graphics.Path
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// ======================= CẤU HÌNH =======================
class Field(val key: String, val label: String, val def: Float)

object Cfg {
    val FIELDS = listOf(
        Field("jx", "Joystick - X (0-1, theo chiều rộng)", 0.14f),
        Field("jy", "Joystick - Y (0-1, theo chiều cao)", 0.72f),
        Field("jr", "Độ dài kéo joystick (theo chiều cao)", 0.10f),
        Field("sx", "Nút SÚT - X", 0.88f),
        Field("sy", "Nút SÚT - Y", 0.70f),
        Field("px", "Nút CHUYỀN - X", 0.80f),
        Field("py", "Nút CHUYỀN - Y", 0.82f),
        Field("dir", "Hướng tấn công (1 = sang phải, -1 = sang trái)", 1f),
        Field("shootEvery", "Giây giữa các lần sút", 6f),
        Field("passEvery", "Giây giữa các lần chuyền", 3.5f),
        Field("thresh", "Ngưỡng nhận diện nút (nhỏ = chặt, mặc định 14)", 14f)
    )

    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun get(c: Context, key: String): Float =
        p(c).getFloat(key, FIELDS.first { it.key == key }.def)

    fun set(c: Context, key: String, v: Float) {
        p(c).edit().putFloat(key, v).apply()
    }
}

// ======================= NHẬN DIỆN =======================
class Gray(val w: Int, val h: Int, val d: ByteArray)
class Hit(val x: Int, val y: Int, val mad: Float)
class Template(val name: String, val ingame: Boolean, val gray: Gray)

object Vision {
    const val S = 4 // hệ số thu nhỏ khi so khớp

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

    /** So khớp mẫu bằng sai khác tuyệt đối trung bình (MAD), càng nhỏ càng giống. */
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

    /** Tìm quả bóng: cụm điểm trắng nhỏ, gần tròn. Trả về [x, y] theo pixel gốc. */
    fun findBall(px: IntArray, W: Int, H: Int): FloatArray? {
        val s = 2
        val w = W / s
        val h = H / s
        val mask = BooleanArray(w * h)
        val x0 = (w * 0.2f).toInt()
        val x1 = (w * 0.85f).toInt()
        val y0 = (h * 0.12f).toInt()
        val y1 = (h * 0.9f).toInt()
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val p = px[y * s * W + x * s]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                val mn = min(r, min(g, b))
                val mx = max(r, max(g, b))
                mask[y * w + x] = mn > 200 && (mx - mn) < 35
            }
        }
        val stack = IntArray(w * h)
        var best: FloatArray? = null
        var bestScore = 0.6f
        for (start in 0 until w * h) {
            if (!mask[start]) continue
            var sp = 0
            stack[sp++] = start
            mask[start] = false
            var area = 0
            var sx = 0L
            var sy = 0L
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            while (sp > 0) {
                val c = stack[--sp]
                val cx = c % w
                val cy = c / w
                area++
                sx += cx
                sy += cy
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                if (cx > 0 && mask[c - 1]) { mask[c - 1] = false; stack[sp++] = c - 1 }
                if (cx < w - 1 && mask[c + 1]) { mask[c + 1] = false; stack[sp++] = c + 1 }
                if (cy > 0 && mask[c - w]) { mask[c - w] = false; stack[sp++] = c - w }
                if (cy < h - 1 && mask[c + w]) { mask[c + w] = false; stack[sp++] = c + w }
            }
            if (area < 12 || area > 400) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val asp = bw.toFloat() / bh
            val fill = area.toFloat() / (bw * bh)
            if (asp < 0.6f || asp > 1.7f || fill < 0.55f) continue
            val score = 1f - abs(fill - 0.785f)
            if (score > bestScore) {
                bestScore = score
                best = floatArrayOf((sx.toFloat() / area + 0.5f) * s, (sy.toFloat() / area + 0.5f) * s)
            }
        }
        return best
    }
}

// ======================= LƯU TEMPLATE =======================
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

    /** f = [x1, y1, x2, y2] theo tỉ lệ 0..1. Căn lề theo bội số của S để khớp phase khi so mẫu. */
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
    private var w = 0
    private var h = 0
    private var dpi = 0
    private var last: Bitmap? = null
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

    /** Khi xoay màn hình (dọc -> ngang), tạo lại vùng đệm cho đúng kích thước. */
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
        }
    }

    /** Lấy khung hình mới nhất (hoặc khung cũ nếu màn hình không đổi). */
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

// ======================= VÙNG CHỌN TEMPLATE =======================
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

// ======================= BOT (TRỢ NĂNG + THANH NỔI) =======================
class BotService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: BotService? = null
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var runBtn: TextView? = null
    private var menuBtn: TextView? = null
    private var editor: FrameLayout? = null
    @Volatile private var running = false
    @Volatile private var menuOnly = false
    private var lastShoot = 0L
    private var lastPass = 0L

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        panel?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        editor?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        instance = null
        return super.onUnbind(intent)
    }

    private fun toast(m: String) {
        ui.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
    }

    private fun tv(t: String, a: () -> Unit): TextView = TextView(this).apply {
        text = t
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(28, 18, 28, 18)
        setOnClickListener { a() }
    }

    private fun btn(t: String, a: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setOnClickListener { a() }
        return b
    }

    private fun buildPanel() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(0x99000000.toInt())
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 250

        val handle = tv("≡") {}
        var dx = 0f
        var dy = 0f
        var ox = 0
        var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - dx).toInt()
                    lp.y = oy + (e.rawY - dy).toInt()
                    wm.updateViewLayout(col, lp)
                }
            }
            true
        }
        val run = tv("▶") { if (running) stopBot() else startBot() }
        val snap = tv("📷") { openEditor() }
        val menu = tv("M") {
            menuOnly = !menuOnly
            menuBtn?.setTextColor(if (menuOnly) Color.YELLOW else Color.WHITE)
            toast(if (menuOnly) "Chỉ tự bấm menu: BẬT" else "Chỉ tự bấm menu: TẮT")
        }
        runBtn = run
        menuBtn = menu
        col.addView(handle)
        col.addView(run)
        col.addView(snap)
        col.addView(menu)
        wm.addView(col, lp)
        panel = col
    }

    // ---------- Tạo template: chụp màn hình, kéo chọn vùng ----------
    private fun openEditor() {
        val cap = CaptureService.instance
        if (cap == null) {
            toast("Chưa bật quét màn hình. Mở app DLS Bot và bấm 'Bật quét màn hình'")
            return
        }
        if (editor != null) return
        panel?.visibility = View.INVISIBLE
        ui.postDelayed({
            Thread {
                val bmp = cap.grab()
                ui.post {
                    panel?.visibility = View.VISIBLE
                    if (bmp == null) toast("Không chụp được màn hình") else showEditor(bmp)
                }
            }.start()
        }, 450)
    }

    private fun showEditor(bmp: Bitmap) {
        val root = FrameLayout(this)
        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        iv.scaleType = ImageView.ScaleType.FIT_XY
        val sel = SelectView(this)
        root.addView(iv, FrameLayout.LayoutParams(-1, -1))
        root.addView(sel, FrameLayout.LayoutParams(-1, -1))

        val hint = TextView(this)
        hint.text = "Kéo ngón tay quanh nút/biểu tượng cần nhận diện (chọn vùng có chữ hoặc hình rõ)"
        hint.setTextColor(Color.YELLOW)
        hint.setBackgroundColor(0xAA000000.toInt())
        hint.setPadding(20, 12, 20, 12)
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        fun close() {
            editor?.let { try { wm.removeView(it) } catch (e: Exception) { } }
            editor = null
        }

        fun save(ingame: Boolean) {
            val f = sel.fractions()
            if (f == null || !Store.save(this, bmp, f, ingame)) {
                toast("Chưa chọn vùng hợp lệ")
            } else {
                toast("Đã lưu template")
                close()
            }
        }

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        bar.setBackgroundColor(0xAA000000.toInt())
        bar.addView(btn("Lưu: NÚT BẤM") { save(false) })
        bar.addView(btn("Lưu: TRONG TRẬN") { save(true) })
        bar.addView(btn("Hủy") { close() })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val lp = WindowManager.LayoutParams(
            -1, -1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(root, lp)
        editor = root
    }

    // ---------- Chạy / dừng ----------
    private fun startBot() {
        if (running) return
        if (CaptureService.instance == null) {
            toast("Chưa bật quét màn hình. Mở app DLS Bot và bấm 'Bật quét màn hình'")
            return
        }
        running = true
        runBtn?.text = "■"
        Thread { loop() }.apply { isDaemon = true }.start()
    }

    private fun stopBot() {
        running = false
        runBtn?.text = "▶"
    }

    private fun dispatch(strokes: List<GestureDescription.StrokeDescription>): Boolean {
        val b = GestureDescription.Builder()
        for (s in strokes) b.addStroke(s)
        val latch = CountDownLatch(1)
        var ok = false
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                ok = true
                latch.countDown()
            }

            override fun onCancelled(g: GestureDescription?) {
                latch.countDown()
            }
        }
        if (!dispatchGesture(b.build(), cb, null)) return false
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    private fun tapStroke(x: Float, y: Float, start: Long, dur: Long): GestureDescription.StrokeDescription {
        val p = Path()
        p.moveTo(x, y)
        return GestureDescription.StrokeDescription(p, start, dur)
    }

    private fun loop() {
        val templates = Store.load(this)
        toast("Bot chạy với ${templates.size} template")
        val thresh = Cfg.get(this, "thresh")
        val hasIngame = templates.any { it.ingame }
        while (running) {
            try {
                val bmp = CaptureService.instance?.grab()
                if (bmp == null) {
                    Thread.sleep(300)
                    continue
                }
                val W = bmp.width
                val H = bmp.height
                val px = IntArray(W * H)
                bmp.getPixels(px, 0, W, 0, 0, W, H)
                val g = Vision.gray(px, W, H)

                var bestHit: Hit? = null
                var bestT: Template? = null
                var inGame = false
                for (t in templates) {
                    val hit = Vision.match(g, t.gray) ?: continue
                    if (hit.mad > thresh) continue
                    if (t.ingame) {
                        inGame = true
                    } else if (bestHit == null || hit.mad < bestHit.mad) {
                        bestHit = hit
                        bestT = t
                    }
                }
                val bh = bestHit
                val bt = bestT
                if (bh != null && bt != null) {
                    val tx = bh.x * Vision.S + bt.gray.w * Vision.S / 2
                    val ty = bh.y * Vision.S + bt.gray.h * Vision.S / 2
                    dispatch(listOf(tapStroke(tx.toFloat(), ty.toFloat(), 0, 60)))
                    Thread.sleep(1000)
                    continue
                }
                if (menuOnly) {
                    Thread.sleep(500)
                    continue
                }
                if (hasIngame && !inGame) {
                    Thread.sleep(400)
                    continue
                }
                play(px, W, H)
            } catch (e: Exception) {
                try { Thread.sleep(500) } catch (x: Exception) { }
            }
        }
        ui.post { runBtn?.text = "▶" }
    }

    private fun play(px: IntArray, W: Int, H: Int) {
        val jx = Cfg.get(this, "jx") * W
        val jy = Cfg.get(this, "jy") * H
        val r = Cfg.get(this, "jr") * H
        var dx = Cfg.get(this, "dir")
        var dy = 0f
        var hasBall = true
        val ball = Vision.findBall(px, W, H)
        if (ball != null) {
            val vx = ball[0] - W / 2f
            val vy = ball[1] - H / 2f
            val d = hypot(vx, vy)
            if (d > H * 0.12f) {
                dx = vx / d
                dy = vy / d
                hasBall = false // bóng ở xa -> chạy tới bóng
            }
        }
        val n = hypot(dx, dy)
        if (n > 0f) {
            dx /= n
            dy /= n
        }
        val path = Path()
        path.moveTo(jx, jy)
        path.lineTo(jx + dx * r, jy + dy * r)
        val strokes = ArrayList<GestureDescription.StrokeDescription>()
        strokes.add(GestureDescription.StrokeDescription(path, 0, 450))
        val now = System.currentTimeMillis()
        val shootGap = (Cfg.get(this, "shootEvery") * 1000).toLong()
        val passGap = (Cfg.get(this, "passEvery") * 1000).toLong()
        // Một gesture có thể có nhiều nét cùng lúc: vừa giữ joystick vừa bấm nút
        if (hasBall && now - lastShoot > shootGap) {
            strokes.add(tapStroke(Cfg.get(this, "sx") * W, Cfg.get(this, "sy") * H, 150, 60))
            lastShoot = now
        } else if (hasBall && now - lastPass > passGap) {
            strokes.add(tapStroke(Cfg.get(this, "px") * W, Cfg.get(this, "py") * H, 150, 60))
            lastPass = now
        }
        dispatch(strokes)
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
            @Suppress("DEPRECATION")
            startActivityForResult(m.createScreenCaptureIntent(), 100)
        }

        val help = TextView(this)
        help.text = "\n3. Mở DLS (chế độ ngang). Thanh nổi: ≡ kéo di chuyển, ▶ chạy/dừng, " +
            "📷 tạo template (nút bấm hoặc dấu hiệu đang trong trận), M chỉ bấm menu.\n\n" +
            "Cấu hình toạ độ (tỉ lệ 0-1 so với màn hình ngang):\n"
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
        add("Xóa toàn bộ template") {
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
            "\nTemplate đã lưu: " + Store.count(this)
    }
}
