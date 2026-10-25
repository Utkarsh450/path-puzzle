package com.arrowpuzzle.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min
import kotlin.random.Random

abstract class Overlay(protected val v: GameView) {
    private val openedAt = System.nanoTime()
    private var closeStart = 0L
    var closing = false
        private set
    var onClosed: (() -> Unit)? = null
    open val dismissable = true

    fun age() = (System.nanoTime() - openedAt) / 1e9f
    fun closeAge() = if (closing) (System.nanoTime() - closeStart) / 1e9f else 0f
    val finished get() = closing && closeAge() > .18f

    fun close(then: (() -> Unit)? = null) {
        if (closing) return
        closing = true; closeStart = System.nanoTime(); onClosed = then
        v.invalidate()
    }

    /** 0..1 visibility used for fades. */
    protected fun visibility(): Float = if (closing) 1f - Ease.clamp01(closeAge() / .18f) else Ease.clamp01(age() / .2f)

    open val needsFrame get() = age() < .8f || closing
    abstract fun draw(c: Canvas, w: Float, h: Float, dt: Float)
    open fun onBack() { if (dismissable) close() }
}

sealed class PItem {
    class Primary(val label: String, val sub: String? = null, val enabled: Boolean = true, val action: () -> Unit) : PItem()
    class Secondary(val label: String, val action: () -> Unit) : PItem()
    class TextBtn(val label: String, val action: () -> Unit) : PItem()
    class Toggle(val label: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : PItem()
    class Progress(val value: Float, val label: String) : PItem()
}

/**
 * ChaiUI dialog over a black/55 backdrop: opaque card with the warm edge, a title, a lead
 * with one highlight phrase ([keyword]), the solid main action and outline secondary ones.
 * [sheet] makes it a bottom drawer.
 */
class Popup(
    v: GameView,
    private val title: String,
    private val body: String? = null,
    private val keyword: String? = null,
    private val artHeight: Float = 0f,
    private val art: ((Canvas, RectF, Float) -> Unit)? = null,
    private val items: List<PItem>,
    override val dismissable: Boolean = true,
    private val showClose: Boolean = false,
    private val sheet: Boolean = false
) : Overlay(v) {
    override val needsFrame get() = super.needsFrame || art != null

    override fun draw(c: Canvas, w: Float, h: Float, dt: Float) {
        val ui = v.ui
        val vis = visibility()
        c.drawRect(0f, 0f, w, h, ui.fillPaint(C.BLACKOUT, (255 * vis).toInt()))

        val cardW = if (sheet) w else min(ui.dp(360f), w - ui.dp(32f))
        val pad = ui.dp(24f)
        val inner = cardW - pad * 2
        val bodySize = ui.dp(15.5f)
        val bodyH = if (body != null) ui.paragraph(null, body, keyword, 0f, 0f, inner, bodySize, C.MUTED) else 0f
        var total = pad
        if (art != null) total += artHeight + ui.dp(18f)
        total += ui.dp(30f)
        total += if (body != null) ui.dp(8f) + bodyH + ui.dp(22f) else ui.dp(16f)
        for (it in items) total += itemHeight(it)
        total += ui.dp(8f)

        val open = Ease.clamp01(age() / .32f)
        val calm = Motion.reduced
        val sc = if (sheet || calm) 1f else if (closing) 1f - .04f * Ease.clamp01(closeAge() / .18f) else .94f + .06f * Ease.outCubic(open)
        val left = (w - cardW) / 2
        if (sheet) total += v.insetBottom + ui.dp(8f)
        val slide = if (!sheet || calm) 0f else if (closing) total * Ease.clamp01(closeAge() / .18f) else total * (1f - Ease.outCubic(open))
        val top = if (sheet) h - total + slide else (h - total) / 2
        val card = RectF(left, top, left + cardW, if (sheet) h + ui.dp(30f) else top + total)
        c.save()
        c.scale(sc, sc, w / 2, h / 2)
        c.saveLayerAlpha(0f, 0f, w, h, (255 * vis).toInt())
        ui.dialogCard(c, card, sheet)
        if (sheet) {
            val hw = ui.dp(18f)
            c.drawRoundRect(w / 2 - hw, top + ui.dp(8f), w / 2 + hw, top + ui.dp(12f), ui.dp(2f), ui.dp(2f), ui.fillPaint(C.FAINT))
        }
        var y = top + pad
        if (art != null) {
            art.invoke(c, RectF(left + pad, y, left + cardW - pad, y + artHeight), age())
            y += artHeight + ui.dp(18f)
        }
        ui.text(c, title, w / 2, y + ui.dp(22f), ui.dp(22f), C.FG)
        y += ui.dp(30f)
        if (body != null) {
            y += ui.dp(8f)
            ui.paragraph(c, body, keyword, left + pad, y, inner, bodySize, C.MUTED)
            y += bodyH + ui.dp(22f)
        } else {
            y += ui.dp(16f)
        }
        val bl = left + pad; val br = left + cardW - pad
        for ((i, it) in items.withIndex()) {
            val ih = itemHeight(it)
            when (it) {
                is PItem.Primary -> ui.primaryButton(c, "p$i", RectF(bl, y, br, y + ui.dp(52f)), it.label, it.sub, it.enabled) { close(it.action) }
                is PItem.Secondary -> ui.secondaryButton(c, "p$i", RectF(bl, y, br, y + ui.dp(48f)), it.label) { close(it.action) }
                is PItem.TextBtn -> ui.textButton(c, "p$i", w / 2, y + ui.dp(20f), it.label, C.MUTED, ui.dp(16f)) { close(it.action) }
                is PItem.Toggle -> {
                    val row = RectF(bl, y, br, y + ui.dp(48f))
                    ui.text(c, it.label, bl, row.centerY() + ui.dp(5.5f), ui.dp(16f), C.FG, Paint.Align.LEFT, ui.medium)
                    ui.toggle(c, br - ui.dp(22f), row.centerY(), it.get())
                    c.drawRect(bl, row.bottom, br, row.bottom + ui.dp(1f), ui.fillPaint(C.BORDER))
                    ui.hit("p$i", row) { it.set(!it.get()); v.sfx.play(Sound.TAP); v.invalidate() }
                }
                is PItem.Progress -> {
                    val bar = RectF(bl, y + ui.dp(4f), br, y + ui.dp(10f))
                    c.drawRoundRect(bar, ui.dp(3f), ui.dp(3f), ui.fillPaint(C.SECONDARY))
                    val f = RectF(bar.left, bar.top, bar.left + bar.width() * it.value.coerceIn(0f, 1f), bar.bottom)
                    if (f.width() > 0f) c.drawRoundRect(f, ui.dp(3f), ui.dp(3f), ui.fillPaint(C.FG))
                    ui.text(c, it.label, bl, y + ui.dp(30f), ui.dp(14f), C.MUTED, Paint.Align.LEFT, ui.medium)
                }
            }
            y += ih
        }
        if (showClose) {
            ui.iconClose(c, card.right - ui.dp(26f), card.top + ui.dp(26f), ui.dp(18f), C.MUTED)
            ui.hit("p_close", RectF(card.right - ui.dp(52f), card.top, card.right, card.top + ui.dp(52f))) { close() }
        }
        c.restore(); c.restore()
    }

    private fun itemHeight(it: PItem) = when (it) {
        is PItem.Primary -> v.ui.dp(64f)
        is PItem.Secondary -> v.ui.dp(58f)
        is PItem.TextBtn -> v.ui.dp(44f)
        is PItem.Toggle -> v.ui.dp(52f)
        is PItem.Progress -> v.ui.dp(46f)
    }
}

/** Level complete: the dark backdrop, a hairline card with the board, quiet specks. */
class WinOverlay(v: GameView, private val info: WinInfo) : Overlay(v) {
    override val dismissable = false
    override val needsFrame get() = true
    private val specks = Particles()
    private var spawned = 0f

