package com.arrowpuzzle.game

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Hit(val id: String, val r: RectF, val action: () -> Unit)

/** Switches a span of text to another typeface (TypefaceSpan(Typeface) needs API 28). */
private class FontSpan(private val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(p: TextPaint) { p.typeface = face; p.letterSpacing = -.025f }
    override fun updateMeasureState(p: TextPaint) { p.typeface = face; p.letterSpacing = -.025f }
}

/**
 * Shared drawing toolkit in the ChaiUI style (ui.chaicode.com): Manrope type, the one solid
 * button with asymmetric corners, warm hairline cards without shadows, the cream highlight
 * phrase, the hexagon glow backdrop and flat line icons.
 */
class Ui(private val res: Resources) {
    val dens = res.displayMetrics.density
    fun dp(v: Float) = v * dens

    val hits = ArrayList<Hit>()
    var pressedId: String? = null

    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun font(file: String, weight: Int, fallback: Typeface): Typeface = try {
        Typeface.Builder(res.assets, "fonts/$file").setFontVariationSettings("'wght' $weight").build() ?: fallback
    } catch (_: Exception) {
        fallback
    }

    /** Manrope: everything. */
    val bold: Typeface = font("Manrope.ttf", 600, Typeface.create("sans-serif", Typeface.BOLD))
    val medium: Typeface = font("Manrope.ttf", 500, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    val regular: Typeface = font("Manrope.ttf", 400, Typeface.create("sans-serif", Typeface.NORMAL))
    /** Montserrat: card titles, buttons on cards, the highlight phrase. */
    val display: Typeface = font("Montserrat.ttf", 500, medium)
    val displayBold: Typeface = font("Montserrat.ttf", 600, bold)
    /** Onest: the wordmark. */
    val brand: Typeface = font("Onest.ttf", 500, medium)

    private val path = Path()
    private val tmp = RectF()

    fun fillPaint(color: Int, alpha: Int = 255): Paint {
        fill.shader = null; fill.style = Paint.Style.FILL; fill.color = color; fill.xfermode = null
        if (alpha != 255) fill.alpha = alpha * Color.alpha(color) / 255
        return fill
    }

    fun strokePaint(color: Int, width: Float, alpha: Int = 255): Paint {
        stroke.shader = null; stroke.color = color; stroke.strokeWidth = width
        if (alpha != 255) stroke.alpha = alpha * Color.alpha(color) / 255
        return stroke
    }

    fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
             align: Paint.Align = Paint.Align.CENTER, face: Typeface = bold, alpha: Int = 255) {
        tp.typeface = face; tp.textSize = size; tp.color = color; tp.alpha = alpha * Color.alpha(color) / 255
        tp.textAlign = align; tp.style = Paint.Style.FILL; tp.shader = null; tp.letterSpacing = 0f
        c.drawText(s, x, y, tp)
    }

    fun textW(s: String, size: Float, face: Typeface = bold): Float {
        tp.typeface = face; tp.textSize = size; tp.letterSpacing = 0f
        return tp.measureText(s)
    }

    fun hit(id: String, r: RectF, action: () -> Unit) = hits.add(Hit(id, RectF(r), action))
    fun pressed(id: String) = pressedId == id

    // ---- text with the highlight phrase ------------------------------------------------

    private val layouts = HashMap<String, StaticLayout>()

    /** "the one clear path" -> "The One Clear Path" (the CSS capitalize of the highlight). */
    fun capitalize(s: String) = s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

