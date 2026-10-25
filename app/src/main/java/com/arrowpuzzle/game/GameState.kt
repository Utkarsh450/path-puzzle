package com.arrowpuzzle.game

import android.graphics.Path
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

enum class SpriteState { IDLE, MOVING, BUMP, ERASING, GONE }

/**
 * Visual and motion state of one arrow. Geometry is in cell units. While moving, the arrow
 * slides along "ext": its own body, then the cells it travels through (possibly jumping
 * through portals), then a straight run off the board when it exits.
 */
class ArrowSprite(val id: Int, var info: ArrowInfo) {
    var state = if (info.alive) SpriteState.IDLE else SpriteState.GONE

    // cached static geometry, rebuilt when the body changes
    var bodyPath: Path? = null
    var headPath: Path? = null

    // motion track
    var ex = FloatArray(0); var ey = FloatArray(0); var ej = BooleanArray(0); var cum = FloatArray(0)
    var bodyArc = 0f
    var s = 0f
    var v = 0f
    var t = 0f
    var sEnd = 0f
    var dur = 0f
    var exiting = false
    var bumpDist = 0f
    var pendingInfo: ArrowInfo? = null

    // effects
    var flash = 0f
    var red = false          // arrows you made a mistake with stay red (as in the real game)
    var blockT = -1f         // time since this arrow blocked a tap, -1 when idle
    var alpha = 1f
    var appear = 1f
    var appearDelay = 0f
    var headDx = 0f
    var headDy = 0f

    fun invalidate() { bodyPath = null; headPath = null }

    fun prepareMotion(track: List<Pt>, reverse: Boolean, exit: Boolean, dx: Int, dy: Int, far: Int) {
        val body = info.pts
        val seq = ArrayList<Pt>(body.size + track.size + 1)
        if (!reverse) seq.addAll(body)
        else for (i in body.indices.reversed()) seq.add(Pt(body[i].x, body[i].y, i < body.size - 1 && body[i + 1].jump))
        seq.addAll(track)
        if (exit) {
            val l = seq.last()
            seq.add(Pt(l.x + dx * far, l.y + dy * far, false))
        }
        val m = seq.size
        ex = FloatArray(m) { seq[it].x.toFloat() }
        ey = FloatArray(m) { seq[it].y.toFloat() }
        ej = BooleanArray(m) { it > 0 && seq[it].jump }
        cum = FloatArray(m)
        for (i in 1 until m) cum[i] = cum[i - 1] + if (ej[i]) 0f else abs(ex[i] - ex[i - 1]) + abs(ey[i] - ey[i - 1])
        bodyArc = cum[body.size - 1]
        s = 0f; t = 0f; v = 0f
    }

    /** Body window [a, b] of the track. brk[i] = start a new sub-path (portal jump). Returns point count. */
    fun window(a: Float, b: Float, ox: FloatArray, oy: FloatArray, brk: BooleanArray): Int {
        var n = 0
        var started = false
        val m = ex.size
        for (i in 1 until m) {
            if (n >= ox.size - 2) break
            if (ej[i]) {
                if (started && cum[i] > a && cum[i] < b) { ox[n] = ex[i]; oy[n] = ey[i]; brk[n] = true; n++ }
                continue
            }
            val c0 = cum[i - 1]; val c1 = cum[i]
            if (c1 <= a) continue
            if (c0 >= b) break
            val sx = sign(ex[i] - ex[i - 1]); val sy = sign(ey[i] - ey[i - 1])
            if (!started) {
                val u = ((a - c0) / (c1 - c0)).coerceIn(0f, 1f)
                ox[n] = ex[i - 1] + (ex[i] - ex[i - 1]) * u; oy[n] = ey[i - 1] + (ey[i] - ey[i - 1]) * u; brk[n] = true; n++
                started = true
            }
            headDx = sx; headDy = sy
            if (c1 < b) { ox[n] = ex[i]; oy[n] = ey[i]; brk[n] = false; n++ }
            else {
                val u = (b - c0) / (c1 - c0)
                ox[n] = ex[i - 1] + (ex[i] - ex[i - 1]) * u; oy[n] = ey[i - 1] + (ey[i] - ey[i - 1]) * u; brk[n] = false; n++
                break
            }
        }
        return n
    }

