package com.dlsbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

// ======================= HẰNG SỐ CHIẾN THUẬT (đúng theo spec) =======================
const val PL = 105f   // chiều dài sân (m) - giả định radar phủ toàn sân
const val PW = 68f    // chiều rộng sân (m)
val PIF = Math.PI.toFloat()
val RAD75 = 75f * PIF / 180f

const val T_AIM_PASS = 80L     // xoay joystick về đồng đội trước khi chuyền
const val T_PASS_B = 220L      // chuyền B ~45% lực
const val T_OT_B1 = 180L       // ban bật: chuyền 1 (B ~35%)
const val T_OT_WAIT = 380L     // chờ giữa hai lần chuyền
const val T_OT_B2 = 220L       // ban bật: chọc khe trả lại (B ~45%)
const val T_SWIPE = 120L       // vuốt đẩy bóng bứt tốc
const val T_AIM_SHOOT = 120L   // bẻ góc trụ trước khi sút
const val T_SHOOT_BOX = 120L   // A ~20% trong vòng cấm
const val T_SHOOT_GK = 650L    // B 100% khi đối mặt thủ môn
const val T_SHOOT_FAR = 300L   // A ~50% sút xa
const val T_SWITCH_C = 70L     // C: đổi sang hậu vệ
const val FOE_SPEED = 7f       // tốc độ chạy tối đa ước lượng của hậu vệ (m/s)
const val SEG = 80L            // độ dài mỗi đoạn gesture nối tiếp (ms): ngắn hơn = phản xạ nhanh hơn
val RAD15 = 15f * PIF / 180f
val RAD25 = 25f * PIF / 180f
val RAD80 = 80f * PIF / 180f
val RAD40 = 40f * PIF / 180f
val RAD30 = 30f * PIF / 180f

class P(val x: Float, val y: Float) // toạ độ mét trên sân (x: 0..105, y: 0..68)

fun dist(a: P, b: P): Float = hypot(a.x - b.x, a.y - b.y)
fun ang(a: P, b: P): Float = atan2(b.y - a.y, b.x - a.x)
fun wrap(a: Float): Float {
    var x = a
    while (x > PIF) x -= 2 * PIF
    while (x < -PIF) x += 2 * PIF
    return x
}

fun segDist(p: P, a: P, b: P): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val l2 = dx * dx + dy * dy
    if (l2 < 1e-6f) return dist(p, a)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

// ======================= RADAR =======================
class Radar(val ball: P?, val allies: List<P>, val enemies: List<P>) {
    val ok: Boolean get() = allies.size >= 4 && (enemies.size >= 2 || ball != null)
}

class Blob(val p: P, val area: Int, val fill: Float)

object RadarAnalyzer {
    private fun hueOf(r: Int, g: Int, b: Int, mx: Int, mn: Int): Float {
        val d = (mx - mn).toFloat()
        if (d == 0f) return 0f
        val hh = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        val deg = hh * 60f
        return if (deg < 0f) deg + 360f else deg
    }

    private fun hueDist(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /** Phân loại điểm ảnh theo màu rồi gom cụm: 1 = đội mình, 2 = đối thủ, 3 = bóng. */
    fun analyze(c: Context, px: IntArray, w: Int, h: Int): Radar {
        val allyHue = Cfg.get(c, "allyHue")
        val allyTol = Cfg.get(c, "allyTol")
        val enemyHue = Cfg.get(c, "enemyHue")
        val enemyTol = Cfg.get(c, "enemyTol")
        val minSat = Cfg.get(c, "minSat")
        val minVal = Cfg.get(c, "minVal")
        val pitchHue = Cfg.get(c, "pitchHue")
        val pitchTol = Cfg.get(c, "pitchTol")
        val n = w * h
        val cls = ByteArray(n)
        for (i in 0 until n) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val v = mx / 255f
            val s = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            if (v >= 0.9f && s <= 0.22f) {
                cls[i] = 3
                continue
            }
            if (s < minSat || v < minVal) continue
            val hue = hueOf(r, g, b, mx, mn)
            if (hue >= 45f && hue <= 70f && s >= 0.55f && v >= 0.9f) {
                cls[i] = 3
            } else if (hueDist(hue, allyHue) <= allyTol) {
                cls[i] = 1
            } else if (enemyHue >= 0f) {
                if (hueDist(hue, enemyHue) <= enemyTol) cls[i] = 2
            } else if (hueDist(hue, pitchHue) > pitchTol) {
                cls[i] = 2 // TỰ ĐỘNG: mọi màu bão hòa khác đội mình và khác nền sân
            }
        }

        val minA = max(2, (0.006f * w * 0.006f * w).toInt())
        val maxA = max(minA + 1, (0.05f * w * 0.05f * w).toInt())
        val stack = IntArray(n)
        val allies = ArrayList<P>()
        val enemies = ArrayList<P>()
        val brights = ArrayList<Blob>()
        for (s0 in 0 until n) {
            val kind = cls[s0].toInt()
            if (kind == 0) continue
            var sp = 0
            stack[sp++] = s0
            cls[s0] = 0
            var area = 0
            var sx = 0L
            var sy = 0L
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            while (sp > 0) {
                val c0 = stack[--sp]
                val cx = c0 % w
                val cy = c0 / w
                area++
                sx += cx
                sy += cy
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                if (cx > 0 && cls[c0 - 1].toInt() == kind) { cls[c0 - 1] = 0; stack[sp++] = c0 - 1 }
                if (cx < w - 1 && cls[c0 + 1].toInt() == kind) { cls[c0 + 1] = 0; stack[sp++] = c0 + 1 }
                if (cy > 0 && cls[c0 - w].toInt() == kind) { cls[c0 - w] = 0; stack[sp++] = c0 - w }
                if (cy < h - 1 && cls[c0 + w].toInt() == kind) { cls[c0 + w] = 0; stack[sp++] = c0 + w }
            }
            if (area < minA || area > maxA) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val asp = bw.toFloat() / bh
            val fill = area.toFloat() / (bw * bh)
            if (asp < 0.4f || asp > 2.5f || fill < 0.4f) continue
            val pt = P((sx.toFloat() / area + 0.5f) * PL / w, (sy.toFloat() / area + 0.5f) * PW / h)
            if (kind == 1) {
                allies.add(pt)
            } else if (kind == 2) {
                enemies.add(pt)
            } else {
                brights.add(Blob(pt, area, fill))
            }
        }
        var ball: P? = null
        if (brights.size >= 6) {
            // Nhiều chấm trắng/vàng: có thể đối thủ mặc áo trắng/vàng. Coi chấm nhỏ nhất là bóng.
            val sm = brights.minByOrNull { it.area }
            if (sm != null) {
                ball = sm.p
                for (bb in brights) if (bb !== sm) enemies.add(bb.p)
            }
        } else {
            ball = brights.maxByOrNull { it.fill }?.p
        }
        return Radar(ball, allies, enemies)
    }
}

// ======================= ĐA CHẠM LIÊN TỤC (GesturePump) =======================
class Track {
    var sx = 0f
    var sy = 0f
    var tx = 0f
    var ty = 0f
    var until = 0L
    var pendingMs = 0L
    var stroke: GestureDescription.StrokeDescription? = null
    var cx = 0f
    var cy = 0f
}

