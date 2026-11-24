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
/**
 * Mỗi "ngón tay ảo" là một Track. Mọi nét được gửi chung trong từng gesture ngắn (SEG ms)
 * và nối tiếp bằng continueStroke(willContinue = true), nên joystick không bao giờ bị nhấc lên,
 * còn các nút A/B/C/L và vuốt được thêm vào cùng gesture mà không làm đứt joystick.
 * (Gửi gesture riêng lẻ sẽ khiến Android HỦY gesture đang chạy, nên phải gộp như vậy.)
 */
class Track {
    var sx = 0f
    var sy = 0f
    var tx = 0f
    var ty = 0f
    var until = 0L
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
            for (t in tr.values) t.until = 0L
        }
    }

    /** Giữ joystick tại góc angle (rad). Gọi lại liên tục để làm mới; tự nhả sau hold ms nếu ngừng gọi. */
    fun joy(angle: Float, hold: Long = 500L) {
        val t = tr.getValue("joy")
        val r = Cfg.get(svc, "jr") * sh
        synchronized(lock) {
            t.sx = Cfg.get(svc, "jx") * sw
            t.sy = Cfg.get(svc, "jy") * sh
            t.tx = t.sx + cos(angle) * r
            t.ty = t.sy + sin(angle) * r
            t.until = System.currentTimeMillis() + hold
        }
    }

    fun press(name: String, ms: Long) {
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

    fun release(name: String) {
        val t = tr.getValue(name)
        synchronized(lock) { t.until = 0L }
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
        latch.await(2, TimeUnit.SECONDS)
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
        stopAll()
        for (i in 0 until 2) {
            val s = build()
            if (s.isNotEmpty()) dispatch(s)
        }
    }
}

// ======================= CHIẾN THUẬT =======================
class PassOpt(val mate: P, val lob: Boolean, val score: Float, val dist: Float)
class ShotPlan(val btn: String, val ms: Long, val t0: Long)

class Tactics(private val c: Context, private val pump: Pump) {
    var state = "-"
    var goalX = PL
    var carrier: P? = null
    private var attackRight = Cfg.get(c, "dir") >= 0f
    private var vote = 0
    private var enemyGk: P? = null
    private val sched = ArrayList<Pair<Long, () -> Unit>>()
    private var busyUntil = 0L
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
    private var shotPlan: ShotPlan? = null

    fun reset() {
        sched.clear()
        busyUntil = 0L
        state = "-"
        carrier = null
        shotPlan = null
        headingInit = false
        evadeUntil = 0L
    }

    private fun at(delay: Long, now: Long, f: () -> Unit) {
        sched.add(Pair(now + delay, f))
    }

    // ---------- Điều khiển joystick mượt ----------
    /** Xoay tức thì (dùng khi ngắm chuyền/sút). */
    private fun aimNow(a: Float, hold: Long = 500L) {
        heading = a
        headingInit = true
        pump.joy(a, hold)
    }