    /**
     * A wrapped paragraph in [color] with at most one highlight phrase (cream Montserrat),
     * drawn from [top]; returns its height. Layouts are cached by content.
     */
    fun paragraph(c: Canvas?, body: String, highlight: String?, x: Float, top: Float, width: Float, size: Float,
                  color: Int, center: Boolean = true, face: Typeface = regular): Float {
        val key = "$body|$highlight|${width.toInt()}|$size|$color|${C.HIGHLIGHT}|$center|${face.hashCode()}"
        val layout = layouts.getOrPut(key) {
            if (layouts.size > 48) layouts.clear()
            var text = body
            var start = -1
            if (highlight != null) {
                start = body.indexOf(highlight, ignoreCase = true)
                if (start >= 0) text = body.substring(0, start) + capitalize(body.substring(start, start + highlight.length)) +
                    body.substring(start + highlight.length)
            }
            val sp = SpannableString(text)
            if (start >= 0) {
                val end = start + highlight!!.length
                sp.setSpan(ForegroundColorSpan(C.HIGHLIGHT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(FontSpan(display), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // each layout keeps its own paint: StaticLayout reads it again at every draw
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; this.color = color }
            if (face == bold) paint.letterSpacing = -.025f
            StaticLayout.Builder.obtain(sp, 0, sp.length, paint, width.toInt().coerceAtLeast(1))
                .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.3f).build()
        }
        if (c != null) { c.save(); c.translate(x, top); layout.draw(c); c.restore() }
        return layout.height.toFloat()
    }

    // ---- surfaces ------------------------------------------------------------------------

    private val radius get() = dp(10f)

    /** Rounded rect with independent corners: tl, tr, br, bl. */
    private fun corners(r: RectF, tl: Float, tr: Float, br: Float, bl: Float): Path {
        path.reset()
        path.addRoundRect(r, floatArrayOf(tl, tl, tr, tr, br, br, bl, bl), Path.Direction.CW)
        return path
    }

    /** The warm hairline card: faint fill, 1 dp edge, no shadow. */
    fun card(c: Canvas, r: RectF, pressed: Boolean = false, rad: Float = dp(14f)) {
        c.drawRoundRect(r, rad, rad, fillPaint(C.CARD_FILL))
        c.drawRoundRect(r, rad, rad, strokePaint(if (pressed) C.CARD_EDGE_HOVER else C.CARD_EDGE, dp(1f)))
    }

    /** Dialog surface: opaque card colour, the stronger warm edge, rounded-2xl. */
    fun dialogCard(c: Canvas, r: RectF, sheet: Boolean = false) {
        val rad = dp(16f)
        val p = if (sheet) corners(r, rad, rad, 0f, 0f) else corners(r, rad, rad, rad, rad)
        c.drawPath(p, fillPaint(C.CARD))
        c.drawPath(p, strokePaint(C.CARD_EDGE_HOVER, dp(1f)))
    }

    /** Small outlined label (Chip / Level): text and a thin edge in [color], never a fill. */
    fun chip(c: Canvas, cx: Float, cy: Float, label: String, color: Int = C.FG, size: Float = dp(13f),
             edge: Int = C.BORDER, padX: Float = dp(10f)): RectF {
        val w = textW(label, size, medium) + padX * 2
        val h = size + dp(12f)
        tmp.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawRoundRect(tmp, dp(6f), dp(6f), fillPaint(C.BG, 200))
        c.drawRoundRect(tmp, dp(6f), dp(6f), strokePaint(edge, dp(1f)))
        text(c, label, cx, cy + size * .36f, size, color, face = medium)
        return RectF(tmp)
    }

    // ---- buttons -------------------------------------------------------------------------

    /**
     * The solid button, one per screen: white on dark (near-black on light), top-right and
     * bottom-left corners rounded, ArrowUpRight icon. [sub] is a caption under the label.
     */
    fun primaryButton(c: Canvas, id: String, r: RectF, label: String, sub: String? = null,
                      enabled: Boolean = true, action: () -> Unit) {
        val k = if (enabled && pressed(id)) .97f else 1f
        val rr = scaled(r, k)
        val bg = if (enabled) (if (pressed(id)) C.PRIMARY else C.SOLID) else C.SECONDARY
        val fg = if (enabled) C.SOLID_FG else C.SUBTLE
        c.drawPath(corners(rr, 0f, radius, 0f, radius), fillPaint(bg))
        val size = dp(17f)
        val labelY = if (sub == null) rr.centerY() + size * .36f else rr.centerY() + dp(1f)
        val iconS = dp(16f)
        val lw = textW(label, size, bold)
        val total = lw + if (enabled) dp(6f) + iconS else 0f
        val x0 = rr.centerX() - total / 2
        text(c, label, x0, labelY, size, fg, Paint.Align.LEFT)
        if (enabled) iconArrowUpRight(c, x0 + lw + dp(6f) + iconS / 2, labelY - size * .36f, iconS, fg)
        if (sub != null) text(c, sub, rr.centerX(), rr.centerY() + dp(19f), dp(12.5f), fg, face = medium, alpha = 150)
        if (enabled) hit(id, r, action)
    }

    /** Same solid button; kept for the win screen's main action. */
    fun whiteButton(c: Canvas, id: String, r: RectF, label: String, textColor: Int = C.SOLID_FG,
                    sub: String? = null, action: () -> Unit) = primaryButton(c, id, r, label, sub, true, action)

    /** Outline button beside the solid one: mirrored corners, 1 dp border, transparent. */
    fun secondaryButton(c: Canvas, id: String, r: RectF, label: String, action: () -> Unit) {
        val rr = scaled(r, if (pressed(id)) .97f else 1f)
        val p = corners(rr, radius, 0f, radius, 0f)
        if (pressed(id)) c.drawPath(p, fillPaint(C.SECONDARY))
        c.drawPath(p, strokePaint(C.INPUT, dp(1f)))
        text(c, label, rr.centerX(), rr.centerY() + dp(6f), dp(16f), C.FG, face = medium)
        hit(id, r, action)
    }

    /** Ghost text button. */
    fun textButton(c: Canvas, id: String, cx: Float, cy: Float, label: String, color: Int, size: Float, action: () -> Unit) {
        val w = textW(label, size, medium) + dp(32f)
        val r = RectF(cx - w / 2, cy - dp(22f), cx + w / 2, cy + dp(22f))
        text(c, label, cx, cy + size * .36f, size, if (pressed(id)) C.FG else color, face = medium)
        hit(id, r, action)
    }

    /** Soft button on cards (shadcn default: light grey on dark), Montserrat semibold. */
    fun softButton(c: Canvas, r: RectF, label: String, pressed: Boolean = false) {
        c.drawRoundRect(r, dp(6f), dp(6f), fillPaint(C.PRIMARY, if (pressed) 220 else 255))
        text(c, label, r.centerX(), r.centerY() + dp(5f), dp(14f), C.PRIMARY_FG, face = displayBold)
    }

    /** Boxed icon button (boosters): hairline square, the cream edge when [ring] is on. */
    fun circleButton(c: Canvas, id: String, cx: Float, cy: Float, r: Float, ring: Boolean = false, action: () -> Unit) {
        val k = if (pressed(id)) .94f else 1f
        val rr = r * k
        tmp.set(cx - rr, cy - rr, cx + rr, cy + rr)
        c.drawRoundRect(tmp, dp(14f), dp(14f), fillPaint(if (pressed(id)) C.SECONDARY else C.CARD))
        c.drawRoundRect(tmp, dp(14f), dp(14f), strokePaint(if (ring) C.HIGHLIGHT else C.CARD_EDGE, dp(if (ring) 1.5f else 1f)))
        hit(id, RectF(cx - r, cy - r, cx + r, cy + r), action)
    }

    /** Count badge on a booster: a small outlined chip. */
    fun badge(c: Canvas, cx: Float, cy: Float, label: String, color: Int = C.FG) {
        val h = dp(20f)
        val w = maxOf(h, textW(label, dp(12f), bold) + dp(10f))
        tmp.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawRoundRect(tmp, h / 2, h / 2, fillPaint(C.BG))
        c.drawRoundRect(tmp, h / 2, h / 2, strokePaint(C.CARD_EDGE_HOVER, dp(1f)))
        text(c, label, cx, cy + dp(4.2f), dp(12f), color)
    }

    /** HUD pill: a hairline card. */
    fun pill(c: Canvas, r: RectF, color: Int = C.CARD_FILL) {
        c.drawRoundRect(r, dp(10f), dp(10f), fillPaint(color))
        c.drawRoundRect(r, dp(10f), dp(10f), strokePaint(C.CARD_EDGE, dp(1f)))
    }

    /** shadcn switch, restyled: primary track when on, input track when off. */
    fun toggle(c: Canvas, cx: Float, cy: Float, on: Boolean) {
        val w = dp(44f); val h = dp(24f)
        tmp.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawRoundRect(tmp, h / 2, h / 2, fillPaint(if (on) C.PRIMARY else C.INPUT))
        val kx = if (on) cx + w / 2 - h / 2 else cx - w / 2 + h / 2
        val thumb = if (C.dark) (if (on) C.PRIMARY_FG else C.FG) else C.BG
        c.drawCircle(kx, cy, h / 2 - dp(2.5f), fillPaint(thumb))
    }

    private fun scaled(r: RectF, k: Float): RectF {
        val w = r.width() * k; val h = r.height() * k
        return RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2)
    }

    // ---- the backdrop ------------------------------------------------------------------------

    private var backdrop: Bitmap? = null
    private var backdropKey = ""

    /**
     * The chaicode page background, drawn in code: a warm glow at the top centre with faint
     * hexagons in it, fading out towards the bottom. Light mode keeps it warm (the effect of
     * invert + hue-rotate on the original). Cached per size and theme.
     */
    fun backdrop(c: Canvas, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val bh = min(h, dp(560f).toInt())
        val key = "$w:$bh:${C.dark}"
        if (backdrop == null || key != backdropKey) {
            backdrop?.recycle()
            backdrop = renderBackdrop(w, bh)
            backdropKey = key
        }
        c.drawBitmap(backdrop!!, 0f, 0f, null)
    }

    private fun renderBackdrop(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = w / 2f
        val glow = if (C.dark) 0x1FFFEDD5 else 0x1492400E
        p.shader = RadialGradient(cx, -h * .12f, maxOf(w.toFloat(), h * 1.1f) * .85f, intArrayOf(glow, glow and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        // pointy-top hexagons on a staggered grid; a scattered subset is lit
        val r = dp(19f)
        val hw = sqrt(3f) * r
        val step = 1.5f * r
        val ink = if (C.dark) 0xFFFFF7ED.toInt() else 0xFF78350F.toInt()
        var row = 0
        var y = r * .4f
        while (y < h + r) {
            var x = if (row % 2 == 0) cx % hw - hw else cx % hw - hw / 2
            var col = 0
            while (x < w + hw) {
                val hash = ((row * 73856093) xor (col * 19349663)) and 0x7fffffff
                if (hash % 5 < 2) {
                    val d = hypot(x - cx, y) / (maxOf(w, h) * .75f)
                    val a = ((1f - d).coerceIn(0f, 1f) * (if (C.dark) 16 else 14)).toInt()
                    if (a > 0) {
                        p.color = ink; p.alpha = a
                        hexagon(c, x, y, r * .93f, p)
                    }
                }
                x += hw; col++
            }
            y += step; row++
        }
        // fade the bottom out so the image has no edge
        p.color = Color.BLACK
        p.shader = LinearGradient(0f, h * .55f, 0f, h.toFloat(), Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return bmp
    }

    private fun hexagon(c: Canvas, x: Float, y: Float, r: Float, p: Paint) {
        path.reset()
        for (i in 0 until 6) {
            val a = (PI / 3 * i - PI / 2).toFloat()
            val px = x + cos(a) * r; val py = y + sin(a) * r
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, p)
    }

    // ---- line icons (s = icon size in px), lucide-like strokes ------------------------------

    private fun line(color: Int, s: Float) = strokePaint(color, s * .085f)

    fun iconArrowUpRight(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s * 1.3f)
        c.drawLine(cx - s * .28f, cy + s * .28f, cx + s * .28f, cy - s * .28f, stroke)
        path.reset(); path.moveTo(cx - s * .2f, cy - s * .28f); path.lineTo(cx + s * .28f, cy - s * .28f); path.lineTo(cx + s * .28f, cy + s * .2f)
        c.drawPath(path, stroke)
    }

    /** chaicode's theme switcher: a circle with one half shaded. */
    fun iconTheme(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val r = s * .4f
        c.drawCircle(cx, cy, r, line(color, s))
        tmp.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(tmp, 90f, 180f, true, fillPaint(color))
    }

    fun iconBack(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s * 1.2f)
        path.reset(); path.moveTo(cx + s * .14f, cy - s * .3f); path.lineTo(cx - s * .16f, cy); path.lineTo(cx + s * .14f, cy + s * .3f)
        c.drawPath(path, stroke)
    }

    fun iconGear(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        path.reset()
        val teeth = 8
        val ro = s * .44f; val ri = s * .35f
        for (i in 0 until teeth * 4) {
            val a = (i / (teeth * 4f)) * 2 * PI - PI / 2
            val rr = if ((i / 2) % 2 == 0) ro else ri
            val x = cx + cos(a).toFloat() * rr; val y = cy + sin(a).toFloat() * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, stroke)
        c.drawCircle(cx, cy, s * .14f, stroke)
    }

    fun iconBulb(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        path.reset()
        tmp.set(cx - s * .28f, cy - s * .46f, cx + s * .28f, cy + s * .1f)
        path.arcTo(tmp, 140f, 260f)
        path.lineTo(cx + s * .13f, cy + s * .2f)
        path.lineTo(cx - s * .13f, cy + s * .2f)
        path.close()
        c.drawPath(path, stroke)
        c.drawLine(cx - s * .12f, cy + s * .33f, cx + s * .12f, cy + s * .33f, stroke)
        c.drawLine(cx - s * .06f, cy + s * .44f, cx + s * .06f, cy + s * .44f, stroke)
    }

    fun iconEraser(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        c.save(); c.rotate(-40f, cx, cy)
        tmp.set(cx - s * .4f, cy - s * .18f, cx + s * .4f, cy + s * .18f)
        c.drawRoundRect(tmp, s * .07f, s * .07f, stroke)
        c.drawLine(cx - s * .04f, cy - s * .18f, cx - s * .04f, cy + s * .18f, stroke)
        c.restore()
        c.drawLine(cx - s * .08f, cy + s * .4f, cx + s * .4f, cy + s * .4f, stroke)
    }

    fun iconWand(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        c.drawLine(cx - s * .36f, cy + s * .36f, cx + s * .1f, cy - s * .1f, stroke)
        sparkle(c, cx + s * .26f, cy - s * .28f, s * .17f, color)
        sparkle(c, cx - s * .18f, cy - s * .3f, s * .1f, color)
        sparkle(c, cx + s * .32f, cy + s * .14f, s * .09f, color)
    }

    private fun sparkle(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        c.save(); c.translate(cx, cy); Particles.star4(path, r); c.drawPath(path, fillPaint(color)); c.restore()
    }

    fun iconGrid(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        tmp.set(cx - s * .36f, cy - s * .36f, cx + s * .36f, cy + s * .36f)
        c.drawRoundRect(tmp, s * .08f, s * .08f, stroke)
        c.drawLine(cx - s * .12f, cy - s * .36f, cx - s * .12f, cy + s * .36f, stroke)
        c.drawLine(cx + s * .12f, cy - s * .36f, cx + s * .12f, cy + s * .36f, stroke)
        c.drawLine(cx - s * .36f, cy - s * .12f, cx + s * .36f, cy - s * .12f, stroke)
        c.drawLine(cx - s * .36f, cy + s * .12f, cx + s * .36f, cy + s * .12f, stroke)
    }

    fun iconArrowCount(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s * 1.3f)
        c.drawLine(cx - s * .32f, cy + s * .32f, cx + s * .12f, cy - s * .12f, stroke)
        path.reset()
        path.moveTo(cx + s * .38f, cy - s * .38f); path.lineTo(cx - s * .08f, cy - s * .3f); path.lineTo(cx + s * .3f, cy + s * .08f); path.close()
        c.drawPath(path, fillPaint(color))
    }

    private fun heartPath(cx: Float, cy: Float, size: Float): Path {
        val s = size / 2f
        path.reset()
        path.moveTo(cx, cy + .86f * s)
        path.cubicTo(cx - 1.2f * s, cy + .02f * s, cx - 1.02f * s, cy - .95f * s, cx - .5f * s, cy - .9f * s)
        path.cubicTo(cx - .2f * s, cy - .88f * s, cx, cy - .7f * s, cx, cy - .48f * s)
        path.cubicTo(cx, cy - .7f * s, cx + .2f * s, cy - .88f * s, cx + .5f * s, cy - .9f * s)
        path.cubicTo(cx + 1.02f * s, cy - .95f * s, cx + 1.2f * s, cy + .02f * s, cx, cy + .86f * s)
        path.close()
        return path
    }

    /** Heart outline; [highlight] fills it. */
    fun heart(c: Canvas, cx: Float, cy: Float, size: Float, color: Int, highlight: Boolean = true) {
        val p = heartPath(cx, cy, size)
        if (highlight) c.drawPath(p, fillPaint(color)) else c.drawPath(p, strokePaint(color, size * .08f))
    }

    /** Coin: a cream ring with a small inner ring. */
    fun coin(c: Canvas, cx: Float, cy: Float, r: Float) {
        c.drawCircle(cx, cy, r * .9f, strokePaint(C.HIGHLIGHT, r * .2f))
        c.drawCircle(cx, cy, r * .35f, fillPaint(C.HIGHLIGHT))
    }

    fun star5(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        path.reset()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * .45f
            val a = -PI / 2 + i * PI / 5
            val x = cx + cos(a).toFloat() * rad; val y = cy + sin(a).toFloat() * rad
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, fillPaint(color))
    }