class Pump(private val svc: AccessibilityService) {
    private val lock = Any()
    private val names = listOf("joy", "A", "B", "C", "L", "swipe")
    private val tr: Map<String, Track> = names.associateWith { Track() }
    private val btnKeys = mapOf(
        "A" to ("ax" to "ay"), "B" to ("bx" to "by"), "C" to ("cx" to "cy"), "L" to ("lx" to "ly")
    )
    @Volatile private var alive = false
    @Volatile private var sw = 0
    @Volatile private var sh = 0
    private var lastAngle = 1000f
    private var lastChangeT = 0L

    fun setScreen(w: Int, h: Int) {
        sw = w
        sh = h
    }

    fun start() {
        alive = true
        Thread { loop() }.apply { isDaemon = true }.start()
    }

    fun stopAndRelease() {
        alive = false
    }

    fun stopAll() {
        synchronized(lock) {
            for (t in tr.values) {
                t.until = 0L
                t.pendingMs = 0L
            }
        }
    }

    /** Giữ joystick tại góc angle (rad). Ngừng gọi thì tự BUÔNG sau hold ms. */
    fun joy(angle: Float, hold: Long = 150L) {
        val t = tr.getValue("joy")
        val r = Cfg.get(svc, "jr") * sh
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (t.until <= now) lastChangeT = now
            if (abs(wrap(angle - lastAngle)) > 0.052f) {
                lastAngle = angle
                lastChangeT = now
            }
            t.sx = Cfg.get(svc, "jx") * sw
            t.sy = Cfg.get(svc, "jy") * sh
            t.tx = t.sx + cos(angle) * r
            t.ty = t.sy + sin(angle) * r
            t.until = now + hold
        }
    }

    /** Số ms joystick đã giữ yên một hướng (0 nếu đang buông). */
    fun joySteadyMs(): Long {
        val t = tr.getValue("joy")
        synchronized(lock) {
            val now = System.currentTimeMillis()
            return if (t.until <= now) 0L else now - lastChangeT
        }
    }

    /** Đè giữ nút (làm mới liên tục để giữ, ngừng gọi là nhả). */
    fun hold(name: String, ms: Long) {
        val keys = btnKeys[name] ?: return
        val t = tr.getValue(name)
        synchronized(lock) {
            t.sx = Cfg.get(svc, keys.first) * sw
            t.sy = Cfg.get(svc, keys.second) * sh
            t.tx = t.sx
            t.ty = t.sy
            val u = System.currentTimeMillis() + ms
            if (u > t.until) t.until = u
        }
    }

    /** Bấm một lần đúng ms. Nếu nút đang bị giữ thì nhả trước rồi mới bấm lại. */
    fun tap(name: String, ms: Long) {
        val keys = btnKeys[name] ?: return
        val t = tr.getValue(name)
        synchronized(lock) {
            t.sx = Cfg.get(svc, keys.first) * sw
            t.sy = Cfg.get(svc, keys.second) * sh
            t.tx = t.sx
            t.ty = t.sy
            val now = System.currentTimeMillis()
            if (t.stroke != null || t.until > now) {
                t.until = 0L
                t.pendingMs = ms
            } else {
                t.until = now + ms
            }
        }
    }

    fun release(name: String) {
        val t = tr.getValue(name)
        synchronized(lock) {
            t.until = 0L
            t.pendingMs = 0L
        }
    }

    fun swipe(dxFrac: Float, ms: Long) {
        val t = tr.getValue("swipe")
        synchronized(lock) {
            t.sx = Cfg.get(svc, "wx") * sw
            t.sy = Cfg.get(svc, "wy") * sh
            t.tx = (t.sx + dxFrac * sw).coerceIn(1f, sw - 1f)
            t.ty = t.sy
            t.until = System.currentTimeMillis() + ms
        }
    }

    fun tapAt(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        dispatch(listOf(GestureDescription.StrokeDescription(p, 0, 60)))
    }

    private fun build(): List<GestureDescription.StrokeDescription> {
        val out = ArrayList<GestureDescription.StrokeDescription>()
        synchronized(lock) {
            val now = System.currentTimeMillis()
            for (t in tr.values) {
                if (t.pendingMs > 0L && t.stroke == null && t.until <= now) {
                    t.until = now + t.pendingMs
                    t.pendingMs = 0L
                }
            }
            var seg = SEG
            for ((k, t) in tr) {
                if (k != "joy" && t.until > now) {
                    val rem = t.until - now
                    if (rem < seg) seg = max(30L, rem)
                }
            }
            for ((_, t) in tr) {
                val live = t.stroke != null
                val wants = t.until > now
                if (!live && !wants) continue
                val more = wants && t.until > now + seg
                val path = Path()
                val s: GestureDescription.StrokeDescription
                if (!live) {
                    path.moveTo(t.sx, t.sy)
                    if (t.tx != t.sx || t.ty != t.sy) path.lineTo(t.tx, t.ty)
                    s = GestureDescription.StrokeDescription(path, 0, seg, more)
                    t.cx = t.tx
                    t.cy = t.ty
                } else {
                    path.moveTo(t.cx, t.cy)
                    if (wants) {
                        if (t.tx != t.cx || t.ty != t.cy) path.lineTo(t.tx, t.ty)
                        s = t.stroke!!.continueStroke(path, 0, seg, more)
                        t.cx = t.tx
                        t.cy = t.ty
                    } else {
                        s = t.stroke!!.continueStroke(path, 0, 20, false)
                    }
                }
                t.stroke = if (more) s else null
                out.add(s)
            }
        }
        return out
    }

    private fun resetStrokes() {
        synchronized(lock) {
            for (t in tr.values) t.stroke = null
        }
    }

    private fun dispatch(strokes: List<GestureDescription.StrokeDescription>) {
        val latch = CountDownLatch(1)
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                latch.countDown()
            }

            override fun onCancelled(g: GestureDescription?) {
                resetStrokes()
                latch.countDown()
            }
        }
        val ok = try {
            val b = GestureDescription.Builder()
            for (s in strokes) b.addStroke(s)
            svc.dispatchGesture(b.build(), cb, null)
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            resetStrokes()
            Thread.sleep(50)
            return
        }
        latch.await(1, TimeUnit.SECONDS)
    }

    private fun loop() {
        while (alive) {
            val s = build()
            if (s.isEmpty()) {
                Thread.sleep(10)
                continue
            }
            dispatch(s)
        }
        // Dừng: buông TẤT CẢ các ngón (nhiều lần cho chắc)
        stopAll()
        for (i in 0 until 3) {
            val s = build()
            if (s.isNotEmpty()) dispatch(s)
            Thread.sleep(20)
        }
    }
}

// ======================= CHIẾN THUẬT (TỰ HỌC + DỰ ĐOÁN) =======================
class PassOpt(val mate: P, val lob: Boolean, val score: Float, val dist: Float)

class Plan(
    val kind: String, val btn: String, val ms: Long, val t0: Long,
    val mate: P?, val lob: Boolean, val fixedAim: Float?, val swipe: Boolean
)

/** Theo dõi quả bóng sau một cú chuyền/sút: học lực chuyền và phát hiện nút bấm sai vị trí. */
class PassTrack(val from: P, val mate: P, val lob: Boolean, val t0: Long, val btn: String, val learn: Boolean) {
    var last: P? = null
    var lastT = 0L
    var speed = 99f
}

class Mover(var x: Float, var y: Float, var vx: Float, var vy: Float, var t: Long)

/** Ghép các chấm radar giữa các khung hình để ước lượng vận tốc từng cầu thủ. */
class Tracker {
    private var items = ArrayList<Mover>()

