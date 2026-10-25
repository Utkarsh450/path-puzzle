package com.arrowpuzzle.game

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a board in cell units: after [draw] sets up the canvas, cell (x, y) is centred on
 * (x, y). Arrow proportions match the real game as installed on the test phone (24.75 px
 * cells): line 4 px (0.16), square bends, round tail caps, a head 14 px wide (0.28 each
 * side) and 13 px long whose tip sits 5 px (0.22) past the cell centre.
 */
class BoardRenderer {
    companion object {
        const val LW = .16f
        const val HEAD_HW = .28f
        const val HEAD_TIP = .22f
        const val HEAD_BACK = .29f
        /** Rounding of the head's corners. */
        const val HEAD_ROUND = .03f
        const val CORNER = 0f
        /** The real game draws a flat blue arrow with no trail or glow while it flies out. */
        const val TRAIL = false

        fun lerpColor(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            fun ch(s: Int) = (((a shr s) and 255) + ((((b shr s) and 255) - ((a shr s) and 255)) * k)).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** Polyline with rounded corners; brk[i] starts a new sub-path (portal jump). */
        fun polyline(p: Path, xs: FloatArray, ys: FloatArray, brk: BooleanArray?, n: Int, r: Float) {
            var i = 0
            while (i < n) {
                var j = i + 1
                while (j < n && (brk == null || !brk[j])) j++
                if (j - i >= 2) {
                    p.moveTo(xs[i], ys[i])
                    for (q in i + 1 until j - 1) {
                        val ax = xs[q - 1]; val ay = ys[q - 1]; val bx = xs[q]; val by = ys[q]; val cx = xs[q + 1]; val cy = ys[q + 1]
                        val d1 = hypot(ax - bx, ay - by); val d2 = hypot(cx - bx, cy - by)
                        if (d1 < 1e-4f || d2 < 1e-4f) { p.lineTo(bx, by); continue }
                        val rr = min(r, min(d1, d2) / 2)
                        if (rr <= 0f) { p.lineTo(bx, by); continue }
                        p.lineTo(bx + (ax - bx) / d1 * rr, by + (ay - by) / d1 * rr)
                        p.quadTo(bx, by, bx + (cx - bx) / d2 * rr, by + (cy - by) / d2 * rr)
                    }
                    p.lineTo(xs[j - 1], ys[j - 1])
                }
                i = j
            }
        }

        fun headTriangle(p: Path, x: Float, y: Float, dx: Float, dy: Float) {
            p.moveTo(x + dx * HEAD_TIP, y + dy * HEAD_TIP)
            p.lineTo(x - dx * HEAD_BACK - dy * HEAD_HW, y - dy * HEAD_BACK + dx * HEAD_HW)
            p.lineTo(x - dx * HEAD_BACK + dy * HEAD_HW, y - dy * HEAD_BACK - dx * HEAD_HW)
            p.close()
        }
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val round = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Arrow heads: filled, with the outline stroke rounding their corners a little. */
    private val headFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE; strokeJoin = Paint.Join.ROUND; strokeWidth = HEAD_ROUND
    }
    private fun headPaint(color: Int, alpha: Int = 255): Paint { headFill.color = color; headFill.alpha = alpha; return headFill }
    private val path = Path()
    private val wx = FloatArray(1024)
    private val wy = FloatArray(1024)
    private val wb = BooleanArray(1024)
    private val rect = RectF()
    private var occupied = BooleanArray(0)
    private var dotAlpha = FloatArray(0)
    private var dotsFor: Board? = null

    fun draw(c: Canvas, g: GameState, ui: Ui, k: Float, ox: Float, oy: Float, dt: Float, grid: Boolean) {
        val b = g.board ?: return
        c.save()
        c.translate(ox, oy); c.scale(k, k); c.translate(.5f, .5f)
        if (grid) drawGrid(c, b)
        drawDots(c, g, b, dt)
        drawFeatures(c, g)
        drawLinks(c, g)
        for (s in g.sprites) if (s.state == SpriteState.IDLE || s.state == SpriteState.ERASING) drawIdle(c, g, s)
        for (s in g.sprites) if (s.state == SpriteState.BUMP) drawMoving(c, s, s.bumpOffset(), false, true)
        for (s in g.sprites) if (s.state == SpriteState.MOVING) drawMoving(c, s, s.s, s.exiting, false)
        drawKeys(c, g, ui)
        drawZones(c, g, ui)
        drawStopBreaks(c, g)
        g.particles.draw(c)
        c.restore()
    }

    // ---- background layers ----------------------------------------------------------

    private fun drawGrid(c: Canvas, b: Board) {
        round.shader = null; round.color = C.GRID; round.strokeWidth = .05f
        for (y in 0 until b.rows) {
            var x0 = -1; var x1 = -1
            for (x in 0 until b.cols) if (b.inMask(x, y)) { if (x0 < 0) x0 = x; x1 = x }
            if (x0 >= 0 && x1 > x0) c.drawLine(x0.toFloat(), y.toFloat(), x1.toFloat(), y.toFloat(), round)
        }
        for (x in 0 until b.cols) {
            var y0 = -1; var y1 = -1
            for (y in 0 until b.rows) if (b.inMask(x, y)) { if (y0 < 0) y0 = y; y1 = y }
            if (y0 >= 0 && y1 > y0) c.drawLine(x.toFloat(), y0.toFloat(), x.toFloat(), y1.toFloat(), round)
        }
    }

    private fun drawDots(c: Canvas, g: GameState, b: Board, dt: Float) {
        val n = b.cols * b.rows
        val fresh = dotsFor !== b || occupied.size != n
        if (fresh) { occupied = BooleanArray(n); dotAlpha = FloatArray(n); dotsFor = b }
        java.util.Arrays.fill(occupied, false)
        for (s in g.sprites) {
            val solid = s.state == SpriteState.IDLE || s.state == SpriteState.BUMP ||
                (s.state == SpriteState.ERASING && s.alpha > .4f) || (s.state == SpriteState.MOVING && !s.exiting)
            if (!solid) continue
            for (p in (s.pendingInfo ?: s.info).pts) occupied[p.y * b.cols + p.x] = true
            if (s.state == SpriteState.MOVING) for (p in s.info.pts) occupied[p.y * b.cols + p.x] = true
        }
        for (p in g.features.portals) { occupied[p.ay * b.cols + p.ax] = true; occupied[p.by * b.cols + p.bx] = true }
        for (s in g.features.stops) if (s.alive) occupied[s.y * b.cols + s.x] = true
        for (r in g.features.redirects) occupied[r.y * b.cols + r.x] = true
        val k = min(1f, dt * 9f)
        fill.shader = null; fill.style = Paint.Style.FILL
        for (y in 0 until b.rows) for (x in 0 until b.cols) {
            if (!b.inMask(x, y)) continue
            val i = y * b.cols + x
            val target = if (occupied[i]) 0f else 1f
            dotAlpha[i] = if (fresh) target else dotAlpha[i] + (target - dotAlpha[i]) * k
            if (dotAlpha[i] < .02f) continue
            fill.color = C.DOT; fill.alpha = (255 * dotAlpha[i]).toInt()
            c.drawCircle(x.toFloat(), y.toFloat(), .075f, fill)
        }
    }

    private fun drawFeatures(c: Canvas, g: GameState) {
        val t = g.time
        for (p in g.features.portals) {
            portal(c, p.ax.toFloat(), p.ay.toFloat(), p.color, t)
            portal(c, p.bx.toFloat(), p.by.toFloat(), p.color, t + .5f)
        }
        for (s in g.features.stops) if (s.alive) stopPoint(c, s.x.toFloat(), s.y.toFloat(), t)
        for (r in g.features.redirects) redirect(c, r.x.toFloat(), r.y.toFloat(), r.dx, r.dy, t)
    }

    private fun portal(c: Canvas, x: Float, y: Float, color: Int, t: Float) {
        val rim = C.PORTAL_RIM[color % 3]; val glow = C.PORTAL_GLOW[color % 3]
        fill.style = Paint.Style.FILL; fill.alpha = 255
        fill.shader = RadialGradient(x, y, .62f, intArrayOf((glow and 0xFFFFFF) or 0x88000000.toInt(), glow and 0xFFFFFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, .62f, fill)
        rect.set(x - .24f, y - .38f, x + .24f, y + .38f)
        fill.shader = RadialGradient(x, y, .42f, intArrayOf(0xFF0A1433.toInt(), 0xFF0A1433.toInt(), rim), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(rect, fill)
        fill.shader = null
        round.shader = null; round.color = rim; round.strokeWidth = .1f
        c.drawOval(rect, round)
        rect.inset(.08f, .12f)
        round.color = 0xFFFFFFFF.toInt(); round.alpha = 140; round.strokeWidth = .045f
        c.drawArc(rect, (t * 260f) % 360f, 120f, false, round)
        round.alpha = 255
    }

    private fun stopPoint(c: Canvas, x: Float, y: Float, t: Float) {
        val pulse = 1f + .04f * sin(t * 4f)
        round.shader = null; round.color = 0xFFE7B8F8.toInt(); round.strokeWidth = .085f
        c.drawCircle(x, y, .3f * pulse, round)
        fill.shader = null; fill.style = Paint.Style.FILL; fill.color = 0xFFB43FEE.toInt()
        rect.set(x - .15f, y - .15f, x + .15f, y + .15f)
        c.drawRoundRect(rect, .06f, .06f, fill)
    }

    private fun drawStopBreaks(c: Canvas, g: GameState) {
        fill.shader = null; fill.style = Paint.Style.FILL
        for (sb in g.stopBreaks) {
            val age = sb[2]; val k = age / .7f
            for (i in 0 until 6) {
                val a = i * PI.toFloat() / 3 + .4f
                val d = Ease.outCubic(k) * .7f
                fill.color = if (i % 2 == 0) 0xFFB43FEE.toInt() else 0xFFE7B8F8.toInt()
                fill.alpha = (255 * (1 - k)).toInt().coerceIn(0, 255)
                c.save(); c.translate(sb[0] + cos(a) * d, sb[1] + sin(a) * d); c.rotate(age * 400f + i * 60)
                c.drawRect(-.06f, -.06f, .06f, .06f, fill); c.restore()
            }
        }
    }

    private fun redirect(c: Canvas, x: Float, y: Float, dx: Int, dy: Int, t: Float) {
        fill.shader = null; fill.style = Paint.Style.FILL; fill.color = C.ORANGE; fill.alpha = 36
        c.drawCircle(x, y, .38f, fill)
        c.save(); c.translate(x, y); c.rotate((atan2(dy.toFloat(), dx.toFloat()) * 180 / PI).toFloat())
        round.shader = null; round.color = C.ORANGE; round.strokeWidth = .085f
        val shift = (t * 1.2f % 1f) * .06f
        for (i in 0..1) {
            val o = (i - .5f) * .22f + shift
            path.reset(); path.moveTo(o - .09f, -.16f); path.lineTo(o + .07f, 0f); path.lineTo(o - .09f, .16f)
            c.drawPath(path, round)
        }
        c.restore()
    }

    private fun drawLinks(c: Canvas, g: GameState) {
        fill.shader = null; fill.style = Paint.Style.FILL
        for (s in g.sprites) {
            val l = s.info.link
            if (l <= s.id || l >= g.sprites.size) continue
            val o = g.sprites[l]
            if (s.state != SpriteState.IDLE && s.state != SpriteState.BUMP) continue
            if (o.state != SpriteState.IDLE && o.state != SpriteState.BUMP) continue
            val a = s.info.pts[s.info.pts.size / 2]; val b = o.info.pts[o.info.pts.size / 2]
            val ax = a.x.toFloat(); val ay = a.y.toFloat(); val bx = b.x.toFloat(); val by = b.y.toFloat()
            val d = hypot(bx - ax, by - ay)
            val steps = max(2, (d / .24f).toInt())
            fill.color = C.ACCENT; fill.alpha = 150
            val phase = (g.time * 2f) % 1f
            for (i in 0..steps) {
                val u = ((i + phase) / steps).coerceIn(0f, 1f)
                c.drawCircle(ax + (bx - ax) * u, ay + (by - ay) * u, .04f, fill)
            }
            fill.alpha = 255
            c.drawCircle(ax, ay, .11f, fill); c.drawCircle(bx, by, .11f, fill)
        }
    }

    private fun drawKeys(c: Canvas, g: GameState, ui: Ui) {
        for (s in g.sprites) {
            if (s.info.keyZone < 0) continue
            if (s.state != SpriteState.IDLE && s.state != SpriteState.BUMP) continue
            val p = s.info.pts[s.info.pts.size / 2]
            fill.shader = null; fill.style = Paint.Style.FILL; fill.color = 0xFFFFFFFF.toInt()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), .27f, fill)
            round.shader = null; round.color = C.GOLD; round.strokeWidth = .04f
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), .27f, round)
            ui.key(c, p.x.toFloat(), p.y.toFloat(), .42f, 0xFFE29A0C.toInt())
        }
    }

    private fun drawZones(c: Canvas, g: GameState, ui: Ui) {
        for ((i, z) in g.features.zones.withIndex()) {
            val age = g.zoneUnlock[i]
            if (!z.locked && age == null) continue
            val k = if (age == null) 0f else Ease.clamp01(age / .7f)
            val a = 1f - Ease.outQuad(k)
            val grow = 1f + .1f * k
            val cx = (z.x0 + z.x1) / 2f; val cy = (z.y0 + z.y1) / 2f
            val hw = (z.x1 - z.x0) / 2f + .46f; val hh = (z.y1 - z.y0) / 2f + .46f
            rect.set(cx - hw * grow, cy - hh * grow, cx + hw * grow, cy + hh * grow)
            fill.style = Paint.Style.FILL
            fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0xFFE6EDF8.toInt(), 0xFFF3F6FB.toInt(), Shader.TileMode.CLAMP)
            fill.alpha = (242 * a).toInt()
            c.drawRoundRect(rect, .3f, .3f, fill)
            fill.shader = null
            round.shader = null; round.color = 0xFFCAD6EA.toInt(); round.alpha = (255 * a).toInt(); round.strokeWidth = .05f
            c.drawRoundRect(rect, .3f, .3f, round)
            round.alpha = 255
            val size = min(hw, hh) * .9f
            ui.lock(c, cx, cy, size, Ease.clamp01((age ?: 0f) * 3f), (255 * a).toInt())
        }
    }

    // ---- arrows ---------------------------------------------------------------------

    private fun buildPaths(s: ArrowSprite) {
        val pts = s.info.pts
        val n = pts.size
        for (i in 0 until n) { wx[i] = pts[i].x.toFloat(); wy[i] = pts[i].y.toFloat(); wb[i] = i == 0 || pts[i].jump }
        val body = Path()
        polyline(body, wx, wy, wb, n, CORNER)
        val head = Path()
        headTriangle(head, wx[n - 1], wy[n - 1], s.info.dx.toFloat(), s.info.dy.toFloat())
        if (s.info.twoWay) headTriangle(head, wx[0], wy[0], s.info.tdx.toFloat(), s.info.tdy.toFloat())
        s.bodyPath = body; s.headPath = head
    }

    /** Draw-in during the level intro: the body grows from the tail, the head pops on at the end. */
    private fun drawGrowing(c: Canvas, s: ArrowSprite) {
        val k = Ease.outCubic(s.appear)
        if (k <= 0f) return
        val pts = s.info.pts
        var total = 0f
        for (i in 1 until pts.size) if (!pts[i].jump) total += 1f
        val target = total * k
        var n = 0
        var acc = 0f
        var hx = 0f; var hy = 0f
        wx[0] = pts[0].x.toFloat(); wy[0] = pts[0].y.toFloat(); wb[0] = true; n = 1
        for (i in 1 until pts.size) {
            if (pts[i].jump) { wx[n] = pts[i].x.toFloat(); wy[n] = pts[i].y.toFloat(); wb[n] = true; n++; continue }
            val ax = pts[i - 1].x.toFloat(); val ay = pts[i - 1].y.toFloat()
            val bx = pts[i].x.toFloat(); val by = pts[i].y.toFloat()
            if (acc + 1f <= target) { wx[n] = bx; wy[n] = by; wb[n] = false; n++; acc += 1f; hx = bx - ax; hy = by - ay }
            else {
                val u = target - acc
                wx[n] = ax + (bx - ax) * u; wy[n] = ay + (by - ay) * u; wb[n] = false; n++
                hx = bx - ax; hy = by - ay
                break
            }
        }
        if (n < 2) return
        path.reset(); polyline(path, wx, wy, wb, n, CORNER)
        stroke.shader = null; stroke.color = C.ARROW; stroke.alpha = 255; stroke.strokeWidth = LW
        c.drawPath(path, stroke)
        val headA = Ease.clamp01((s.appear - .8f) / .2f)
        if (headA > 0f) {
            path.reset(); headTriangle(path, wx[n - 1], wy[n - 1], hx, hy)
            val hp = headPaint(C.ARROW, (255 * headA).toInt())
            c.drawPath(path, hp)
        }
    }

    private fun drawIdle(c: Canvas, g: GameState, s: ArrowSprite) {
        if (s.appear < 1f) { drawGrowing(c, s); return }
        val appear = 1f
        val alpha = s.alpha * appear
        if (alpha <= .01f) return
        if (s.bodyPath == null) buildPaths(s)
        val body = s.bodyPath!!; val head = s.headPath!!
        val hinted = g.hintId == s.id
        if (hinted) {
            val pulse = .75f + .25f * sin(g.time * 5f)
            round.shader = null; round.color = C.HINT_GLOW; round.alpha = (android.graphics.Color.alpha(C.HINT_GLOW) * pulse).toInt(); round.strokeWidth = .42f
            c.drawPath(body, round); c.drawPath(head, round)
        }
        val base = if (s.red) C.MISTAKE else if (hinted) C.ACCENT else C.ARROW
        val color = when {
            s.blockT >= 0f -> lerpColor(base, C.MISTAKE, blockEnvelope(s.blockT))
            s.flash > 0f -> lerpColor(base, C.MISTAKE, s.flash)
            else -> base
        }
        if (appear < 1f) {
            val h = s.info.pts.last()
            val sc = .85f + .15f * appear
            c.save(); c.scale(sc, sc, h.x.toFloat(), h.y.toFloat())
        }
        stroke.shader = null; stroke.color = color; stroke.alpha = (255 * alpha).toInt(); stroke.strokeWidth = LW
        c.drawPath(body, stroke)
        val hp = headPaint(color, (255 * alpha).toInt())
        c.drawPath(head, hp)
        if (appear < 1f) c.restore()
    }

    /** Blocker flash measured on the real game: up to red by ~100 ms, back to navy by ~330 ms. */
    private fun blockEnvelope(t: Float): Float = when {
        t < .1f -> t / .1f
        t < .2f -> 1f
        t < .33f -> 1f - (t - .2f) / .13f
        else -> 0f
    }

    private fun drawMoving(c: Canvas, s: ArrowSprite, off: Float, exiting: Boolean, bump: Boolean) {
        if (TRAIL && exiting && off > 0f) {
            val trailLen = min(off, s.bodyArc * .9f + 1.6f)
            val slices = 6
            for (i in 0 until slices) {
                val a0 = off - trailLen + trailLen * i / slices
                val a1 = a0 + trailLen / slices + .04f
                val n = s.window(a0, a1, wx, wy, wb)
                if (n < 2) continue
                path.reset(); polyline(path, wx, wy, wb, n, CORNER)
                val u = (i + 1f) / slices
                round.shader = null; round.color = lerpColor(C.TRAIL_B, C.TRAIL_A, u)
                round.alpha = (30 + 150 * u * u).toInt(); round.strokeWidth = LW * (.55f + .45f * u)
                c.drawPath(path, round)
            }
        }
        val n = s.window(off, off + s.bodyArc, wx, wy, wb)
        if (n < 2) return
        path.reset(); polyline(path, wx, wy, wb, n, CORNER)
        val hx = wx[n - 1]; val hy = wy[n - 1]
        val color = if (bump) lerpColor(C.ARROW, C.MISTAKE, s.flash) else C.ACCENT
        if (bump && s.flash > 0f) {
            round.shader = null; round.color = C.MISTAKE_GLOW; round.alpha = (android.graphics.Color.alpha(C.MISTAKE_GLOW) * s.flash).toInt(); round.strokeWidth = .42f
            c.drawPath(path, round)
        }
        if (TRAIL && exiting) {
            fill.style = Paint.Style.FILL
            fill.shader = RadialGradient(hx, hy, .7f, intArrayOf((C.TRAIL_B and 0xFFFFFF) or 0x99000000.toInt(), C.TRAIL_B and 0xFFFFFF), null, Shader.TileMode.CLAMP)
            fill.alpha = 255
            c.drawCircle(hx, hy, .7f, fill)
            fill.shader = null
        }
        stroke.shader = null; stroke.color = color; stroke.alpha = (255 * s.alpha).toInt(); stroke.strokeWidth = LW
        c.drawPath(path, stroke)
        path.reset(); headTriangle(path, hx, hy, s.headDx, s.headDy)
        val hp = headPaint(color, (255 * s.alpha).toInt())
        c.drawPath(path, hp)
    }

    // ---- previews (win screen / menu card) --------------------------------------------

    /** Records the level's starting layout once; draw it scaled into any rect. */
    fun recordPreview(infos: List<ArrowInfo>, cols: Int, rows: Int, color: Int = C.ARROW): Picture {
        val pic = Picture()
        val c = pic.beginRecording(cols * 100, rows * 100)
        c.scale(100f, 100f); c.translate(.5f, .5f)
        val p = Path()
        stroke.shader = null; stroke.color = color; stroke.strokeWidth = LW
        val hp = headPaint(color)
        for (a in infos) {
            if (!a.alive) continue
            val n = a.pts.size
            for (i in 0 until n) { wx[i] = a.pts[i].x.toFloat(); wy[i] = a.pts[i].y.toFloat(); wb[i] = i == 0 || a.pts[i].jump }
            p.reset(); polyline(p, wx, wy, wb, n, CORNER)
            c.drawPath(p, stroke)
            p.reset(); headTriangle(p, wx[n - 1], wy[n - 1], a.dx.toFloat(), a.dy.toFloat())
            if (a.twoWay) headTriangle(p, wx[0], wy[0], a.tdx.toFloat(), a.tdy.toFloat())
            c.drawPath(p, hp)
        }
        pic.endRecording()
        return pic
    }
}

/** Draws a recorded preview centred and fitted inside [r]. */
fun Canvas.drawPreview(pic: Picture, cols: Int, rows: Int, r: RectF, pad: Float) {
    val k = min((r.width() - 2 * pad) / (cols * 100f), (r.height() - 2 * pad) / (rows * 100f))
    save()
    translate(r.centerX() - cols * 50f * k, r.centerY() - rows * 50f * k)
    scale(k, k)
    drawPicture(pic)
    restore()
}
