package com.arrowpuzzle.game

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

object Ease {
    fun outQuad(t: Float) = 1 - (1 - t) * (1 - t)
    fun outCubic(t: Float) = 1 - (1 - t).pow(3)
    fun inOutCubic(t: Float) = if (t < .5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
    fun outBack(t: Float): Float {
        val c1 = 1.70158f; val c3 = c1 + 1
        return 1 + c3 * (t - 1).pow(3) + c1 * (t - 1).pow(2)
    }
    fun clamp01(t: Float) = t.coerceIn(0f, 1f)
}

/** The system's "remove animations" setting, Android's prefers-reduced-motion. */
object Motion {
    val reduced: Boolean get() = !android.animation.ValueAnimator.areAnimatorsEnabled()
}

enum class PKind { DOT, STAR, RECT, RING }

class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var life: Float, val maxLife: Float, val size: Float, val color: Int, val kind: PKind,
    var rot: Float = 0f, val vr: Float = 0f, val gravity: Float = 0f, val drag: Float = 0f
)

/** A small particle system. Coordinates are in whatever space the caller draws in. */
class Particles {
    val list = ArrayList<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    val active get() = list.isNotEmpty()

    fun add(p: Particle) { if (list.size < 600) list.add(p) }

    /** Radial burst of sparkles (the "arrow solved" effect). */
    fun burst(x: Float, y: Float, n: Int, speed: Float, size: Float, colors: IntArray, life: Float = .6f, kind: PKind = PKind.DOT) {
        repeat(n) {
            val a = Random.nextFloat() * 2 * PI.toFloat()
            val v = speed * (.35f + Random.nextFloat() * .65f)
            add(Particle(x, y, cos(a) * v, sin(a) * v, life * (.6f + Random.nextFloat() * .4f), life,
                size * (.6f + Random.nextFloat() * .7f), colors[Random.nextInt(colors.size)], kind,
                Random.nextFloat() * 360f, (Random.nextFloat() - .5f) * 600f, drag = 2.2f))
        }
    }

    fun update(dt: Float) {
        val it = list.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            val d = 1f / (1f + p.drag * dt)
            p.vx *= d; p.vy = p.vy * d + p.gravity * dt
            p.x += p.vx * dt; p.y += p.vy * dt
            p.rot += p.vr * dt
        }
    }

    fun draw(c: Canvas) {
        for (p in list) {
            val k = (p.life / p.maxLife).coerceIn(0f, 1f)
            paint.color = p.color
            paint.alpha = (255 * (if (p.kind == PKind.RECT) 1f.coerceAtMost(k * 3) else k)).toInt()
            when (p.kind) {
                PKind.DOT -> { paint.style = Paint.Style.FILL; c.drawCircle(p.x, p.y, p.size * (.4f + .6f * k), paint) }
                PKind.RING -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = p.size * .25f
                    c.drawCircle(p.x, p.y, p.size * (1.6f - k), paint)
                }
                PKind.STAR -> {
                    paint.style = Paint.Style.FILL
                    c.save(); c.translate(p.x, p.y); c.rotate(p.rot)
                    star4(path, p.size * (.5f + .5f * k)); c.drawPath(path, paint); c.restore()
                }
                PKind.RECT -> {
                    paint.style = Paint.Style.FILL
                    c.save(); c.translate(p.x, p.y); c.rotate(p.rot)
                    val w = p.size; val h = p.size * .5f * (.4f + .6f * kotlin.math.abs(sin(p.rot / 40f)))
                    c.drawRect(-w / 2, -h / 2, w / 2, h / 2, paint); c.restore()
                }
            }
        }
    }

    companion object {
        /** Four-pointed sparkle centred on the origin. */
        fun star4(path: Path, r: Float) {
            path.reset()
            val k = r * .28f
            path.moveTo(0f, -r); path.quadTo(k, -k, r, 0f); path.quadTo(k, k, 0f, r)
            path.quadTo(-k, k, -r, 0f); path.quadTo(-k, -k, 0f, -r); path.close()
        }
    }
}