    fun update(pts: List<P>, now: Long) {
        val old = items
        val used = BooleanArray(old.size)
        val out = ArrayList<Mover>()
        for (p in pts) {
            var bi = -1
            var bd = 3.5f
            for (i in old.indices) {
                if (used[i]) continue
                val d = hypot(p.x - old[i].x, p.y - old[i].y)
                if (d < bd) {
                    bd = d
                    bi = i
                }
            }
            if (bi >= 0) {
                used[bi] = true
                val o = old[bi]
                val dt = (now - o.t) / 1000f
                if (dt >= 0.12f) {
                    val nvx = 0.6f * o.vx + 0.4f * (p.x - o.x) / dt
                    val nvy = 0.6f * o.vy + 0.4f * (p.y - o.y) / dt
                    out.add(Mover(p.x, p.y, nvx, nvy, now))
                } else {
                    out.add(o)
                }
            } else {
                out.add(Mover(p.x, p.y, 0f, 0f, now))
            }
        }
        items = out
    }

    /** Vận tốc (m/s) của đối tượng gần điểm p nhất (trong 1.5m), giới hạn 9 m/s. */
    fun vel(p: P): FloatArray {
        var best: Mover? = null
        var bd = 1.5f
        for (m in items) {
            val d = hypot(p.x - m.x, p.y - m.y)
            if (d < bd) {
                bd = d
                best = m
            }
        }
        val b = best ?: return floatArrayOf(0f, 0f)
        val sp = hypot(b.vx, b.vy)
        val k = if (sp > 9f) 9f / sp else 1f
        return floatArrayOf(b.vx * k, b.vy * k)
    }
}

class Tactics(private val c: Context, private val pump: Pump, private val notify: (String) -> Unit) {
    var state = "-"
    var goalX = PL
    var carrier: P? = null
    var lastAct = "-"
    private var attackRight = Cfg.get(c, "dir") >= 0f
    private var vote = 0
    private var enemyGk: P? = null
    private var busyUntil = 0L
    private var holdAimUntil = 0L
    private var lastShot = 0L
    private var lastOneTwo = 0L
    private var lastSwitch = 0L
    private var lastPass = 0L
    private var lastCross = 0L
    private var heading = 0f
    private var headingInit = false
    private var lastHT = 0L
    private var runOff = 0f
    private var evadeUntil = 0L
    private var evadeAng = 0f
    private var ballPrev: P? = null
    private var ballPrevT = 0L
    private var bvx = 0f
    private var bvy = 0f
    private var carrPrev: P? = null
    private var carrPrevT = 0L
    private var cvx = 0f
    private var cvy = 0f
    private var lastR: Radar? = null
    private var plan: Plan? = null
    private var otStage = 0
    private var otT = 0L
    private var lastCr: P? = null
    private var lastCrT = 0L
    private var attackSince = 0L
    private var progBest = 1000f
    private var progT = 0L
    private var track: PassTrack? = null
    private var btnFail = 0
    private val allyTr = Tracker()
    private val foeTr = Tracker()
    private var curNow = 0L
    private var lastPasserPos: P? = null
    private var lastPassT = 0L
    private var orient = Cfg.get(c, "orient").toInt().coerceIn(0, 3)
    private var lastSent = 0f
    private var lastSentOk = false
    private val oErr = FloatArray(4)
    private var oN = 0

    fun reset() {
        busyUntil = 0L
        holdAimUntil = 0L
        state = "-"
        carrier = null
        plan = null
        track = null
        otStage = 0
        headingInit = false
        evadeUntil = 0L
        attackSince = 0L
        progT = 0L
        lastSentOk = false
    }