    fun headPoint(out: FloatArray) {
        val target = s + bodyArc
        for (i in 1 until ex.size) {
            if (ej[i]) continue
            if (target <= cum[i]) {
                val u = ((target - cum[i - 1]) / max(1e-4f, cum[i] - cum[i - 1])).coerceIn(0f, 1f)
                out[0] = ex[i - 1] + (ex[i] - ex[i - 1]) * u; out[1] = ey[i - 1] + (ey[i] - ey[i - 1]) * u
                return
            }
        }
        out[0] = ex.last(); out[1] = ey.last()
    }

    fun bumpOffset(): Float {
        val fwd = .1f + .02f * bumpDist
        val back = .22f
        return when {
            t < fwd -> bumpDist * Ease.outQuad(t / fwd)
            t < fwd + back -> bumpDist * (1 - Ease.inOutCubic((t - fwd) / back))
            else -> 0f
        }
    }
}

/** Rules, animation state and boosters for one level in play. */
class GameState(private val store: Store, private val sfx: Sfx, private val haptics: Haptics) {
    interface Listener {
        fun onLifeLost(index: Int)
        fun onWin()
        fun onLose()
        fun onPraise(text: String)
        fun onToast(text: String)
        fun onSaved()
    }

    var listener: Listener? = null
    var board: Board? = null
        private set
    var level = 1
    var salt = 0
    var mode = GameMode.CLASSIC
        private set
    val daily get() = mode == GameMode.CLASSIC && Levels.isDaily(salt)
    /** Duel rules; null in the other modes. */
    var duel: DuelGame? = null
        private set
    /** Time rush run, carried from board to board; null in the other modes. */
    var rush: RushGame? = null
        private set
    var sprites: List<ArrowSprite> = emptyList()
    var initial: List<ArrowInfo> = emptyList()
    var features = Features(emptyList(), emptyList(), emptyList(), emptyList())
    var lives = 3
    var mistakes = 0
    var combo = 0
    var hintId = -1
    var hintRev = false
    var eraserArmed = false
    var won = false
    var lost = false
    var secondChanceUsed = false
    var total = 0
    var remaining = 0
    var time = 0f
    var mistakeAge = 9f
    val particles = Particles()
    val stopBreaks = ArrayList<FloatArray>()     // x, y, age
    val zoneUnlock = HashMap<Int, Float>()       // zone index -> age
    val moveLog = StringBuilder()
    private var winNotified = false
    private var loseNotified = false
    private var winDelay = 0f
    private var lastSuccessAt = -10f
    private var streak = 0
    private var hintBusy = false
    private var wandTarget = -1
    private var wandRev = false
    private var wandTimer = 0f
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tmp = FloatArray(2)
    private val redOnReplay = ArrayList<Int>()

    private val praises = listOf("Smooth!", "Sharp move!", "Unbelievable!", "Boom!", "Great move!", "Smart move!", "Good call!", "Awesome!", "Nice work!", "You got it!")

    fun attach(b: Board, replay: String, mode: GameMode = GameMode.CLASSIC, rush: RushGame? = null) {
        board?.close()
        board = b
        level = b.level; salt = b.salt
        this.mode = mode
        initial = List(b.arrowCount) { b.arrow(it) }
        duel = if (mode == GameMode.DUEL) DuelGame() else null
        this.rush = if (mode == GameMode.RUSH) rush ?: RushGame() else null
        lives = 3; mistakes = 0; combo = 0; hintId = -1; eraserArmed = false
        won = false; lost = false; winNotified = false; loseNotified = false; secondChanceUsed = false
        winDelay = 0f; time = 0f; streak = 0; wandTarget = -1; mistakeAge = 9f
        particles.list.clear(); stopBreaks.clear(); zoneUnlock.clear()
        moveLog.setLength(0)
        if (replay.isNotEmpty()) replayMoves(b, replay)
        sprites = List(b.arrowCount) { ArrowSprite(it, b.arrow(it)) }
        for (id in redOnReplay) if (id in sprites.indices) sprites[id].red = true
        redOnReplay.clear()
        features = b.features()
        total = b.arrowCount
        remaining = b.remaining()
        // intro: every arrow draws itself in from the tail at a random moment (real game)
        for (s in sprites) {
            s.appear = 0f
            s.appearDelay = ((s.id * 0.6180339f) % 1f) * .45f
        }
        if (remaining == 0) won = true
    }