    /**
     * Rê bóng mượt: có vùng chết (không đổi hướng khi lệch nhỏ) và giới hạn tốc độ xoay,
     * chỉ xoay nhanh (fast = true) khi cần ngoặt né hậu vệ.
     */
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
        pump.joy(heading)
    }

    /**
     * Thủ môn đội mình đứng sát biên khung thành nhà -> tấn công về phía ngược lại.
     * Thủ môn đối phương = chấm đối thủ gần khung thành cần ghi nhất.
     */
    private fun detectGoal(r: Radar) {
        if (r.allies.size < 4) return
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
        goalX = if (attackRight) PL else 0f
        enemyGk = r.enemies.filter { abs(it.x - goalX) < 12f }.minByOrNull { abs(it.x - goalX) }
    }

    // ---------- Lực theo khoảng cách ----------
    private fun clampMs(v: Float, lo: Float, hi: Float): Long = min(max(v, lo), max(lo, hi)).toLong()

    private fun passMs(d: Float): Long =
        clampMs(Cfg.get(c, "passBase") + Cfg.get(c, "passPerM") * d, 80f, Cfg.get(c, "passMax"))

    private fun lobMs(d: Float): Long =
        clampMs(Cfg.get(c, "lobBase") + Cfg.get(c, "lobPerM") * d, 80f, Cfg.get(c, "lobMax"))

    /** Sút A: gần gôn nhẹ (tránh vọt xà), xa ngoài vòng cấm mạnh hơn. */
    private fun shotMs(d: Float): Long {
        val t = ((d - 8f) / 20f).coerceIn(0f, 1f)
        val n = Cfg.get(c, "shotNear")
        val f = Cfg.get(c, "shotFar")
        return (n + t * (f - n)).toLong()
    }

    /** Sút sệt (đè B): càng xa càng giữ lâu. */
    private fun groundShotMs(d: Float): Long {
        val base = Cfg.get(c, "gsBase")
        return clampMs(base + Cfg.get(c, "gsPerM") * (d - 6f), base, Cfg.get(c, "gsMax"))
    }

    // ---------- Vòng lặp quyết định ----------
    fun update(r: Radar, now: Long) {
        val due = sched.filter { it.first <= now }
        if (due.isNotEmpty()) {
            sched.removeAll(due.toSet())
            for (d in due) d.second()
        }
        val fresh = r !== lastR
        lastR = r
        if (fresh) detectGoal(r)
        val ball = r.ball
        if (ball == null) {
            carrier = null
            state = "NO_BALL"
            shotPlan = null
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
        val cr = carr
        carrier = cr
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
                }
            } else {
                carrPrev = null
                cvx = 0f
                cvy = 0f
            }
        }
        val enemyNear = r.enemies.minByOrNull { dist(it, ball) }
        val ownGoalX = if (attackRight) 0f else PL
        val ownSign = if (attackRight) -1f else 1f
        state = when {
            cr != null -> "ATTACK"
            enemyNear != null && dist(enemyNear, ball) < carryM * 1.5f -> "DEFEND"
            abs(ball.x - ownGoalX) < PL / 2f || bvx * ownSign > 2f -> "DEFEND"
            else -> "LOOSE"
        }
        if (state != "ATTACK") shotPlan = null
        if (shotPlan != null && cr != null) {
            if (tickShot(r, cr, now)) return
        }
        if (now < busyUntil) return
        if (state != "DEFEND") pump.release("B")
        when (state) {
            "ATTACK" -> attack(r, cr!!, now)
            "DEFEND" -> defend(r, ball, enemyNear, now)
            else -> chase(r, ball, now)
        }
    }

    // ---------- Hình học & kiểm tra đường chuyền ----------
    private fun laneFree(r: Radar, a: P, b: P, rad: Float): Boolean =
        r.enemies.none { it !== enemyGk && segDist(it, a, b) < rad }

    /** Bóng bay với tốc độ bs (m/s): có hậu vệ nào chạy tới chặn kịp không. */
    private fun intercepted(r: Radar, a: P, b: P, bs: Float): Boolean {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-4f) return false
        val len = sqrt(l2)
        for (f in r.enemies) {
            if (f === enemyGk) continue
            val t = (((f.x - a.x) * dx + (f.y - a.y) * dy) / l2).coerceIn(0f, 1f)
            val qx = a.x + t * dx
            val qy = a.y + t * dy
            val dFoe = hypot(f.x - qx, f.y - qy)
            if (dFoe < 1.6f) return true
            if (dFoe / FOE_SPEED < (t * len) / bs + 0.25f) return true
        }
        return false
    }

    /** Điểm ngắm trong khung thành: ưu tiên phía xa thủ môn (vùng trống). */
    private fun aimPoint(me: P): P {
        val mid = PW / 2f
        val gk = enemyGk
        val y = when {
            gk == null -> if (me.y < mid) mid + 2.4f else mid - 2.4f
            gk.y > mid -> mid - 2.8f
            else -> mid + 2.8f
        }
        return P(goalX, y)
    }

    private fun rayScore(r: Radar, me: P, a: Float, goalAng: Float): Float {
        val ex = me.x + cos(a) * 14f
        val ey = me.y + sin(a) * 14f
        val end = P(ex, ey)
        var s = cos(wrap(a - goalAng))
        for (f in r.enemies) {
            val d = segDist(f, me, end)
            if (d < 5f) s -= (5f - d) / 5f * 1.6f
        }
        if (ex < -1f || ex > PL + 1f || ey < 1f || ey > PW - 1f) s -= 0.8f
        return s
    }

    /** Chọn hướng chạy vào khoảng trống; chỉ đổi khi tốt hơn rõ rệt (chống zigzag). */
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

    /** Chọn đồng đội nhận bóng tốt nhất: đường chuyền thoáng, không bị kèm, vị trí thuận lợi. */
    private fun bestPass(
        r: Radar, me: P, dirSign: Float, minFwd: Float, allowLob: Boolean, dMin: Float, dMax: Float
    ): PassOpt? {
        var best: PassOpt? = null
        for (m in r.allies) {
            if (m === me) continue
            val d = dist(me, m)
            if (d < dMin || d > dMax) continue
            val fwd = (m.x - me.x) * dirSign
            if (fwd < minFwd) continue
            var space = 30f
            for (e in r.enemies) space = min(space, dist(e, m))
            if (space < 4.5f) continue
            if (abs(m.x - goalX) < 3f && abs(m.y - PW / 2f) > 12f) continue
            val ground = !intercepted(r, me, m, 22f)
            val lobOk = allowLob && d >= 12f && space >= 5.5f
            if (!ground && !lobOk) continue
            val lobbing = !ground
            val s = fwd + min(space, 12f) * 0.8f - d * 0.12f - (if (lobbing) 2f else 0f)
            val cur = best
            if (cur == null || s > cur.score) best = PassOpt(m, lobbing, s, d)
        }
        return best
    }

    private fun doPass(o: PassOpt, me: P, now: Long) {
        val btn = if (o.lob) "L" else "B"
        val ms = if (o.lob) lobMs(o.dist) else passMs(o.dist)
        aimNow(ang(me, o.mate), 700L)
        busyUntil = now + T_AIM_PASS + ms + 140
        lastPass = now
        at(T_AIM_PASS, now) { pump.press(btn, ms) }
    }

    /** Tạt cánh: đứng sát biên ngang gần vòng cấm và có đồng đội thoáng trong vòng cấm. */
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
        doPass(PassOpt(t, true, bs, d), me, now)
        lastCross = now
        return true
    }

    private fun oneTwo(me: P, mate: P, now: Long) {
        val dirSign = if (attackRight) 1f else -1f
        lastOneTwo = now
        aimNow(ang(me, mate), 1500L)
        val t2 = T_AIM_PASS + T_OT_B1 + T_OT_WAIT
        busyUntil = now + t2 + T_OT_B2 + 150
        at(T_AIM_PASS, now) { pump.press("B", T_OT_B1) }
        val back = atan2(if (me.y < PW / 2f) 0.45f else -0.45f, dirSign)
        at(t2 - 60, now) { aimNow(back, 900L) }
        at(t2, now) {
            pump.press("B", T_OT_B2)
            pump.swipe(dirSign * Cfg.get(c, "wlen"), T_SWIPE)
        }
    }

    /** Ngắm trước, chỉ bấm sút khi người chơi đã hướng mặt đúng vùng trống (không sút khi đang xoay). */
    private fun tickShot(r: Radar, me: P, now: Long): Boolean {
        val sp = shotPlan ?: return false
        val goal = P(goalX, PW / 2f)
        val axis = if (attackRight) 0f else PIF
        if (abs(wrap(ang(me, goal) - axis)) > RAD75) {
            shotPlan = null
            return false
        }
        val aim = ang(me, aimPoint(me))
        aimNow(aim, 700L)
        val waited = now - sp.t0
        val spd = hypot(cvx, cvy)
        val facing = if (spd > 2.5f) abs(wrap(atan2(cvy, cvx) - aim)) else 0f
        if (waited >= T_AIM_SHOOT && facing < RAD25) {
            pump.press(sp.btn, sp.ms)
            lastShot = now
            busyUntil = now + sp.ms + 220
            shotPlan = null
            return true
        }
        if (waited > 650) {
            shotPlan = null
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
        val pressed = nearest != null && dist(nearest, me) < danger
        val inBox = abs(me.x - goalX) <= 16.5f && abs(me.y - PW / 2f) <= 20.15f

        // 1) Dứt điểm: ngắm vùng trống -> chờ hướng mặt -> bấm. Gần/trung bình & thoáng: sút sệt (đè B).
        if (now - lastShot > 2200 && dGoal <= 28f && !narrow) {
            val ap = aimPoint(me)
            val clear = laneFree(r, me, ap, 2.2f)
            if (clear || (inBox && dGoal <= 16.5f)) {
                val btn = if (dGoal <= 22f && clear) "B" else "A"
                val ms = if (btn == "B") groundShotMs(dGoal) else shotMs(dGoal)
                shotPlan = ShotPlan(btn, ms, now)
                aimNow(ang(me, ap), 700L)
                return
            }
        }
        // 2) Góc quá hẹp: chuyền ngược an toàn cho tuyến hai
        if (narrow && dGoal <= 25f && now - lastPass > 1000) {
            val o = bestPass(r, me, dirSign, -25f, false, 6f, 30f)
            if (o != null) {
                doPass(o, me, now)
                return
            }
        }
        // 3) Tạt cánh
        if (now - lastCross > 3000 && tryCross(r, me, now)) return
        // 4) Ban bật dọc biên (đồng đội phải thoáng, đường chuyền không bị cắt)
        val onWing = me.y < PW * 0.25f || me.y > PW * 0.75f
        if (onWing && !inBox && dGoal > 20f && now - lastOneTwo > 5000) {
            val o = bestPass(r, me, dirSign, -3f, false, 6f, 25f)
            if (o != null) {
                oneTwo(me, o.mate, now)
                return
            }
        }
        // 5) Bị áp sát hoặc bị chắn đường: chỉ chuyền khi có người nhận thuận lợi (sệt, hoặc bổng nếu sệt bị chặn)
        if (now - lastPass > 1200) {
            val goalAng = ang(me, goal)
            val ahead = r.enemies.any { dist(it, me) < 10f && abs(wrap(ang(me, it) - goalAng)) < RAD25 }
            if (pressed || ahead) {
                val o = bestPass(r, me, dirSign, if (pressed) -2f else 3f, true, 8f, 38f)
                if (o != null && o.score > (if (pressed) 0f else 6f)) {
                    doPass(o, me, now)
                    return
                }
            }
        }
        // 6) Rê bóng
        dribble(r, me, goal, now)
    }

    private fun dribble(r: Radar, me: P, goal: P, now: Long) {
        val danger = Cfg.get(c, "danger")
        val goalAng = ang(me, goal)
        if (now < evadeUntil) {
            driveTo(evadeAng, now, true)
            return
        }
        // Chỉ ngoặt né khi có hậu vệ áp sát phía trước trong bán kính nguy hiểm
        var threat: P? = null
        var td = danger
        for (e in r.enemies) {
            val d = dist(e, me)
            if (d < td && abs(wrap(ang(me, e) - heading)) < RAD80) {
                td = d
                threat = e
            }
        }
        val th = threat
        if (th != null) {
            val base = ang(me, th)
            val a1 = wrap(base + PIF / 2f)
            val a2 = wrap(base - PIF / 2f)
            val pick = if (rayScore(r, me, a1, goalAng) >= rayScore(r, me, a2, goalAng)) a1 else a2
            evadeAng = wrap(pick + 0.25f * wrap(goalAng - pick))
            evadeUntil = now + 500L // giữ hướng né, không đổi qua lại
            driveTo(evadeAng, now, true)
            return
        }
        // Không bị áp sát: chạy thẳng vào khoảng trống
        driveTo(chooseRun(r, me, goalAng), now, false)
    }

    // ---------- KHI KHÔNG CÓ BÓNG ----------
    private fun defend(r: Radar, ball: P, enemyBall: P?, now: Long) {
        pump.press("B", 500L) // đè B áp sát, KHÔNG bao giờ bấm A (tránh xoạc, thẻ đỏ)
        val me = r.allies.minByOrNull { dist(it, ball) } ?: return
        val tgt = enemyBall ?: ball
        val ix = tgt.x + bvx * 0.45f
        val iy = tgt.y + bvy * 0.45f
        driveTo(atan2(iy - me.y, ix - me.x), now, false)
        val ownSign = if (attackRight) -1f else 1f
        if ((ball.x - me.x) * ownSign > 8f && now - lastSwitch > 1500) {
            pump.press("C", T_SWITCH_C) // bóng đã vượt qua: đổi sang hậu vệ tuyến dưới
            lastSwitch = now
        }
    }

    private fun chase(r: Radar, ball: P, now: Long) {
        val me = r.allies.minByOrNull { dist(it, ball) } ?: return
        driveTo(ang(me, ball), now, false)
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

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

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
        cv.drawText(
            tac.state + "  ta=" + rd.allies.size + " dich=" + rd.enemies.size +
                " goal=" + (if (tac.goalX > 50f) "PHAI" else "TRAI") + " fps=" + fps,
            6f, 24f, pt
        )
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
        // Luồng "cap": cắt vùng RADAR và phân tích ngay khi có khung hình mới
        cap.roiSpec = floatArrayOf(
            Cfg.get(this, "mmL"), Cfg.get(this, "mmT"), Cfg.get(this, "mmR"), Cfg.get(this, "mmB")
        )
        cap.wantFull = true
        cap.onRoi = { roi -> handleRoi(roi) }
        val p = Pump(this)
        pump = p
        p.start()
        tactics = Tactics(this, p)
        // Luồng "brain": ra quyết định ~60 lần/giây trên khung radar mới nhất
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
            if (menuOnly) {
                p.stopAll()
                return
            }
            tac.update(rd, now)
        } else {
            // vào trận khi radar hợp lệ qua nhiều khung hình liên tiếp
            if (rd != null && rd.ok && radarT != lastSeenT && now - radarT < 500) {
                lastSeenT = radarT
                okStreak++
                if (okStreak >= 10) {
                    matchMode = true
                    cap.wantFull = false // vào trận: không cần khung đầy đủ nữa -> nhẹ hơn
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