    private fun f2(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

    fun info(): String =
        "truc=" + orient + " heSoSet=" + f2(Cfg.get(c, "pScale")) + " heSoBong=" + f2(Cfg.get(c, "lScale")) +
            " | " + lastAct

    private fun predict(p: P, tr: Tracker, t: Float): P {
        val v = tr.vel(p)
        return P((p.x + v[0] * t).coerceIn(0f, PL), (p.y + v[1] * t).coerceIn(0f, PW))
    }

    /** Điểm cần chuyền tới: vị trí đồng đội sau khi bóng bay tới (chuyền vào khoảng trống phía trước). */
    private fun leadPoint(me: P, m: P): P {
        val t = (dist(me, m) / 20f).coerceAtMost(0.9f)
        return predict(m, allyTr, t)
    }

    // ---------- Joystick (có ánh xạ trục tự học) ----------
    private fun mapA(a: Float, k: Int): Float = when (k) {
        1 -> -a
        2 -> PIF - a
        3 -> a + PIF
        else -> a
    }

    private fun joyOut(a: Float, hold: Long) {
        val s = wrap(mapA(a, orient))
        lastSent = s
        lastSentOk = true
        pump.joy(s, hold)
    }

    private fun aimNow(a: Float, hold: Long = 250L) {
        heading = a
        headingInit = true
        joyOut(a, hold)
    }

    private fun driveTo(desired: Float, now: Long, fast: Boolean) {
        if (!headingInit) {
            heading = desired
            headingInit = true
        }
        val dt = (now - lastHT).coerceIn(1L, 100L) / 1000f
        lastHT = now
        val diff = wrap(desired - heading)
        val dead = Cfg.get(c, "dead") * PIF / 180f
        if (fast || abs(diff) > dead) {
            val rate = (if (fast) Cfg.get(c, "evade") else Cfg.get(c, "turn")) * PIF / 180f * dt
            heading = wrap(heading + diff.coerceIn(-rate, rate))
        }
        joyOut(heading, 150L)
    }

    private fun learnOrient(now: Long) {
        if (Cfg.get(c, "learn") < 0.5f) return
        val spd = hypot(cvx, cvy)
        if (spd < 3.5f || !lastSentOk || plan != null || now < busyUntil) return
        if (pump.joySteadyMs() < 600L) return
        val obs = atan2(cvy, cvx)
        for (k in 0..3) oErr[k] += abs(wrap(obs - mapA(lastSent, k)))
        oN++
        if (oN < 20) return
        var bk = orient
        var be = oErr[orient] / oN
        for (k in 0..3) {
            val m = oErr[k] / oN
            if (m < be) {
                be = m
                bk = k
            }
        }
        val cur = oErr[orient] / oN
        if (bk != orient && be < cur - 0.5f && be < 0.8f) {
            orient = bk
            Cfg.set(c, "orient", bk.toFloat())
            lastAct = "doi truc -> " + bk
        }
        for (k in 0..3) oErr[k] = 0f
        oN = 0
    }

    private fun detectGoal(r: Radar) {
        val mode = Cfg.get(c, "dirMode")
        if (mode > 0.5f) {
            attackRight = true
        } else if (mode < -0.5f) {
            attackRight = false
        } else if (r.allies.size >= 4) {
            var minX = PL
            var maxX = 0f
            for (a in r.allies) {
                if (a.x < minX) minX = a.x
                if (a.x > maxX) maxX = a.x
            }
            vote += if (minX < PL - maxX) 1 else -1
            vote = vote.coerceIn(-30, 30)
            if (vote >= 20) attackRight = true
            if (vote <= -20) attackRight = false
        }
        goalX = if (attackRight) PL else 0f
        enemyGk = r.enemies.filter { abs(it.x - goalX) < 12f }.minByOrNull { abs(it.x - goalX) }
    }

    // ---------- Lực theo khoảng cách (có hệ số tự học) ----------
    private fun clampMs(v: Float, lo: Float, hi: Float): Long = min(max(v, lo), max(lo, hi)).toLong()

    private fun passMs(d: Float): Long =
        clampMs((Cfg.get(c, "passBase") + Cfg.get(c, "passPerM") * d) * Cfg.get(c, "pScale"), 80f, Cfg.get(c, "passMax"))

    private fun lobMs(d: Float): Long =
        clampMs((Cfg.get(c, "lobBase") + Cfg.get(c, "lobPerM") * d) * Cfg.get(c, "lScale"), 80f, Cfg.get(c, "lobMax"))

    private fun shotMs(d: Float): Long {
        val t = ((d - 8f) / 20f).coerceIn(0f, 1f)
        val n = Cfg.get(c, "shotNear")
        val f = Cfg.get(c, "shotFar")
        return (n + t * (f - n)).toLong()
    }

    private fun groundShotMs(d: Float): Long {
        val base = Cfg.get(c, "gsBase")
        return clampMs(base + Cfg.get(c, "gsPerM") * (d - 6f), base, Cfg.get(c, "gsMax"))
    }

    private fun shotQuality(r: Radar, me: P, ap: P): Float {
        val d = dist(me, ap)
        val axis = if (attackRight) 0f else PIF
        val off = abs(wrap(ang(me, P(goalX, PW / 2f)) - axis))
        val distF = exp(-max(d - 6f, 0f) / 11f)
        val angF = (1f - off / RAD75).coerceIn(0f, 1f)
        var blockF = 1f
        for (e in r.enemies) {
            if (e === enemyGk) continue
            if (segDist(e, me, ap) < 2.2f) blockF *= 0.4f
        }
        val gk = enemyGk
        val gkF = if (gk != null && segDist(gk, me, ap) < 1.8f) 0.5f else 1f
        return distF * angF * blockF * gkF * 0.45f
    }

    // ---------- Theo dõi bóng sau chuyền/sút ----------
    private fun updateTrack(r: Radar, ball: P, now: Long, fresh: Boolean) {
        val tk = track ?: return
        if (fresh) {
            val lp = tk.last
            if (lp == null) {
                tk.last = ball
                tk.lastT = now
            } else if (now - tk.lastT >= 100L) {
                val dt = (now - tk.lastT) / 1000f
                tk.speed = dist(ball, lp) / dt
                tk.last = ball
                tk.lastT = now
            }
        }
        val age = now - tk.t0
        val moved = dist(ball, tk.from)
        val ally = r.allies.minByOrNull { dist(it, ball) }
        val takenByOther = ally != null && dist(ally, ball) < 2.1f && dist(ally, tk.from) > 3f
        val foeTook = r.enemies.any { dist(it, ball) < 2.5f }
        if (age > 1300L && moved < 1.5f) {
            // bấm nút mà bóng không rời chân: nút sai vị trí hoặc chưa thật sự giữ bóng
            track = null
            btnFail++
            if (btnFail >= 3) {
                btnFail = 0
                notify("Nút " + tk.btn + " bấm nhưng bóng không đi: kiểm tra vị trí nút bằng 📷")
            }
            return
        }
        val done = age > 2600L ||
            (moved > 2.5f && age > 700L && tk.speed < 1f) ||
            (moved > 2.5f && (takenByOther || foeTook))
        if (!done) return
        track = null
        btnFail = 0
        if (!tk.learn || foeTook) return
        val dx = tk.mate.x - tk.from.x
        val dy = tk.mate.y - tk.from.y
        val l2 = dx * dx + dy * dy
        if (l2 < 25f) return
        if (takenByOther && ally != null && dist(ally, tk.mate) < 4f) return // đến đúng chân đồng đội
        val along = ((ball.x - tk.from.x) * dx + (ball.y - tk.from.y) * dy) / l2
        val key = if (tk.lob) "lScale" else "pScale"
        var s = Cfg.get(c, key)
        if (along < 0.8f) {
            s *= 1f + min((0.9f - along) * 0.5f, 0.2f)
        } else if (along > 1.3f) {
            s *= 1f - min((along - 1.2f) * 0.25f, 0.15f)
        } else {
            return
        }
        s = s.coerceIn(0.6f, 1.8f)
        Cfg.set(c, key, s)
        lastAct = "hoc luc " + key + "=" + f2(s)
    }

    // ---------- Vòng lặp quyết định ----------
    fun update(r: Radar, now: Long) {
        curNow = now
        val fresh = r !== lastR
        lastR = r
        if (fresh) {
            detectGoal(r)
            allyTr.update(r.allies, now)
            foeTr.update(r.enemies, now)
        }
        val ball = r.ball
        if (ball == null) {
            carrier = null
            state = "NO_BALL"
            plan = null
            attackSince = 0L
            return
        }
        if (fresh) {
            val pb = ballPrev
            if (pb == null) {
                ballPrev = ball
                ballPrevT = now
            } else if (now - ballPrevT >= 150) {
                val dt = (now - ballPrevT) / 1000f
                bvx = 0.5f * bvx + 0.5f * (ball.x - pb.x) / dt
                bvy = 0.5f * bvy + 0.5f * (ball.y - pb.y) / dt
                ballPrev = ball
                ballPrevT = now
            }
        }
        val carryM = Cfg.get(c, "carryFrac") * PL
        var carr: P? = null
        var bd = carryM
        for (a in r.allies) {
            val d = dist(a, ball)
            if (d < bd) {
                bd = d
                carr = a
            }
        }
        if (carr != null) {
            lastCr = carr
            lastCrT = now
        } else if (state == "ATTACK" && now - lastCrT < 250L) {
            carr = lastCr
        }
        val cr = carr
        carrier = cr
        if (cr != null) {
            if (attackSince == 0L) attackSince = now
            val dg = dist(cr, P(goalX, PW / 2f))
            if (progT == 0L || dg < progBest - 2f) {
                progBest = dg
                progT = now
            }
        } else {
            attackSince = 0L
            progT = 0L
        }
        var velUpdated = false
        if (fresh) {
            if (cr != null) {
                val cp = carrPrev
                if (cp == null) {
                    carrPrev = cr
                    carrPrevT = now
                } else if (now - carrPrevT >= 200) {
                    val dt = (now - carrPrevT) / 1000f
                    cvx = 0.5f * cvx + 0.5f * (cr.x - cp.x) / dt
                    cvy = 0.5f * cvy + 0.5f * (cr.y - cp.y) / dt
                    carrPrev = cr
                    carrPrevT = now
                    velUpdated = true
                }
            } else {
                carrPrev = null
                cvx = 0f
                cvy = 0f
            }
        }
        if (velUpdated && cr != null) learnOrient(now)
        updateTrack(r, ball, now, fresh)

        val enemyNear = r.enemies.minByOrNull { dist(it, ball) }
        val ownGoalX = if (attackRight) 0f else PL
        val ownSign = if (attackRight) -1f else 1f
        state = when {
            cr != null -> "ATTACK"
            enemyNear != null && dist(enemyNear, ball) < carryM * 1.5f -> "DEFEND"
            abs(ball.x - ownGoalX) < PL / 2f || bvx * ownSign > 2f -> "DEFEND"
            else -> "LOOSE"
        }
        if (state != "ATTACK") plan = null

        if (otStage == 1) {
            if (cr != null && now - otT >= T_OT_WAIT) {
                otStage = 0
                startOT2(cr, now)
            } else if (now - otT > 1500L) {
                otStage = 0
            } else {
                return
            }
        }
        val pl = plan
        if (pl != null) {
            if (cr == null) {
                plan = null
            } else if (tickPlan(r, cr, pl, now)) {
                return
            }
        }
        if (now < busyUntil) {
            if (now < holdAimUntil) joyOut(heading, 250L)
            return
        }
        if (state != "DEFEND") pump.release("B")
        when (state) {
            "ATTACK" -> attack(r, cr!!, now)
            "DEFEND" -> defend(r, ball, enemyNear, now)
            else -> chase(r, ball, now)
        }
    }

    // ---------- Hình học & kiểm tra đường chuyền (có dự đoán chuyển động) ----------
    private fun laneFree(r: Radar, a: P, b: P, rad: Float): Boolean =
        r.enemies.none { it !== enemyGk && segDist(it, a, b) < rad }

    private fun intercepted(r: Radar, a: P, b: P, bs: Float): Boolean {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-4f) return false
        val len = sqrt(l2)
        for (f0 in r.enemies) {
            if (f0 === enemyGk) continue
            val f = predict(f0, foeTr, 0.3f)
            val t = (((f.x - a.x) * dx + (f.y - a.y) * dy) / l2).coerceIn(0f, 1f)
            val qx = a.x + t * dx
            val qy = a.y + t * dy
            val dFoe = hypot(f.x - qx, f.y - qy)
            if (dFoe < 1.6f) return true
            if (dFoe / FOE_SPEED < (t * len) / bs + 0.25f) return true
        }
        return false
    }