    private fun replayMoves(b: Board, log: String) {
        for (tok in log.split(';')) {
            if (tok.isEmpty()) continue
            when (tok[0]) {
                't' -> { val rev = tok.endsWith("r"); b.tap(tok.substring(1).trimEnd('r').toInt(), rev) }
                'e' -> b.erase(tok.substring(1).toInt())
                'm' -> { lives--; mistakes++ }
                'r' -> redOnReplay.add(tok.substring(1).toInt())
                'c' -> { lives = 1; secondChanceUsed = true }
            }
            moveLog.append(tok).append(';')
        }
        if (lives <= 0) lives = 1
    }

    /** A win or loss is waiting for [update] frames before the listener hears of it. */
    val awaitingResult: Boolean
        get() = (won && !winNotified) || (lost && !loseNotified)

    val busy: Boolean
        get() = sprites.any { it.state == SpriteState.MOVING || it.state == SpriteState.BUMP || it.state == SpriteState.ERASING || it.appear < 1f || it.blockT >= 0f } ||
            mistakeAge < 1f ||
            particles.active || stopBreaks.isNotEmpty() || zoneUnlock.isNotEmpty() || wandTarget >= 0

    fun percentCleared(): Int = if (total == 0) 0 else ((1f - remaining.toFloat() / total) * 100).toInt()

    private fun far(): Int { val b = board ?: return 30; return max(b.cols, b.rows) + 24 }

    fun tap(id: Int, reverse: Boolean) {
        val b = board ?: return
        if (won || lost || id !in sprites.indices) return
        val sp = sprites[id]
        if (sp.state != SpriteState.IDLE) return
        if (eraserArmed) { erase(id); return }
        if (!b.tappable(id)) {
            sp.flash = .5f
            listener?.onToast("Unlock the zone with a key first")
            sfx.play(Sound.BUMP)
            return
        }
        val r = b.tap(id, reverse)
        if (r.success) {
            moveLog.append('t').append(id).append(if (reverse) "r" else "").append(';')
            var exits = 0
            for (m in r.moves) {
                val s = sprites[m.arrow]
                if (m.outcome == Outcome.EXIT) {
                    s.prepareMotion(m.track, m.reverse, true, m.dx, m.dy, far())
                    s.state = SpriteState.MOVING; s.exiting = true; s.v = 0f
                    s.sEnd = s.cum.last() - s.bodyArc
                    exits++
                } else {
                    s.prepareMotion(m.track, m.reverse, false, 0, 0, 0)
                    s.state = SpriteState.MOVING; s.exiting = false
                    s.sEnd = s.cum[min(s.cum.size - 1, s.info.pts.size - 1 + m.track.size)] - s.bodyArc
                    s.dur = .14f + .05f * s.sEnd
                    s.pendingInfo = b.arrow(m.arrow)
                    if (m.stop >= 0 && m.stop < features.stops.size) {
                        val st = features.stops[m.stop]
                        st.alive = false
                        stopBreaks.add(floatArrayOf(st.x.toFloat(), st.y.toFloat(), 0f))
                        sfx.play(Sound.STOP)
                    }
                }
                if (hintId == m.arrow) hintId = -1
            }
            for (z in r.unlocked) {
                if (z < features.zones.size) features.zones[z].locked = false
                zoneUnlock[z] = 0f
                sfx.play(Sound.UNLOCK)
                haptics.tick(30)
            }
            store.arrowsCleared = store.arrowsCleared + exits
            onSuccess()
            rush?.cleared(exits)
            if (remaining == 0) rush?.boardCleared()
            duel?.let { d -> d.cleared(exits, remaining == 0); if (d.finished) won = true }
        } else {
            for (m in r.moves) {
                val s = sprites[m.arrow]
                if (m.outcome == Outcome.BLOCKED || m.outcome == Outcome.LOOP) s.red = true
                if (m.blocker >= 0 && m.blocker < sprites.size) sprites[m.blocker].blockT = 0f
            }
            if (r.moves.none { it.outcome == Outcome.BLOCKED || it.outcome == Outcome.LOOP }) sprites[id].red = true
            mistakeAge = 0f
            mistakes++; combo = 0; streak = 0
            sfx.play(Sound.BUMP)
            haptics.tick(45)
            val d = duel; val r = rush
            when {
                // in the modes a mistake costs the player at the board a heart, or time
                d != null -> { d.mistake(); if (d.finished) won = true }
                r != null -> { r.mistake(); listener?.onToast("-${RushGame.MISTAKE_SECONDS.toInt()} seconds"); if (r.finished) won = true }
                else -> {
                    lives--
                    moveLog.append("m;")
                    for (s in sprites) if (s.red) moveLog.append('r').append(s.id).append(';')
                    listener?.onLifeLost(max(0, lives))
                    if (lives <= 0) lost = true
                }
            }
        }
        listener?.onSaved()
    }