    /** Padlock outline; [open] 0..1 lifts and swings the shackle. */
    fun lock(c: Canvas, cx: Float, cy: Float, s: Float, open: Float = 0f, alpha: Int = 255) {
        val lift = s * .16f * open
        strokePaint(C.FG, s * .09f, alpha)
        tmp.set(cx - s * .2f, cy - s * .44f - lift, cx + s * .2f, cy - s * .02f - lift)
        c.save(); c.rotate(-25f * open, cx + s * .2f, cy - s * .1f)
        c.drawArc(tmp, 180f, 180f, false, stroke)
        c.drawLine(cx - s * .2f, cy - s * .24f - lift, cx - s * .2f, cy - s * .06f - lift, stroke)
        c.drawLine(cx + s * .2f, cy - s * .24f - lift, cx + s * .2f, cy - s * .02f, stroke)
        c.restore()
        tmp.set(cx - s * .32f, cy - s * .1f, cx + s * .32f, cy + s * .4f)
        c.drawRoundRect(tmp, s * .1f, s * .1f, fillPaint(C.CARD, alpha))
        c.drawRoundRect(tmp, s * .1f, s * .1f, strokePaint(C.FG, s * .09f, alpha))
        c.drawCircle(cx, cy + s * .14f, s * .05f, fillPaint(C.FG, alpha))
    }