    /** Ngắm vùng trống: phía đối diện hướng thủ môn đang di chuyển. */
    private fun aimPoint(me: P): P {
        val mid = PW / 2f
        val gk = enemyGk
        val y = if (gk == null) {
            if (me.y < mid) mid + 2.4f else mid - 2.4f
        } else {
            val g = predict(gk, foeTr, 0.3f)
            if (g.y > mid) mid - 2.8f else mid + 2.8f
        }
        return P(goalX, y)
    }

    private fun rayScore(r: Radar, me: P, a: Float, goalAng: Float): Float {
        val ex = me.x + cos(a) * 14f
        val ey = me.y + sin(a) * 14f
        val end = P(ex, ey)
        var s = cos(wrap(a - goalAng))
        for (f0 in r.enemies) {
            val f = predict(f0, foeTr, 0.3f)
            val d = segDist(f, me, end)
            if (d < 5f) s -= (5f - d) / 5f * 1.6f
        }
        if (ex < -1f || ex > PL + 1f || ey < 1f || ey > PW - 1f) s -= 0.8f
        return s
    }

    private fun chooseRun(r: Radar, me: P, goalAng: Float): Float {
        var bestOff = runOff
        var bs = -9f
        for (k in -4..4) {
            val off = k * RAD15
            val s = rayScore(r, me, goalAng + off, goalAng) + (if (abs(off - runOff) < 0.01f) 0.25f else 0f)
            if (s > bs) {
                bs = s
                bestOff = off
            }
        }
        val curS = rayScore(r, me, goalAng + runOff, goalAng) + 0.25f
        if (bs > curS + 0.2f) runOff = bestOff
        return wrap(goalAng + runOff)
    }

    /** Chọn người nhận tốt nhất: chuyền vào vị trí đón đầu, thoáng, không bị kèm, đang chạy lên. */
    private fun bestPass(
        r: Radar, me: P, dirSign: Float, minFwd: Float, allowLob: Boolean, dMin: Float, dMax: Float
    ): PassOpt? {
        var best: PassOpt? = null
        val lpp = lastPasserPos
        for (m in r.allies) {
            if (m === me) continue
            val lp = leadPoint(me, m)
            val d = dist(me, lp)
            if (d < dMin || d > dMax) continue
            val fwd = (lp.x - me.x) * dirSign
            if (fwd < minFwd) continue
            var space = 30f
            for (e in r.enemies) space = min(space, dist(e, lp))
            if (space < 4.5f) continue
            if (abs(lp.x - goalX) < 3f && abs(lp.y - PW / 2f) > 12f) continue
            val ground = !intercepted(r, me, lp, 22f)
            val lobOk = allowLob && d >= 12f && space >= 5.5f
            if (!ground && !lobOk) continue
            val lobbing = !ground
            val mv = allyTr.vel(m)
            val pingPong = if (lpp != null && curNow - lastPassT < 6000L && dist(m, lpp) < 5f) 6f else 0f
            val s = fwd + min(space, 12f) * 0.8f - d * 0.12f - (if (lobbing) 2f else 0f) -
                pingPong + (mv[0] * dirSign).coerceIn(-3f, 5f) * 0.6f
            val cur = best
            if (cur == null || s > cur.score) best = PassOpt(m, lobbing, s, d)
        }
        return best
    }

    private fun passStillOk(r: Radar, me: P, lp: P, lob: Boolean): Boolean {
        var space = 30f
        for (e in r.enemies) space = min(space, dist(e, lp))
        if (space < 3.8f) return false
        return lob || !intercepted(r, me, lp, 22f)
    }

    private fun startPass(o: PassOpt, me: P, kind: String, now: Long) {
        val btn = if (o.lob) "L" else "B"
        val ms = if (o.lob) lobMs(o.dist) else passMs(o.dist)
        plan = Plan(kind, btn, ms, now, o.mate, o.lob, null, false)
        lastPasserPos = me
        lastPassT = now
        lastAct = "chuyen " + btn + " " + ms + "ms " + f2(o.dist) + "m"
        aimNow(ang(me, leadPoint(me, o.mate)))
    }

    private fun startOT2(me: P, now: Long) {
        val dirSign = if (attackRight) 1f else -1f
        val back = atan2(if (me.y < PW / 2f) 0.45f else -0.45f, dirSign)
        plan = Plan("OT2", "B", T_OT_B2, now, null, false, back, true)
        lastAct = "ban bat 2"
        aimNow(back)
    }

    private fun tryCross(r: Radar, me: P, now: Long): Boolean {
        val side = min(me.y, PW - me.y)
        val gx = abs(me.x - goalX)
        if (side > 14f || gx > 32f || gx < 10f) return false
        var best: P? = null
        var bs = -99f
        for (m in r.allies) {
            if (m === me) continue
            if (abs(m.x - goalX) > 18f || abs(m.y - PW / 2f) > 16f) continue
            var space = 30f
            for (e in r.enemies) space = min(space, dist(e, m))
            if (space < 3.5f) continue
            val s = min(space, 10f) - abs(m.y - PW / 2f) * 0.25f
            if (s > bs) {
                bs = s
                best = m
            }
        }
        val t = best ?: return false
        val d = dist(me, t)
        if (d < 8f) return false
        startPass(PassOpt(t, true, bs, d), me, "PASS", now)
        lastAct = "tat canh"
        lastCross = now
        return true
    }