    private fun onSuccess() {
        val b = board ?: return
        combo++
        sfx.play(Sound.SLIDE)
        haptics.tick(10)
        streak = if (time - lastSuccessAt < 1.4f) streak + 1 else 1
        lastSuccessAt = time
        if (streak == 5) { listener?.onPraise(praises.random()); streak = 0 }
        remaining = b.remaining()
        if (remaining == 0) won = true
    }

    fun erase(id: Int) {
        val b = board ?: return
        if (mode != GameMode.CLASSIC) return
        eraserArmed = false
        if (!b.tappable(id)) { listener?.onToast("This arrow is locked"); return }
        val unlocked = b.erase(id)
        store.setCount(Booster.ERASER, store.count(Booster.ERASER) - 1)
        moveLog.append('e').append(id).append(';')
        val s = sprites[id]
        s.state = SpriteState.ERASING
        for (p in s.info.pts) particles.burst(p.x.toFloat(), p.y.toFloat(), 3, 1.6f, .1f, intArrayOf(C.ERASER, 0xFF9CC3FF.toInt()), .5f)
        for (z in unlocked) {
            if (z < features.zones.size) features.zones[z].locked = false
            zoneUnlock[z] = 0f
            sfx.play(Sound.UNLOCK)
        }
        if (hintId == id) hintId = -1
        sfx.play(Sound.ERASE)
        haptics.tick(20)
        store.arrowsCleared = store.arrowsCleared + 1
        onSuccess()
        listener?.onSaved()
    }

    private fun requestHint(done: (Pair<Int, Boolean>?) -> Unit) {
        val b = board ?: return
        if (hintBusy) return
        hintBusy = true
        val copy = b.copy()
        exec.execute {
            val h = try { copy.hint() } finally { copy.close() }
            main.post { hintBusy = false; if (board === b) done(h) }
        }
    }

    fun useHint() {
        if (won || lost || hintId >= 0) return
        requestHint { h ->
            if (h == null || won || lost) return@requestHint
            hintId = h.first; hintRev = h.second
            store.setCount(Booster.HINT, store.count(Booster.HINT) - 1)
            store.hintsUsed = store.hintsUsed + 1
            sfx.play(Sound.PICK)
            listener?.onSaved()
        }
    }