    override fun draw(c: Canvas, w: Float, h: Float, dt: Float) {
        val ui = v.ui
        val t = age()
        val vis = visibility()
        val calm = Motion.reduced
        c.saveLayerAlpha(0f, 0f, w, h, (255 * vis).toInt())
        c.drawColor(C.BG)
        ui.backdrop(c, w.toInt(), h.toInt())

        val cardSize = min(w - ui.dp(96f), min(ui.dp(300f), h * .36f))
        val cardCy = h * .45f

        // a few slow white and cream specks drifting down
        if (!calm && spawned < 1.6f) {
            val n = if (spawned == 0f) 36 else if (Random.nextFloat() < dt * 14) 1 else 0
            repeat(n) {
                specks.add(Particle(Random.nextFloat() * w, -ui.dp(10f) - Random.nextFloat() * h * .4f,
                    (Random.nextFloat() - .5f) * ui.dp(30f), ui.dp(50f) + Random.nextFloat() * ui.dp(70f),
                    4.5f, 4.5f, ui.dp(3f) + Random.nextFloat() * ui.dp(3f), C.CONFETTI[Random.nextInt(C.CONFETTI.size)], PKind.DOT,
                    gravity = ui.dp(10f), drag = .2f))
            }
            spawned += dt
        }
        specks.update(dt)
        specks.draw(c)

        val ta = if (calm) 1f else Ease.clamp01(t / .3f)
        val title = when {
            info.title != null -> info.title
            info.salt != 0 -> "Challenge completed"
            else -> "Level completed"
        }
        val titleY = h * .17f
        ui.text(c, title, w / 2, titleY - ui.dp(10f) * (1 - Ease.outCubic(ta)), ui.dp(30f), C.FG, alpha = (255 * ta).toInt())
        val lead = when {
            info.lead != null -> info.lead
            info.perfect -> "Flawless, not a single mistake on this board."
            else -> "Board cleared. Every arrow found its way out."
        }
        val keyword = if (info.lead != null) info.keyword else if (info.perfect) "Flawless" else "every arrow"
        val leadW = min(w - ui.dp(64f), ui.dp(340f))
        c.saveLayerAlpha(0f, 0f, w, h, (255 * ta).toInt())
        ui.paragraph(c, lead, keyword, w / 2 - leadW / 2, titleY + ui.dp(14f), leadW, ui.dp(15.5f), C.MUTED)
        c.restore()

        // preview card
        val ck = if (calm) 1f else Ease.clamp01((t - .08f) / .35f)
        val sc = .96f + .04f * Ease.outCubic(ck)
        val card = RectF(w / 2 - cardSize / 2, cardCy - cardSize / 2, w / 2 + cardSize / 2, cardCy + cardSize / 2)
        c.save(); c.scale(sc, sc, w / 2, cardCy)
        c.saveLayerAlpha(0f, 0f, w, h, (255 * ck).toInt())
        ui.card(c, card, rad = ui.dp(18f))
        c.drawPreview(info.preview, info.cols, info.rows, card, ui.dp(22f))
        c.restore(); c.restore()

        // the mode's score line, then the reward
        val ra = if (calm) 1f else Ease.clamp01((t - .35f) / .3f)
        var y = card.bottom + ui.dp(42f)
        c.saveLayerAlpha(0f, 0f, w, h, (255 * ra).toInt())
        if (info.detail != null) {
            ui.text(c, info.detail, w / 2, y, ui.dp(20f), C.FG, face = ui.display)
            y += ui.dp(30f)
        }
        if (info.reward > 0) {
            val label = "+${info.reward} coins"
            val lw = ui.textW(label, ui.dp(20f), ui.display)
            ui.coin(c, w / 2 - lw / 2 - ui.dp(6f), y - ui.dp(7f), ui.dp(10f))
            ui.text(c, label, w / 2 + ui.dp(10f), y, ui.dp(20f), C.FG, face = ui.display)
        }
        c.restore()
        if (info.salt == 0 && Mechanic.ENABLED) {
            val next = Mechanic.unlockLevels.indexOfFirst { it > info.level }
            if (next >= 0) {
                val prev = if (next == 0) 1 else Mechanic.unlockLevels[next - 1]
                val target = Mechanic.unlockLevels[next]
                val p = (info.level + 1 - prev).toFloat() / (target - prev)
                val by = y + ui.dp(28f)
                val bw = min(w - ui.dp(120f), ui.dp(240f))
                val bar = RectF(w / 2 - bw / 2, by, w / 2 + bw / 2, by + ui.dp(6f))
                c.drawRoundRect(bar, ui.dp(3f), ui.dp(3f), ui.fillPaint(C.SECONDARY))
                val f = RectF(bar.left, bar.top, bar.left + bar.width() * p.coerceIn(0f, 1f), bar.bottom)
                c.drawRoundRect(f, ui.dp(3f), ui.dp(3f), ui.fillPaint(C.FG))
                val left = target - info.level - 1
                val msg = if (left <= 0) "New mechanic next level: ${Mechanic.values()[next].title}" else "New mechanic in $left levels"
                ui.text(c, msg, w / 2, by + ui.dp(26f), ui.dp(13f), C.MUTED, face = ui.medium)
            }
        }

        // buttons: the solid main action and the outline one beneath it
        val ba = if (calm) 1f else Ease.clamp01((t - .5f) / .3f)
        if (ba > 0f) {
            val bw = min(w - ui.dp(64f), ui.dp(320f))
            val by = h - v.insetBottom - ui.dp(150f)
            val primary = when {
                info.mode != GameMode.CLASSIC -> "Play again"
                info.salt != 0 -> "Continue"
                else -> "Next level"
            }
            val sub = if (info.salt != 0) null else "Level ${info.level + 1}"
            c.saveLayerAlpha(0f, 0f, w, h, (255 * ba).toInt())
            ui.primaryButton(c, "w_next", RectF(w / 2 - bw / 2, by, w / 2 + bw / 2, by + ui.dp(58f)), primary, sub) {
                close { v.afterWin(info, false) }
            }
            ui.secondaryButton(c, "w_main", RectF(w / 2 - bw / 2, by + ui.dp(72f), w / 2 + bw / 2, by + ui.dp(120f)), "Main menu") {
                close { v.afterWin(info, true) }
            }
            c.restore()
        }
        c.restore()
    }