    private fun tickPlan(r: Radar, me: P, pl: Plan, now: Long): Boolean {
        val dirSign = if (attackRight) 1f else -1f
        val aim: Float
        var tgtPt: P? = null
        val fixed = pl.fixedAim
        if (pl.kind == "SHOT") {
            val goal = P(goalX, PW / 2f)
            val axis = if (attackRight) 0f else PIF
            if (abs(wrap(ang(me, goal) - axis)) > RAD75) {
                plan = null
                return false
            }
            val ap = aimPoint(me)
            tgtPt = ap
            aim = ang(me, ap)
        } else if (fixed != null) {
            aim = fixed
        } else {
            val m = pl.mate
            if (m == null) {
                plan = null
                return false
            }
            var cur: P? = null
            var cd = 5f
            for (a in r.allies) {
                if (a === me) continue
                val d = dist(a, m)
                if (d < cd) {
                    cd = d
                    cur = a
                }
            }
            val cm = cur
            if (cm == null) {
                plan = null
                lastPass = now
                lastAct = "huy chuyen (mat nguoi nhan)"
                return false
            }
            val lp = leadPoint(me, cm)
            if (!passStillOk(r, me, lp, pl.lob)) {
                plan = null
                lastPass = now
                lastAct = "huy chuyen (nguoi nhan khong an toan)"
                return false
            }
            tgtPt = lp
            aim = ang(me, lp)
        }
        aimNow(aim)
        val waited = now - pl.t0
        val need = Cfg.get(c, "aimMs").toLong()
        var facingOk = true
        if (pl.kind == "SHOT") {
            val spd = hypot(cvx, cvy)
            if (spd > 3f) facingOk = abs(wrap(atan2(cvy, cvx) - aim)) < RAD40
        }
        if (waited >= need && pump.joySteadyMs() >= need - 40L && facingOk) {
            pump.tap(pl.btn, pl.ms)
            if (pl.swipe) pump.swipe(dirSign * Cfg.get(c, "wlen"), T_SWIPE)
            holdAimUntil = now + pl.ms + 120L
            busyUntil = now + pl.ms + 260L
            if (pl.kind == "SHOT") lastShot = now else lastPass = now
            if (pl.kind == "OT1") {
                otStage = 1
                otT = now + pl.ms
            }
            val tp = tgtPt
            if (tp != null) {
                val isPass = pl.kind == "PASS" || pl.kind == "OT1"
                track = PassTrack(me, tp, pl.lob, now, pl.btn, isPass)
            }
            plan = null
            return true
        }
        if (waited > 1000L) {
            plan = null
            if (pl.kind == "SHOT") lastShot = now else lastPass = now
            return false
        }
        return true
    }

    // ---------- KHI CÓ BÓNG ----------
    private fun attack(r: Radar, me: P, now: Long) {
        val dirSign = if (attackRight) 1f else -1f
        val goal = P(goalX, PW / 2f)
        val dGoal = dist(me, goal)
        val axis = if (attackRight) 0f else PIF
        val narrow = abs(wrap(ang(me, goal) - axis)) > RAD75
        val danger = Cfg.get(c, "danger")
        val nearest = r.enemies.minByOrNull { dist(it, me) }
        val pressed = nearest != null && dist(nearest, me) < danger * 0.7f
        val inBox = abs(me.x - goalX) <= 16.5f && abs(me.y - PW / 2f) <= 20.15f
        val ownGoalX = if (attackRight) 0f else PL
        val stuck = progT != 0L && now - progT > 3500L

        if (now - attackSince < 300L) {
            dribble(r, me, goal, now)
            return
        }
        // Thủ môn / hậu vệ sát cầu môn nhà: phát bóng an toàn, không rê
        if (abs(me.x - ownGoalX) < 9f) {
            if (now - lastPass > 1200L) {
                val o = bestPass(r, me, dirSign, 3f, true, 10f, 50f)
                    ?: bestPass(r, me, dirSign, -5f, true, 10f, 50f)
                if (o != null) {
                    startPass(o, me, "PASS", now)
                    lastAct = "phat bong " + lastAct
                    return
                }
                if (now - attackSince > 3000L) {
                    var far: P? = null
                    var fx = -999f
                    for (m in r.allies) {
                        if (m === me) continue
                        val fw = (m.x - me.x) * dirSign
                        if (fw > fx) {
                            fx = fw
                            far = m
                        }
                    }
                    val fm = far
                    if (fm != null) {
                        startPass(PassOpt(fm, true, 0f, dist(me, fm)), me, "PASS", now)
                        return
                    }
                }
            }
            return
        }
        // 1) Sút theo chất lượng
        val qThr = Cfg.get(c, "shotQ") * (if (pressed) 0.7f else 1f) * (if (stuck) 0.4f else 1f)
        val maxD = Cfg.get(c, "shotMaxD") + (if (stuck) 6f else 0f)
        if (now - lastShot > 2500L && dGoal <= maxD && !narrow) {
            val ap = aimPoint(me)
            val q = shotQuality(r, me, ap)
            if (laneFree(r, me, ap, 1.6f) && q >= qThr) {
                val btn = if (dGoal <= 20f) "B" else "A"
                val ms = if (btn == "B") groundShotMs(dGoal) else shotMs(dGoal)
                plan = Plan("SHOT", btn, ms, now, null, false, null, false)
                lastAct = "sut " + btn + " " + ms + "ms q=" + f2(q)
                aimNow(ang(me, ap))
                return
            }
        }
        // 2) Góc quá hẹp: chuyền ngược an toàn
        if (narrow && dGoal <= 25f && now - lastPass > 1500L) {
            val o = bestPass(r, me, dirSign, -25f, false, 6f, 30f)
            if (o != null) {
                startPass(o, me, "PASS", now)
                return
            }
        }
        // 3) Tạt cánh
        if (now - lastCross > 4000L && now - lastPass > 1500L && tryCross(r, me, now)) return
        // 4) Ban bật dọc biên
        val onWing = me.y < PW * 0.25f || me.y > PW * 0.75f
        if (onWing && !inBox && dGoal > 20f && now - lastOneTwo > 8000L && now - lastPass > 1500L) {
            val o = bestPass(r, me, dirSign, -3f, false, 6f, 25f)
            if (o != null) {
                lastOneTwo = now
                plan = Plan("OT1", "B", T_OT_B1, now, o.mate, false, null, false)
                lastPasserPos = me
                lastPassT = now
                lastAct = "ban bat 1"
                aimNow(ang(me, leadPoint(me, o.mate)))
                return
            }
        }
        // 5) Bị áp sát / bị chắn / kẹt: chỉ chuyền khi người nhận thuận lợi
        if (now - lastPass > 1500L) {
            val goalAng = ang(me, goal)
            val ahead = r.enemies.any { dist(it, me) < 7f && abs(wrap(ang(me, it) - goalAng)) < RAD25 }
            if (pressed || ahead || stuck) {
                val o = bestPass(r, me, dirSign, if (stuck) -8f else if (pressed) -2f else 3f, true, 8f, 38f)
                if (o != null && o.score > (if (stuck) -5f else if (pressed) 2f else 8f)) {
                    startPass(o, me, "PASS", now)
                    if (stuck) progT = now
                    return
                }
            }
        }
        // 6) Rê bóng (kẹt lâu: đổi hướng chạy)
        if (stuck) {
            runOff = if (runOff >= 0f) -RAD30 else RAD30
            progT = now
            lastAct = "ket -> doi huong"
        }
        dribble(r, me, goal, now)
    }

