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
    val ok: Boolean get() = allies.size >= 4 && enemies.size >= 4
}

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
            } else if (hueDist(hue, enemyHue) <= enemyTol) {
                cls[i] = 2
            }
        }

        val minA = max(2, (0.006f * w * 0.006f * w).toInt())
        val maxA = max(minA + 1, (0.05f * w * 0.05f * w).toInt())
        val stack = IntArray(n)
        val allies = ArrayList<P>()
        val enemies = ArrayList<P>()
        var ball: P? = null
        var ballScore = 0f
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
            } else if (fill > ballScore) {
                ballScore = fill
                ball = pt
            }
        }
        return Radar(ball, allies, enemies)
    }
}

// ======================= ĐA CHẠM LIÊN TỤC (GesturePump) =======================
/**
 * Mỗi "ngón tay ảo" là một Track. Mọi nét được gửi chung trong từng gesture ngắn (30-120ms)
 * và nối tiếp bằng continueStroke(willContinue = true), nên joystick không bao giờ bị nhấc lên,
 * còn các nút A/B/C và vuốt được thêm vào cùng gesture mà không làm đứt joystick.
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
    private val names = listOf("joy", "A", "B", "C", "swipe")
    private val tr: Map<String, Track> = names.associateWith { Track() }
    private val btnKeys = mapOf("A" to ("ax" to "ay"), "B" to ("bx" to "by"), "C" to ("cx" to "cy"))
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
        val cfgR = Cfg.get(svc, "jr") * sh
        synchronized(lock) {
            t.sx = Cfg.get(svc, "jx") * sw
            t.sy = Cfg.get(svc, "jy") * sh
            t.tx = t.sx + cos(angle) * cfgR
            t.ty = t.sy + sin(angle) * cfgR
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
            var seg = 120L
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
                Thread.sleep(15)
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
    private var svx = 1f
    private var svy = 0f
    private var ballPrev: P? = null
    private var ballPrevT = 0L
    private var bvx = 0f
    private var bvy = 0f

    fun reset() {
        sched.clear()
        busyUntil = 0L
        state = "-"
        carrier = null
    }

    private fun at(delay: Long, now: Long, f: () -> Unit) {
        sched.add(Pair(now + delay, f))
    }

    private fun steer(a: Float) {
        svx = 0.55f * svx + 0.45f * cos(a)
        svy = 0.55f * svy + 0.45f * sin(a)
        pump.joy(atan2(svy, svx))
    }

    private fun aimNow(a: Float, hold: Long = 500L) {
        svx = cos(a)
        svy = sin(a)
        pump.joy(a, hold)
    }

    /** Hai thủ môn nằm ở hai đầu sân: bên nào thủ môn là màu đối thủ thì đó là khung thành cần ghi. */
    private fun detectGoal(r: Radar) {
        val all = ArrayList<Pair<P, Boolean>>()
        for (a in r.allies) all.add(Pair(a, false))
        for (e in r.enemies) all.add(Pair(e, true))
        if (all.size < 6) return
        val left = all.minByOrNull { it.first.x } ?: return
        val right = all.maxByOrNull { it.first.x } ?: return
        if (left.second != right.second) {
            vote += if (right.second) 1 else -1
            vote = vote.coerceIn(-15, 15)
            if (vote >= 8) attackRight = true
            if (vote <= -8) attackRight = false
        }
        goalX = if (attackRight) PL else 0f
        val g = if (attackRight) right else left
        enemyGk = if (g.second) g.first else null
    }

    fun update(r: Radar, now: Long) {
        val due = sched.filter { it.first <= now }
        if (due.isNotEmpty()) {
            sched.removeAll(due.toSet())
            for (d in due) d.second()
        }
        detectGoal(r)
        val ball = r.ball
        if (ball == null) {
            carrier = null
            state = "NO_BALL"
            return
        }
        // vận tốc bóng (làm mượt)
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
        carrier = carr
        val enemyNear = r.enemies.minByOrNull { dist(it, ball) }
        val ownGoalX = if (attackRight) 0f else PL
        val ownSign = if (attackRight) -1f else 1f
        state = when {
            carr != null -> "ATTACK"
            enemyNear != null && dist(enemyNear, ball) < carryM * 1.5f -> "DEFEND"
            abs(ball.x - ownGoalX) < PL / 2f || bvx * ownSign > 2f -> "DEFEND"
            else -> "LOOSE"
        }
        if (now < busyUntil) return
        if (state != "DEFEND") pump.release("B")
        when (state) {
            "ATTACK" -> attack(r, carr!!, now)
            "DEFEND" -> defend(r, ball, enemyNear, now)
            else -> chase(r, ball)
        }
    }

    private fun laneFree(r: Radar, a: P, b: P, rad: Float): Boolean =
        r.enemies.none { it !== enemyGk && segDist(it, a, b) < rad }

    private fun isOpen(r: Radar, p: P, rad: Float): Boolean =
        r.enemies.none { dist(it, p) < rad }

    private fun forwardAlly(r: Radar, me: P, dirSign: Float): P? =
        r.allies.filter {
            it !== me && (it.x - me.x) * dirSign >= 3f && dist(it, me) in 8f..38f &&
                isOpen(r, it, 5f) && laneFree(r, me, it, 2.5f)
        }.maxByOrNull { (it.x - me.x) * dirSign - 0.3f * dist(it, me) }

    private fun supportAlly(r: Radar, me: P): P? =
        r.allies.filter {
            it !== me && dist(it, me) in 6f..30f && isOpen(r, it, 5f) && laneFree(r, me, it, 2.5f)
        }.minByOrNull { dist(it, me) }

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

        // 1) Dứt điểm 3 cấp độ (cấm sút khi góc quá hẹp > 75 độ)
        if (dGoal <= 25f && now - lastShot > 2500) {
            val clear = laneFree(r, me, goal, 2.2f)
            if (!narrow && (inBox || clear)) {
                val gkY = enemyGk?.y ?: (PW / 2f)
                val aimY = if (gkY > PW / 2f) PW / 2f - 3.2f else PW / 2f + 3.2f
                val a = ang(me, P(goalX, aimY))
                val shot: Pair<String, Long>? = when {
                    dGoal <= 14f && clear -> Pair("B", T_SHOOT_GK)
                    dGoal <= 16.5f -> Pair("A", T_SHOOT_BOX)
                    clear -> Pair("A", T_SHOOT_FAR)
                    else -> null
                }
                if (shot != null) {
                    shoot(a, shot.first, shot.second, now)
                    return
                }
            }
        }
        // góc quá hẹp: ưu tiên chuyền ngược cho tuyến hai
        if (narrow && dGoal <= 25f) {
            val sup = supportAlly(r, me)
            if (sup != null) {
                passTo(sup, me, now)
                return
            }
        }
        // 2) Ban bật dọc biên
        val onWing = me.y < PW * 0.25f || me.y > PW * 0.75f
        if (onWing && !inBox && dGoal > 20f && now - lastOneTwo > 5000) {
            val mate = r.allies.filter {
                it !== me && dist(it, me) in 6f..25f && isOpen(r, it, 4f) &&
                    (it.x - me.x) * dirSign >= -3f && laneFree(r, me, it, 2.2f)
            }.minByOrNull { dist(it, me) }
            if (mate != null) {
                oneTwo(me, mate, now)
                return
            }
        }
        // 3) Bị áp sát: chỉ chuyền cho đồng đội phía trên, không bị kèm
        if (pressed) {
            val tgt = forwardAlly(r, me, dirSign)
            if (tgt != null) {
                passTo(tgt, me, now)
                return
            }
        }
        // 4) Rê bóng về phía khung thành, né đối thủ
        var vx = cos(ang(me, goal))
        var vy = sin(ang(me, goal))
        for (e in r.enemies) {
            val d = dist(e, me)
            if (d < 9f && d > 0.1f) {
                val w = (9f - d) / 9f * 1.3f
                vx += (me.x - e.x) / d * w
                vy += (me.y - e.y) / d * w
            }
        }
        steer(atan2(vy, vx))
    }

    private fun passTo(t: P, me: P, now: Long) {
        aimNow(ang(me, t), 600L)
        busyUntil = now + T_AIM_PASS + T_PASS_B + 120
        at(T_AIM_PASS, now) { pump.press("B", T_PASS_B) }
    }

    private fun shoot(a: Float, btn: String, ms: Long, now: Long) {
        aimNow(a, 700L)
        lastShot = now
        busyUntil = now + T_AIM_SHOOT + ms + 200
        at(T_AIM_SHOOT, now) { pump.press(btn, ms) }
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

    // ---------- KHI KHÔNG CÓ BÓNG ----------
    private fun defend(r: Radar, ball: P, enemyBall: P?, now: Long) {
        pump.press("B", 500L) // đè B áp sát, KHÔNG bao giờ bấm A (tránh xoạc, thẻ đỏ)
        val me = r.allies.minByOrNull { dist(it, ball) } ?: return
        val tgt = enemyBall ?: ball
        val lead = 0.45f
        val ix = tgt.x + bvx * lead
        val iy = tgt.y + bvy * lead
        steer(atan2(iy - me.y, ix - me.x))
        val ownSign = if (attackRight) -1f else 1f
        if ((ball.x - me.x) * ownSign > 8f && now - lastSwitch > 1500) {
            pump.press("C", T_SWITCH_C) // bóng đã vượt qua: đổi sang hậu vệ tuyến dưới
            lastSwitch = now
        }
    }

    private fun chase(r: Radar, ball: P) {
        val me = r.allies.minByOrNull { dist(it, ball) } ?: return
        steer(ang(me, ball))
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
    private var lastRadar: Radar? = null
    private var lastRadarOk = 0L
    private var lastMenuCheck = 0L
    private var lastDbg = 0L

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
                " goal=" + (if (tac.goalX > 50f) "PHAI" else "TRAI"),
            6f, 24f, pt
        )
        ui.post { dbgView?.setImageBitmap(out) }
    }

    // ---------- Chụp màn hình để dạy bot ----------
    private fun openEditor() {
        val cap = CaptureService.instance
        if (cap == null) {
            toast("Chưa bật quét màn hình. Mở app DLS Bot và bấm 'Bật quét màn hình'")
            return
        }
        if (editor != null) return
        if (running) stopBot()
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
        if (CaptureService.instance == null) {
            toast("Chưa bật quét màn hình. Mở app DLS Bot và bấm 'Bật quét màn hình'")
            return
        }
        running = true
        runBtn?.text = "■"
        templates = Store.load(this)
        val p = Pump(this)
        pump = p
        p.start()
        tactics = Tactics(this, p)
        matchMode = false
        lastRadar = null
        val t = HandlerThread("radar")
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
        handler?.removeCallbacksAndMessages(null)
        ht?.quitSafely()
        ht = null
        handler = null
        pump?.stopAndRelease()
        pump = null
        tactics = null
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
            handler?.postDelayed(this, max(5L, 55L - dt)) // ~15-18 FPS
        }
    }

    private fun roiOf(bmp: Bitmap): Roi {
        val l = Cfg.get(this, "mmL")
        val t = Cfg.get(this, "mmT")
        val r = Cfg.get(this, "mmR")
        val b = Cfg.get(this, "mmB")
        val x0 = (min(l, r) * bmp.width).toInt().coerceIn(0, bmp.width - 2)
        val x1 = (max(l, r) * bmp.width).toInt().coerceIn(x0 + 1, bmp.width)
        val y0 = (min(t, b) * bmp.height).toInt().coerceIn(0, bmp.height - 2)
        val y1 = (max(t, b) * bmp.height).toInt().coerceIn(y0 + 1, bmp.height)
        val rw = x1 - x0
        val rh = y1 - y0
        val px = IntArray(rw * rh)
        bmp.getPixels(px, 0, rw, x0, y0, rw, rh)
        return Roi(px, rw, rh, true)
    }

    private fun step() {
        val cap = CaptureService.instance ?: return
        val p = pump ?: return
        val tac = tactics ?: return
        val now = System.currentTimeMillis()
        p.setScreen(cap.w, cap.h)
        if (matchMode) {
            val roi = cap.grabRoi(
                Cfg.get(this, "mmL"), Cfg.get(this, "mmT"), Cfg.get(this, "mmR"), Cfg.get(this, "mmB")
            ) ?: return
            if (roi.fresh || lastRadar == null) {
                val rd0 = RadarAnalyzer.analyze(this, roi.px, roi.w, roi.h)
                lastRadar = rd0
                if (dbgOn && now - lastDbg > 200) {
                    lastDbg = now
                    drawDebug(roi, rd0, tac)
                }
            }
            val rd = lastRadar ?: return
            if (rd.ok) lastRadarOk = now
            if (now - lastRadarOk > 2500) {
                matchMode = false
                tac.reset()
                p.stopAll()
                lastRadar = null
                toast("Ra khỏi trận, quay lại chế độ menu")
                return
            }
            if (menuOnly) {
                p.stopAll()
                return
            }
            tac.update(rd, now)
        } else {
            if (now - lastMenuCheck < 700) return
            lastMenuCheck = now
            val bmp = cap.grab() ?: return
            val roi = roiOf(bmp)
            val rd = RadarAnalyzer.analyze(this, roi.px, roi.w, roi.h)
            if (dbgOn) drawDebug(roi, rd, tac)
            if (rd.ok) {
                matchMode = true
                lastRadar = rd
                lastRadarOk = now
                toast("Đã vào trận - bot điều khiển")
                return
            }
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