    fun key(c: Canvas, cx: Float, cy: Float, s: Float, color: Int = C.HIGHLIGHT) {
        strokePaint(color, s * .12f)
        c.drawCircle(cx - s * .22f, cy, s * .16f, stroke)
        c.drawLine(cx - s * .06f, cy, cx + s * .4f, cy, stroke)
        c.drawLine(cx + s * .28f, cy, cx + s * .28f, cy + s * .14f, stroke)
        c.drawLine(cx + s * .4f, cy, cx + s * .4f, cy + s * .16f, stroke)
    }

    /** Trophy outline (Awards, monthly trophy). */
    fun cup(c: Canvas, cx: Float, cy: Float, s: Float, color: Int = C.FG, alpha: Int = 255) {
        strokePaint(color, s * .07f, alpha)
        path.reset()
        path.moveTo(cx - s * .28f, cy - s * .4f); path.lineTo(cx + s * .28f, cy - s * .4f)
        path.lineTo(cx + s * .26f, cy - s * .12f)
        path.quadTo(cx + s * .22f, cy + s * .1f, cx, cy + s * .12f)
        path.quadTo(cx - s * .22f, cy + s * .1f, cx - s * .26f, cy - s * .12f); path.close()
        c.drawPath(path, stroke)
        tmp.set(cx - s * .46f, cy - s * .36f, cx - s * .2f, cy - s * .08f); c.drawArc(tmp, 90f, 180f, false, stroke)
        tmp.set(cx + s * .2f, cy - s * .36f, cx + s * .46f, cy - s * .08f); c.drawArc(tmp, 270f, 180f, false, stroke)
        c.drawLine(cx, cy + s * .12f, cx, cy + s * .3f, stroke)
        c.drawLine(cx - s * .2f, cy + s * .36f, cx + s * .2f, cy + s * .36f, stroke)
    }