    fun useWand() {
        if (won || lost || wandTarget >= 0) return
        requestHint { h ->
            if (h == null || won || lost) return@requestHint
            store.setCount(Booster.WAND, store.count(Booster.WAND) - 1)
            wandTarget = h.first; wandRev = h.second; wandTimer = .5f
            val s = sprites[h.first]
            for (p in s.info.pts) particles.burst(p.x.toFloat(), p.y.toFloat(), 2, 1.2f, .16f, intArrayOf(0xFFFFD25C.toInt(), C.SPARK_A, 0xFFFFFFFF.toInt()), .6f, PKind.STAR)
            sfx.play(Sound.PICK)
        }
    }

    fun continueAfterLoss() {
        lives = 1; lost = false; loseNotified = false; secondChanceUsed = true
        moveLog.append("c;")
        listener?.onSaved()
    }

    /** Whether a duel or rush clock is still running (the HUD keeps animating for it). */
    val clockRunning: Boolean
        get() = !won && !lost && (duel?.finished == false || rush?.finished == false)

    /** Advances animations, and the mode clocks when [clocks] (paused under popups and the intro). */
    fun update(dt: Float, clocks: Boolean = true) {
        time += dt
        if (clocks && clockRunning) {
            duel?.let { d ->
                if (d.tick(dt)) {
                    mistakeAge = 0f
                    sfx.play(Sound.BUMP)
                    if (d.finished) won = true else listener?.onToast("Time's up, Player ${d.current + 1} to play")
                }
            }
            rush?.let { r -> if (r.tick(dt)) won = true }
        }
        for (s in sprites) {
            if (s.appear < 1f) {
                s.appearDelay -= dt
                if (s.appearDelay <= 0f) s.appear = min(1f, s.appear + dt * 3.3f)
            }
            when (s.state) {
                SpriteState.MOVING -> {
                    if (s.exiting) {
                        s.v = min(80f, s.v + 100f * dt)
                        s.s += s.v * dt
                        if (s.s >= s.sEnd) s.state = SpriteState.GONE
                    } else {
                        s.t += dt
                        val k = (s.t / s.dur).coerceIn(0f, 1f)
                        s.s = s.sEnd * Ease.outCubic(k)
                        if (k >= 1f) {
                            s.pendingInfo?.let { s.info = it }
                            s.pendingInfo = null
                            s.invalidate(); s.s = 0f; s.state = SpriteState.IDLE
                        }
                    }
                }
                SpriteState.BUMP -> {
                    s.t += dt
                    if (s.t > .1f + .02f * s.bumpDist + .22f) { s.state = SpriteState.IDLE; s.s = 0f }
                }
                SpriteState.ERASING -> {
                    s.alpha -= dt * 3.5f
                    if (s.alpha <= 0f) { s.alpha = 0f; s.state = SpriteState.GONE }
                }
                else -> {}
            }
            if (s.flash > 0f) s.flash = max(0f, s.flash - dt * 1.4f)
            if (s.blockT >= 0f) { s.blockT += dt; if (s.blockT > .4f) s.blockT = -1f }
        }
        mistakeAge += dt
        particles.update(dt)
        stopBreaks.removeAll { it[2] += dt; it[2] > .7f }
        val zi = zoneUnlock.entries.iterator()
        while (zi.hasNext()) { val e = zi.next(); e.setValue(e.value + dt); if (e.value > 1f) zi.remove() }

        if (wandTarget >= 0) {
            wandTimer -= dt
            if (wandTimer <= 0f) { val id = wandTarget; wandTarget = -1; tap(id, wandRev) }
        }
        if (won && !winNotified) {
            val moving = sprites.any { it.state == SpriteState.MOVING || it.state == SpriteState.ERASING }
            if (!moving) {
                winDelay += dt
                if (winDelay > .25f) { winNotified = true; listener?.onWin() }
            }
        }
        if (lost && !loseNotified && mistakeAge > .85f) {
            loseNotified = true
            listener?.onLose()
        }
    }

    fun close() {
        board?.close(); board = null
    }
}