    private fun dribble(r: Radar, me: P, goal: P, now: Long) {
        val danger = Cfg.get(c, "danger")
        val goalAng = ang(me, goal)
        if (now < evadeUntil) {
            driveTo(evadeAng, now, true)
            return
        }
        var threat: P? = null
        var td = danger
        for (e in r.enemies) {
            val ep = predict(e, foeTr, 0.25f)
            val d = dist(ep, me)
            if (d < td && abs(wrap(ang(me, ep) - heading)) < RAD80) {
                td = d
                threat = ep
            }
        }
        val th = threat
        if (th != null) {
            val base = ang(me, th)
            val a1 = wrap(base + PIF / 2f)
            val a2 = wrap(base - PIF / 2f)
            val pick = if (rayScore(r, me, a1, goalAng) >= rayScore(r, me, a2, goalAng)) a1 else a2
            evadeAng = wrap(pick + 0.25f * wrap(goalAng - pick))
            evadeUntil = now + 500L
            lastAct = "ne hau ve"
            driveTo(evadeAng, now, true)
            return
        }
        driveTo(chooseRun(r, me, goalAng), now, false)
    }

    // ---------- KHI KHÔNG CÓ BÓNG: chặn phía khung thành nhà, chỉ áp sát (đè B) khi đã gần ----------
    private fun defend(r: Radar, ball: P, enemyBall: P?, now: Long) {
        val me = r.allies.minByOrNull { dist(it, ball) } ?: return
        val tgt = enemyBall ?: ball
        val dMe = dist(me, tgt)
        if (dMe < 9f) pump.hold("B", 400L) else pump.release("B")
        val v = if (enemyBall != null) foeTr.vel(tgt) else floatArrayOf(bvx, bvy)
        val lead = (dMe / 8f).coerceIn(0.1f, 0.8f)
        var ix = tgt.x + v[0] * lead
        var iy = tgt.y + v[1] * lead
        if (dMe > 6f) {
            val gx = if (attackRight) 0f else PL
            val gdx = gx - ix
            val gdy = PW / 2f - iy
            val gl = hypot(gdx, gdy)
            if (gl > 1f) {
                ix += gdx / gl * 2f
                iy += gdy / gl * 2f
            }
        }
        if (hypot(ix - me.x, iy - me.y) > 1.5f) driveTo(atan2(iy - me.y, ix - me.x), now, false)
        val ownSign = if (attackRight) -1f else 1f
        if ((ball.x - me.x) * ownSign > 8f && now - lastSwitch > 1500L) {
            pump.tap("C", T_SWITCH_C)
            lastSwitch = now
        }
    }

    private fun chase(r: Radar, ball: P, now: Long) {
        val me = r.allies.minByOrNull { dist(it, ball) } ?: return
        if (dist(me, ball) > 1.2f) driveTo(ang(me, ball), now, false)
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
    private var dbgView: ImageView? = null
    @Volatile private var running = false
    @Volatile private var menuOnly = false
    @Volatile private var dbgOn = false
    private var ht: HandlerThread? = null
    private var handler: Handler? = null
    private var pump: Pump? = null
    private var tactics: Tactics? = null
    private var templates: List<Template> = emptyList()
    private var matchMode = false
    @Volatile private var radar: Radar? = null
    @Volatile private var radarRoi: Roi? = null
    @Volatile private var radarT = 0L
    @Volatile private var radarCount = 0
    private var okStreak = 0
    private var lastSeenT = 0L
    private var lastRadarOk = 0L
    private var lastMenuCheck = 0L
    private var lastSizeCheck = 0L
    private var lastDbg = 0L
    private var lastFpsT = 0L
    private var lastFpsCount = 0
    private var fps = 0
    private var ballMissSince = 0L
    private var lastBallWarn = 0L

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        pump?.stopAll()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopBot()
        panel?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        editor?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        dbgView?.let { try { wm.removeView(it) } catch (e: Exception) { } }
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
        b.isAllCaps = false
        b.setOnClickListener { a() }
        return b
    }

    private fun cycleDir() {
        val cur = Cfg.get(this, "dirMode")
        val nxt = if (cur > 0.5f) -1f else if (cur < -0.5f) 0f else 1f
        Cfg.set(this, "dirMode", nxt)
        toast(
            if (nxt > 0.5f) "Hướng tấn công: LUÔN SANG PHẢI"
            else if (nxt < -0.5f) "Hướng tấn công: LUÔN SANG TRÁI"
            else "Hướng tấn công: TỰ ĐỘNG"
        )
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
        val dbg = tv("🔍") { toggleDebug() }
        val dirB = tv("⇄") { cycleDir() }
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
        col.addView(dbg)
        col.addView(dirB)
        col.addView(menu)
        wm.addView(col, lp)
        panel = col
    }

    // ---------- Debug radar ----------
    private fun toggleDebug() {
        if (dbgOn) {
            dbgOn = false
            dbgView?.let { try { wm.removeView(it) } catch (e: Exception) { } }
            dbgView = null
            return
        }
        val iv = ImageView(this)
        iv.setBackgroundColor(0xAA000000.toInt())
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        wm.addView(iv, lp)
        dbgView = iv
        dbgOn = true
        toast("Radar debug BẬT: cyan = đội mình, đỏ = đối thủ, vàng = bóng, tím = người giữ bóng")
    }