    fun iconHome(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        path.reset()
        path.moveTo(cx - s * .36f, cy - s * .04f); path.lineTo(cx, cy - s * .38f); path.lineTo(cx + s * .36f, cy - s * .04f)
        path.lineTo(cx + s * .36f, cy + s * .36f); path.lineTo(cx - s * .36f, cy + s * .36f); path.close()
        c.drawPath(path, stroke)
        c.drawLine(cx - s * .1f, cy + s * .36f, cx - s * .1f, cy + s * .1f, stroke)
        c.drawLine(cx - s * .1f, cy + s * .1f, cx + s * .1f, cy + s * .1f, stroke)
        c.drawLine(cx + s * .1f, cy + s * .1f, cx + s * .1f, cy + s * .36f, stroke)
    }

    fun iconCalendar(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        tmp.set(cx - s * .36f, cy - s * .3f, cx + s * .36f, cy + s * .36f)
        c.drawRoundRect(tmp, s * .08f, s * .08f, stroke)
        c.drawLine(cx - s * .36f, cy - s * .08f, cx + s * .36f, cy - s * .08f, stroke)
        c.drawLine(cx - s * .16f, cy - s * .42f, cx - s * .16f, cy - s * .2f, stroke)
        c.drawLine(cx + s * .16f, cy - s * .42f, cx + s * .16f, cy - s * .2f, stroke)
    }