    override fun onBack() { close { v.afterWin(info, true) } }
}

/** Little illustrations for mechanic tutorial popups, drawn in a 5x3 cell grid. */
object MechanicArt {
    private val path = Path()
    private val xs = FloatArray(16)
    private val ys = FloatArray(16)

    fun draw(c: Canvas, r: RectF, t: Float, m: Mechanic, ui: Ui) {
        val cs = min(r.width() / 5f, r.height() / 3f)
        c.save()
        c.translate(r.centerX() - cs * 2f, r.centerY() - cs)
        c.scale(cs, cs)
        ui.fillPaint(C.DOT)
        for (y in 0..2) for (x in 0..4) c.drawCircle(x.toFloat(), y.toFloat(), .07f, ui.fill)
        val phase = (t % 2.4f) / 2.4f
        when (m) {
            Mechanic.PORTAL -> {
                portal(c, 2f, 0f, 0, ui); portal(c, 2f, 2f, 0, ui)
                arrow(c, ui, floatArrayOf(0f, 1f), floatArrayOf(0f, 0f), 1f, 0f, C.ARROW)
                val k = Ease.inOutCubic(Ease.clamp01(phase * 1.6f))
                if (k > .55f) arrow(c, ui, floatArrayOf(2f + (k - .55f) * 4f, 3f + (k - .55f) * 4f), floatArrayOf(2f, 2f), 1f, 0f, C.MOVING)
            }
            Mechanic.STOP -> {
                stop(c, 4f, 1f, ui)
                val k = Ease.outCubic(Ease.clamp01(phase * 1.5f))
                arrow(c, ui, floatArrayOf(0f + k * 2f, 1f + k * 2f), floatArrayOf(1f, 1f), 1f, 0f, if (k > 0f && k < 1f) C.MOVING else C.ARROW)
            }
            Mechanic.REDIRECT -> {
                ui.strokePaint(C.ORANGE, .085f)
                for (i in 0..1) {
                    val o = (i - .5f) * .22f
                    path.reset(); path.moveTo(3f - .16f, o - .09f + 1f - 1f); path.lineTo(3f, o + .07f); path.lineTo(3f + .16f, o - .09f)
                    c.save(); c.translate(0f, 1f); c.drawPath(path, ui.stroke); c.restore()
                }
                arrow(c, ui, floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), 1f, 0f, C.ARROW)
                ui.strokePaint(C.MOVING, .06f, 120)
                c.drawLine(1.4f, 1f, 3f, 1f, ui.stroke); c.drawLine(3f, 1f, 3f, 2.6f, ui.stroke)
            }
            Mechanic.ZONE -> {
                val rr = RectF(2.55f, -.45f, 4.45f, 2.45f)
                c.drawRoundRect(rr, .3f, .3f, ui.fillPaint(0xFFE6EDF8.toInt()))
                c.drawRoundRect(rr, .3f, .3f, ui.strokePaint(0xFFCAD6EA.toInt(), .05f))
                ui.lock(c, 3.5f, 1f, .9f, Ease.clamp01((phase - .5f) * 4f))
                arrow(c, ui, floatArrayOf(0f, 0f, 1f), floatArrayOf(2f, 1f, 1f), 0f, -1f, C.ARROW)
                c.drawCircle(0f, 1.5f, .27f, ui.fillPaint(Color.WHITE))
                ui.key(c, 0f, 1.5f, .42f, 0xFFE29A0C.toInt())
            }
            Mechanic.TWO_WAY -> {
                arrow(c, ui, floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 1f, 1f), 1f, 0f, C.ARROW)
                path.reset(); BoardRenderer.headTriangle(path, 1f, 1f, -1f, 0f)
                c.drawPath(path, ui.fillPaint(C.ARROW))
            }
            Mechanic.LINK -> {
                arrow(c, ui, floatArrayOf(0f, 1f), floatArrayOf(0f, 0f), -1f, 0f, C.ARROW)
                arrow(c, ui, floatArrayOf(3f, 4f), floatArrayOf(2f, 2f), 1f, 0f, C.ARROW)
                ui.fillPaint(C.ACCENT, 160)
                for (i in 0..10) { val u = i / 10f; c.drawCircle(.5f + 3f * u, 0f + 2f * u, .04f, ui.fill) }
            }
        }
        c.restore()
    }

    private fun arrow(c: Canvas, ui: Ui, px: FloatArray, py: FloatArray, dx: Float, dy: Float, color: Int) {
        for (i in px.indices) { xs[i] = px[i]; ys[i] = py[i] }
        path.reset(); BoardRenderer.polyline(path, xs, ys, null, px.size, BoardRenderer.CORNER)
        val s = ui.strokePaint(color, BoardRenderer.LW)
        c.drawPath(path, s)
        path.reset(); BoardRenderer.headTriangle(path, px.last(), py.last(), dx, dy)
        c.drawPath(path, ui.fillPaint(color))
    }

    private fun portal(c: Canvas, x: Float, y: Float, color: Int, ui: Ui) {
        val rr = RectF(x - .24f, y - .38f, x + .24f, y + .38f)
        c.drawOval(rr, ui.fillPaint(0xFF0A1433.toInt()))
        c.drawOval(rr, ui.strokePaint(C.PORTAL_RIM[color], .1f))
    }

    private fun stop(c: Canvas, x: Float, y: Float, ui: Ui) {
        c.drawCircle(x, y, .3f, ui.strokePaint(0xFFE7B8F8.toInt(), .085f))
        c.drawRoundRect(RectF(x - .15f, y - .15f, x + .15f, y + .15f), .06f, .06f, ui.fillPaint(0xFFB43FEE.toInt()))
    }
}