    private fun drawDebug(roi: Roi, rd: Radar, tac: Tactics) {
        val sc = 2
        val ow = roi.w * sc
        val oh = roi.h * sc
        val src = Bitmap.createBitmap(roi.px, roi.w, roi.h, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawBitmap(src, null, Rect(0, 0, ow, oh), null)
        val pt = Paint()
        pt.style = Paint.Style.STROKE
        pt.strokeWidth = 3f
        fun toXY(p: P): FloatArray = floatArrayOf(p.x / PL * ow, p.y / PW * oh)
        pt.color = Color.CYAN
        for (a in rd.allies) {
            val q = toXY(a)
            cv.drawCircle(q[0], q[1], 8f, pt)
        }
        pt.color = Color.RED
        for (e in rd.enemies) {
            val q = toXY(e)
            cv.drawCircle(q[0], q[1], 8f, pt)
        }
        rd.ball?.let {
            pt.color = Color.YELLOW
            val q = toXY(it)
            cv.drawCircle(q[0], q[1], 11f, pt)
        }
        tac.carrier?.let {
            pt.color = Color.MAGENTA
            val q = toXY(it)
            cv.drawCircle(q[0], q[1], 14f, pt)
        }
        pt.style = Paint.Style.FILL
        pt.color = Color.WHITE
        pt.textSize = 22f
        val dm = Cfg.get(this, "dirMode")
        cv.drawText(
            tac.state + "  ta=" + rd.allies.size + " dich=" + rd.enemies.size +
                " goal=" + (if (tac.goalX > 50f) "PHAI" else "TRAI") +
                (if (dm > 0.5f || dm < -0.5f) "(tay)" else "(auto)") + " fps=" + fps,
            6f, 24f, pt
        )
        cv.drawText(tac.info(), 6f, 50f, pt)
        ui.post { dbgView?.setImageBitmap(out) }
    }

    // ---------- Chụp màn hình để dạy bot ----------
    private fun openEditor() {
        val cap = CaptureService.instance
        if (cap == null) {
            toast("Chưa bật quét màn hình. Mở app DLS Bot và bấm 'Quét màn hình'")
            return
        }
        if (editor != null) return
        if (running) stopBot()
        cap.wantFull = true
        cap.forceFull = true
        panel?.visibility = View.INVISIBLE
        ui.postDelayed({ cap.forceFull = true }, 300)
        ui.postDelayed({
            val bmp = cap.grab()
            panel?.visibility = View.VISIBLE
            if (bmp == null) toast("Không chụp được màn hình") else showEditor(bmp)
        }, 750)
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
        hint.text = "Kéo ngón tay quanh đối tượng rồi bấm nút bên dưới"
        hint.setTextColor(Color.YELLOW)
        hint.setBackgroundColor(0xAA000000.toInt())
        hint.setPadding(20, 12, 20, 12)
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        fun close() {
            editor?.let { try { wm.removeView(it) } catch (e: Exception) { } }
            editor = null
        }

        fun saveTemplate() {
            val f = sel.fractions()
            if (f == null || !Store.save(this, bmp, f, false)) {
                toast("Chưa chọn vùng hợp lệ")
            } else {
                toast("Đã lưu template nút menu")
                close()
            }
        }

        fun setPos(kx: String, ky: String, isJoy: Boolean) {
            val f = sel.fractions()
            if (f == null) {
                toast("Chưa chọn vùng")
                return
            }
            Cfg.set(this, kx, (f[0] + f[2]) / 2f)
            Cfg.set(this, ky, (f[1] + f[3]) / 2f)
            if (isJoy && (f[3] - f[1]) > 0.04f) Cfg.set(this, "jr", ((f[3] - f[1]) / 2f).coerceIn(0.03f, 0.3f))
            toast("Đã đặt vị trí")
            close()
        }

        fun setRadar() {
            val f = sel.fractions()
            if (f == null || f[2] - f[0] < 0.05f || f[3] - f[1] < 0.03f) {
                toast("Kéo một vùng bao trọn RADAR")
                return
            }
            Cfg.set(this, "mmL", f[0])
            Cfg.set(this, "mmT", f[1])
            Cfg.set(this, "mmR", f[2])
            Cfg.set(this, "mmB", f[3])
            toast("Đã đặt vùng RADAR")
            close()
        }

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.gravity = Gravity.CENTER
        row1.addView(btn("RADAR") { setRadar() })
        row1.addView(btn("Joystick") { setPos("jx", "jy", true) })
        row1.addView(btn("A") { setPos("ax", "ay", false) })
        row1.addView(btn("B") { setPos("bx", "by", false) })
        row1.addView(btn("C") { setPos("cx", "cy", false) })
        row1.addView(btn("L") { setPos("lx", "ly", false) })
        row1.addView(btn("Vuốt") { setPos("wx", "wy", false) })
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.gravity = Gravity.CENTER
        row2.addView(btn("Lưu: NÚT MENU") { saveTemplate() })
        row2.addView(btn("Hủy") { close() })
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundColor(0xAA000000.toInt())
        bar.addView(row1)
        bar.addView(row2)
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
        val cap = CaptureService.instance
        if (cap == null) {
            toast("Chưa bật quét màn hình. Mở app DLS Bot và bấm 'Quét màn hình'")
            return
        }
        running = true
        runBtn?.text = "■"
        templates = Store.load(this)
        radar = null
        radarRoi = null
        radarCount = 0
        okStreak = 0
        lastSeenT = 0L
        matchMode = false
        cap.roiSpec = floatArrayOf(
            Cfg.get(this, "mmL"), Cfg.get(this, "mmT"), Cfg.get(this, "mmR"), Cfg.get(this, "mmB")
        )
        cap.wantFull = true
        cap.onRoi = { roi -> handleRoi(roi) }
        val p = Pump(this)
        pump = p
        p.start()
        tactics = Tactics(this, p) { m -> toast(m) }
        val t = HandlerThread("brain")
        t.start()
        ht = t
        val hd = Handler(t.looper)
        handler = hd
        toast("Bot chạy (" + templates.size + " template menu)")
        hd.post(tick)
    }

    private fun stopBot() {
        running = false
        runBtn?.text = "▶"
        CaptureService.instance?.let {
            it.onRoi = null
            it.roiSpec = null
            it.wantFull = true
        }
        handler?.removeCallbacksAndMessages(null)
        ht?.quitSafely()
        ht = null
        handler = null
        pump?.stopAll()
        pump?.stopAndRelease()
        pump = null
        tactics = null
    }

    private fun handleRoi(roi: Roi) {
        val rd = RadarAnalyzer.analyze(this, roi.px, roi.w, roi.h)
        radarRoi = roi
        radar = rd
        radarT = System.currentTimeMillis()
        radarCount++
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val t0 = System.currentTimeMillis()
            try {
                step()
            } catch (e: Exception) {
            }
            val dt = System.currentTimeMillis() - t0
            handler?.postDelayed(this, max(2L, 16L - dt))
        }
    }

    private fun step() {
        val cap = CaptureService.instance ?: return
        val p = pump ?: return
        val tac = tactics ?: return
        val now = System.currentTimeMillis()
        if (now - lastSizeCheck > 500) {
            lastSizeCheck = now
            cap.checkSize()
        }
        if (now - lastFpsT >= 1000) {
            val c = radarCount
            fps = c - lastFpsCount
            lastFpsCount = c
            lastFpsT = now
        }
        p.setScreen(cap.w, cap.h)
        val rd = radar
        if (dbgOn && now - lastDbg > 200 && rd != null) {
            lastDbg = now
            radarRoi?.let { drawDebug(it, rd, tac) }
        }
        if (matchMode) {
            if (rd == null) return
            if (rd.ok && now - radarT < 600) lastRadarOk = now
            if (now - lastRadarOk > 2500) {
                matchMode = false
                cap.wantFull = true
                cap.last = null
                tac.reset()
                p.stopAll()
                radar = null
                toast("Ra khỏi trận, quay lại chế độ menu")
                return
            }
            if (rd.ball == null) {
                if (ballMissSince == 0L) ballMissSince = now
                if (now - ballMissSince > 8000L && now - lastBallWarn > 20000L) {
                    lastBallWarn = now
                    toast("Radar không thấy bóng: kiểm tra vùng RADAR (📷) hoặc màu (tab Radar), bật 🔍 để xem")
                }
            } else {
                ballMissSince = 0L
            }
            if (menuOnly) {
                p.stopAll()
                return
            }
            tac.update(rd, now)
        } else {
            if (rd != null && rd.ok && radarT != lastSeenT && now - radarT < 500) {
                lastSeenT = radarT
                okStreak++
                if (okStreak >= 10) {
                    matchMode = true
                    cap.wantFull = false
                    lastRadarOk = now
                    okStreak = 0
                    toast("Đã vào trận - bot điều khiển")
                }
                return
            }
            if (rd == null || !rd.ok) okStreak = 0
            if (now - lastMenuCheck < 700) return
            lastMenuCheck = now
            val bmp = cap.grab() ?: return
            menuStep(bmp, p)
        }
    }

    private fun menuStep(bmp: Bitmap, p: Pump) {
        val W = bmp.width
        val H = bmp.height
        val px = IntArray(W * H)
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        val g = Vision.gray(px, W, H)
        val thresh = Cfg.get(this, "thresh")
        var bestHit: Hit? = null
        var bestT: Template? = null
        for (t in templates) {
            if (t.ingame) continue
            val hit = Vision.match(g, t.gray) ?: continue
            if (hit.mad > thresh) continue
            val cur = bestHit
            if (cur == null || hit.mad < cur.mad) {
                bestHit = hit
                bestT = t
            }
        }
        val bh = bestHit
        val bt = bestT
        if (bh != null && bt != null) {
            val tx = bh.x * Vision.S + bt.gray.w * Vision.S / 2
            val ty = bh.y * Vision.S + bt.gray.h * Vision.S / 2
            p.tapAt(tx.toFloat(), ty.toFloat())
        }
    }
}