    fun iconUser(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        c.drawCircle(cx, cy - s * .14f, s * .17f, stroke)
        tmp.set(cx - s * .34f, cy + s * .1f, cx + s * .34f, cy + s * .7f)
        c.drawArc(tmp, 200f, 140f, false, stroke)
    }

    fun iconClose(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s * 1.1f)
        c.drawLine(cx - s * .28f, cy - s * .28f, cx + s * .28f, cy + s * .28f, stroke)
        c.drawLine(cx + s * .28f, cy - s * .28f, cx - s * .28f, cy + s * .28f, stroke)
    }

    fun iconChevron(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, left: Boolean) {
        line(color, s * 1.2f)
        val d = if (left) 1f else -1f
        path.reset(); path.moveTo(cx + d * s * .14f, cy - s * .3f); path.lineTo(cx - d * s * .14f, cy); path.lineTo(cx + d * s * .14f, cy + s * .3f)
        c.drawPath(path, stroke)
    }

    fun iconCheck(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s * 1.4f)
        path.reset(); path.moveTo(cx - s * .3f, cy); path.lineTo(cx - s * .08f, cy + s * .22f); path.lineTo(cx + s * .32f, cy - s * .22f)
        c.drawPath(path, stroke)
    }

    fun iconPodium(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        path.reset()
        path.moveTo(cx - s * .42f, cy + s * .38f); path.lineTo(cx - s * .42f, cy + s * .04f); path.lineTo(cx - s * .14f, cy + s * .04f)
        path.lineTo(cx - s * .14f, cy - s * .2f); path.lineTo(cx + s * .14f, cy - s * .2f); path.lineTo(cx + s * .14f, cy + s * .14f)
        path.lineTo(cx + s * .42f, cy + s * .14f); path.lineTo(cx + s * .42f, cy + s * .38f); path.close()
        c.drawPath(path, stroke)
    }

    /** Duel: two arrows pointing at each other. */
    fun iconDuel(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        c.drawLine(cx - s * .42f, cy - s * .16f, cx + s * .06f, cy - s * .16f, stroke)
        path.reset(); path.moveTo(cx - s * .08f, cy - s * .3f); path.lineTo(cx + s * .08f, cy - s * .16f); path.lineTo(cx - s * .08f, cy - s * .02f)
        c.drawPath(path, stroke)
        c.drawLine(cx + s * .42f, cy + s * .16f, cx - s * .06f, cy + s * .16f, stroke)
        path.reset(); path.moveTo(cx + s * .08f, cy + s * .02f); path.lineTo(cx - s * .08f, cy + s * .16f); path.lineTo(cx + s * .08f, cy + s * .3f)
        c.drawPath(path, stroke)
    }

    /** Time rush: a stopwatch. */
    fun iconStopwatch(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        c.drawCircle(cx, cy + s * .06f, s * .34f, stroke)
        c.drawLine(cx, cy + s * .06f, cx, cy - s * .14f, stroke)
        c.drawLine(cx, cy + s * .06f, cx + s * .14f, cy + s * .14f, stroke)
        c.drawLine(cx - s * .1f, cy - s * .4f, cx + s * .1f, cy - s * .4f, stroke)
        c.drawLine(cx + s * .26f, cy - s * .26f, cx + s * .33f, cy - s * .33f, stroke)
    }

    fun iconChart(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        c.drawLine(cx - s * .36f, cy + s * .36f, cx + s * .38f, cy + s * .36f, stroke)
        c.drawLine(cx - s * .22f, cy + s * .24f, cx - s * .22f, cy + s * .02f, stroke)
        c.drawLine(cx, cy + s * .24f, cx, cy - s * .3f, stroke)
        c.drawLine(cx + s * .22f, cy + s * .24f, cx + s * .22f, cy - s * .1f, stroke)
    }

    fun iconInfo(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        c.drawCircle(cx, cy, s * .38f, stroke)
        c.drawLine(cx, cy - s * .02f, cx, cy + s * .2f, stroke)
        c.drawCircle(cx, cy - s * .18f, s * .02f, stroke)
    }

    fun iconHelp(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(color, s)
        c.drawCircle(cx, cy, s * .38f, stroke)
        tmp.set(cx - s * .13f, cy - s * .22f, cx + s * .13f, cy + s * .04f)
        c.drawArc(tmp, 180f, 250f, false, stroke)
        c.drawLine(cx + s * .02f, cy + s * .04f, cx, cy + s * .1f, stroke)
        c.drawCircle(cx, cy + s * .22f, s * .02f, stroke)
    }

    // ---- the logo ----------------------------------------------------------------------------

    /** The app logo tile (rounded, transparent corners), decoded once. */
    val logo: Bitmap by lazy { android.graphics.BitmapFactory.decodeResource(res, R.drawable.logo_tile) }
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** The logo tile centred on (cx, cy), [size] px square. */
    fun logoTile(c: Canvas, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        logoPaint.alpha = alpha
        tmp.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        c.drawBitmap(logo, null, tmp, logoPaint)
    }

    /**
     * Loading screen: the logo tile breathing in a warm orange glow, a caption and a sweeping bar.
     * [t] is the seconds since it appeared; with reduced motion everything holds still.
     */
    fun loader(c: Canvas, cx: Float, cy: Float, size: Float, t: Float, caption: String) {
        val calm = Motion.reduced
        val pulse = if (calm) .5f else .5f + .5f * sin(t * 2.6f)
        val gr = size * (.78f + .06f * pulse)
        val orange = C.BRAND and 0x00FFFFFF
        fill.style = Paint.Style.FILL; fill.alpha = 255
        fill.shader = RadialGradient(cx, cy, gr, intArrayOf(((38 + 40 * pulse).toInt() shl 24) or orange, orange),
            floatArrayOf(.25f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, gr, fill)
        fill.shader = null
        logoTile(c, cx, cy, size * (1f + .018f * pulse))
        val by = cy + size / 2 + dp(44f)
        text(c, caption, cx, by, dp(14f), C.MUTED, face = medium)
        val bw = size * .8f
        val bar = RectF(cx - bw / 2, by + dp(18f), cx + bw / 2, by + dp(21f))
        c.drawRoundRect(bar, dp(1.5f), dp(1.5f), fillPaint(C.SECONDARY))
        val seg = bw * .38f
        val phase = if (calm) .31f else (t * .85f) % 1f
        val x0 = bar.left - seg + (bw + seg) * phase
        c.save(); c.clipRect(bar)
        c.drawRoundRect(RectF(x0, bar.top, x0 + seg, bar.bottom), dp(1.5f), dp(1.5f), fillPaint(C.BRAND))
        c.restore()
    }

    // ---- names kept from the first art style, now flat ------------------------------------

    /** Life heart: filled foreground when on, faint outline when spent. */
    fun glossyHeart(c: Canvas, cx: Float, cy: Float, size: Float, on: Boolean) =
        if (on) heart(c, cx, cy, size * .82f, C.FG, true) else heart(c, cx, cy, size * .82f, C.FAINT, false)

    fun bulb3d(c: Canvas, cx: Float, cy: Float, s: Float) = iconBulb(c, cx, cy, s * .62f, C.FG)
    fun ruler3d(c: Canvas, cx: Float, cy: Float, s: Float) = iconGrid(c, cx, cy, s * .56f, C.FG)
    fun eraser3d(c: Canvas, cx: Float, cy: Float, s: Float) = iconEraser(c, cx, cy, s * .62f, C.FG)
    fun wand3d(c: Canvas, cx: Float, cy: Float, s: Float) = iconWand(c, cx, cy, s * .62f, C.FG)
    fun calendar3d(c: Canvas, cx: Float, cy: Float, s: Float) = iconCalendar(c, cx, cy, s, C.FG)
    fun podium3d(c: Canvas, cx: Float, cy: Float, s: Float) = iconPodium(c, cx, cy, s, C.FG)

    /** The month's trophy for the Daily tab: outline, cream once the month is complete. */
    fun bigTrophy(c: Canvas, cx: Float, cy: Float, s: Float, complete: Boolean = false) =
        cup(c, cx, cy, s, if (complete) C.HIGHLIGHT else C.FG)

    fun tabHome(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) = iconHome(c, cx, cy, s * .8f, color)
    fun tabDaily(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) = iconCalendar(c, cx, cy, s * .8f, color)
    fun tabMe(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) = iconUser(c, cx, cy, s * .8f, color)

    /** Boxed icon for list rows: hairline square with the icon in the foreground colour. */
    fun rowIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, draw: (Float, Float, Float) -> Unit) {
        tmp.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        c.drawRoundRect(tmp, s * .26f, s * .26f, fillPaint(C.SECONDARY))
        c.drawRoundRect(tmp, s * .26f, s * .26f, strokePaint(C.CARD_EDGE, dp(1f)))
        draw(cx, cy, s)
    }

    /** Tutorial glove. Fingertip at (x, y). */
    fun hand(c: Canvas, x: Float, y: Float, s: Float, pressed: Float) {
        c.save()
        c.translate(x, y); c.rotate(-20f); c.scale(1f - .08f * pressed, 1f - .08f * pressed)
        path.reset()
        tmp.set(-s * .1f, 0f, s * .1f, s * .55f)
        path.addRoundRect(tmp, s * .1f, s * .1f, Path.Direction.CW)
        tmp.set(-s * .14f, s * .36f, s * .36f, s * .86f)
        path.addRoundRect(tmp, s * .16f, s * .16f, Path.Direction.CW)
        tmp.set(-s * .3f, s * .46f, -s * .04f, s * .64f)
        path.addRoundRect(tmp, s * .09f, s * .09f, Path.Direction.CW)
        c.drawPath(path, fillPaint(C.CARD))
        c.drawPath(path, strokePaint(C.FG, s * .045f))
        c.restore()
    }
}
