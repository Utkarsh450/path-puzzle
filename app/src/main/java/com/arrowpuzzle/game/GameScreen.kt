package com.arrowpuzzle.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The in-level screen: HUD, board camera, boosters and per-level flow. */
class GameScreen(private val v: GameView) : GameState.Listener {
    private val ui get() = v.ui
    private val store get() = v.store
    val state = GameState(v.store, v.sfx, v.haptics).also { it.listener = this }

    var loading = true
        private set
    private var prefetched: Board? = null

    // layout
    private var w = 0f
    private var h = 0f
    private var row1 = 0f
    private var row2 = 0f
    private var boardTop = 0f
    private var boardBottom = 0f
    private var boosterCy = 0f
    private val boosterR get() = ui.dp(34f)

    // camera
    private var k0 = 1f
    private var z = 1f
    private var minZ = 1f

    // level intro (measured on the real game): arrows draw in while the camera zooms out to
    // the whole board, holds, then zooms in to playing size; the HUD pops in last
    private var introT = 99f
    private var ovK = 1f
    private var playOx = 0f
    private var playOy = 0f
    private val introZoomEnd get() = if (k0 / ovK > 1.05f) 2.05f else 1.0f
    private val introEnd get() = introZoomEnd + .55f
    private val inIntro get() = introT < introZoomEnd
    private var ox = 0f
    private var oy = 0f
    private var vx = 0f
    private var vy = 0f
    private val k get() = k0 * z

    // effects
    private val ripples = ArrayList<FloatArray>() // x, y, age
    private var praise = ""
    private var praiseAge = 9f
    private var toast = ""
    private var toastAge = 9f
    private val heartLostAge = floatArrayOf(9f, 9f, 9f)
    // duel: each player's hearts as last drawn, and the age of each heart's pop (player * 3 + heart)
    private val duelHearts = intArrayOf(3, 3)
    private val duelHeartAge = FloatArray(6) { 9f }
    private var tutorialArrow = -1
    private var spinner = 0f

    // ---------------------------------------------------------------------------------
    // Level flow
    // ---------------------------------------------------------------------------------

    /** Time rush: which run this is, so every run gets its own boards. */
    private var rushRun = 0

    fun start(level: Int, salt: Int, resume: Boolean, mode: GameMode = GameMode.CLASSIC, rush: RushGame? = null) {
        loading = true
        val same = store.savedLevel == level && store.savedSalt == salt && store.savedMode == mode.key
        // only the main game and dailies are saved; a duel or a rush is played in one go
        val replay = if (resume && same && mode == GameMode.CLASSIC) store.savedMoves else ""
        val pre = prefetched?.takeIf { it.level == level && it.salt == salt }
        if (pre != null) prefetched = null
        if (pre != null) attach(pre, replay, mode, rush)
        else v.loader.execute {
            val t0 = SystemClock.elapsedRealtime()
            val b = Board(level, salt)
            Log.i("PathPuzzle", "board level $level built in ${SystemClock.elapsedRealtime() - t0} ms (${b.arrowCount} arrows)")
            v.post { attach(b, replay, mode, rush) }
        }
        v.invalidate()
    }

    /** A new Time rush run, starting on its first board. */
    fun startRush(run: Int) {
        rushRun = run
        start(Levels.rushBoardLevel(0), Levels.rushSalt(run, 0), false, GameMode.RUSH, RushGame())
    }

    /** Own thread: the board the player is waiting for must never queue behind a prefetch. */
    private val prefetcher = Executors.newSingleThreadExecutor()

    private fun prefetch(level: Int, salt: Int) = prefetcher.execute {
        val nb = Board(level, salt)
        v.post { prefetched?.close(); prefetched = nb }
    }

    private fun attach(b: Board, replay: String, mode: GameMode, rush: RushGame?) {
        state.attach(b, replay, mode, rush)
        loading = false
        introT = 0f
        ripples.clear(); praiseAge = 9f
        if (rush == null || rush.boards == 0) toastAge = 9f  // keep "+10 seconds" up across rush boards
        heartLostAge.fill(9f)
        duelHearts[0] = 3; duelHearts[1] = 3; duelHeartAge.fill(9f)
        fit()
        if (Motion.reduced) {
            // no draw-in or camera sweep when the system asks for less motion
            introT = introEnd
            for (s in state.sprites) s.appear = 1f
        }
        if (mode == GameMode.CLASSIC) {
            store.savedLevel = b.level; store.savedSalt = b.salt; store.savedMode = mode.key; store.savedMoves = state.moveLog.toString()
        }
        tutorialArrow = -1
        if (b.level == 1 && b.salt == 0 && !store.seen("tut_tap")) tutorialArrow = b.hint()?.first ?: -1
        if (mode != GameMode.CLASSIC && !store.seen("tut_${mode.name.lowercase()}")) {
            store.markSeen("tut_${mode.name.lowercase()}")
            v.showModeTutorial(mode)
        } else if (b.introduces >= 0 && b.introduces < Mechanic.values().size && !store.seen("mech_${b.introduces}")) {
            store.markSeen("mech_${b.introduces}")
            v.showMechanicTutorial(Mechanic.values()[b.introduces])
        } else if (b.cols >= 14 && !store.seen("tut_zoom")) {
            store.markSeen("tut_zoom")
            onToast("Pinch to zoom")
        }
        if (b.salt == 0 && mode == GameMode.CLASSIC) prefetch(b.level + 1, 0)
        state.rush?.let { r ->
            // rush boards follow each other quickly, so the next one is ready before it is needed
            val next = r.boards + 1
            prefetch(Levels.rushBoardLevel(next), Levels.rushSalt(rushRun, next))
        }
        v.invalidate()
    }

    fun layout(width: Float, height: Float) {
        w = width; h = height
        row1 = v.insetTop + ui.dp(30f)
        row2 = v.insetTop + ui.dp(82f)
        boosterCy = height - v.insetBottom - ui.dp(28f) - boosterR
        boardTop = row2 + ui.dp(30f)
        boardBottom = boosterCy - boosterR - ui.dp(14f)
        if (!loading) fit()
    }

    /**
     * Like the real game, every board opens whole, fitted to the screen with ~17 dp side
     * margins; pinching in zooms and then the board pans.
     */
    private fun fit() {
        val b = state.board ?: return
        if (w <= 0f) return
        val availW = w - ui.dp(34f)
        val availH = boardBottom - boardTop
        val kFit = min(min(availW / b.cols, availH / b.rows), ui.dp(64f))
        k0 = kFit
        minZ = kFit / k0
        z = 1f; vx = 0f; vy = 0f
        val bw = b.cols * k0
        val bh = b.rows * k0
        ox = (w - bw) / 2f
        oy = if (bh <= availH) boardTop + (availH - bh) / 2f else boardTop + ui.dp(10f)
        clampCamera()
        ovK = kFit; playOx = ox; playOy = oy
    }

    private fun updateIntroCamera() {
        val b = state.board ?: return
        val viewCx = w / 2f
        val viewCy = (boardTop + boardBottom) / 2f
        val bcx = b.cols / 2f; val bcy = b.rows / 2f
        val playCx = (viewCx - playOx) / k0
        val playCy = (viewCy - playOy) / k0
        val t = introT
        val kk: Float; val cx: Float; val cy: Float
        when {
            t < .75f -> {   // zoom out while the arrows draw in
                val e = Ease.outCubic(t / .75f)
                kk = ovK * 1.45f + (ovK - ovK * 1.45f) * e; cx = bcx; cy = bcy
            }
            t < 1.15f || introZoomEnd < 1.5f -> { kk = ovK; cx = bcx; cy = bcy }
            t < introZoomEnd -> {   // zoom in to playing size
                val e = Ease.inOutCubic((t - 1.15f) / (introZoomEnd - 1.15f))
                kk = ovK * Math.pow((k0 / ovK).toDouble(), e.toDouble()).toFloat()
                cx = bcx + (playCx - bcx) * e; cy = bcy + (playCy - bcy) * e
            }
            else -> { kk = k0; cx = playCx; cy = playCy }
        }
        z = kk / k0
        ox = viewCx - cx * kk
        oy = viewCy - cy * kk
        if (t >= introZoomEnd) { ox = playOx; oy = playOy; z = 1f }
    }

    private fun maxZoom(): Float = max(1f, ui.dp(54f) / k0)

    private fun clampCamera() {
        val b = state.board ?: return
        val bw = b.cols * k; val bh = b.rows * k
        val m = ui.dp(36f)
        val availH = boardBottom - boardTop
        ox = if (bw <= w - 2 * m) (w - bw) / 2f else ox.coerceIn(w - bw - m, m)
        oy = if (bh <= availH) boardTop + (availH - bh) / 2f else oy.coerceIn(boardBottom - bh - m, boardTop + m)
    }

    fun onScale(fx: Float, fy: Float, factor: Float) {
        if (loading || inIntro) return
        val nz = (z * factor).coerceIn(minZ, maxZoom())
        val r = nz / z
        ox = fx - (fx - ox) * r; oy = fy - (fy - oy) * r
        z = nz
        clampCamera()
    }

    private fun pannable(): Boolean {
        val b = state.board ?: return false
        return b.cols * k > w - ui.dp(40f) || b.rows * k > boardBottom - boardTop
    }

    fun onDrag(dx: Float, dy: Float) {
        if (loading || inIntro || !pannable()) return
        ox += dx; oy += dy; vx = 0f; vy = 0f
        clampCamera()
    }

    fun onFling(fvx: Float, fvy: Float) { if (pannable()) { vx = fvx; vy = fvy } }

    fun onTap(x: Float, y: Float) {
        if (loading || inIntro) return
        ripples.add(floatArrayOf(x, y, 0f))
        if (y < boardTop - ui.dp(4f) || y > boardBottom + ui.dp(4f)) return
        val hit = arrowAt(x, y) ?: return
        v.sfx.play(Sound.TAP)
        state.tap(hit.first, hit.second)
        if (tutorialArrow >= 0 && state.combo > 0) { tutorialArrow = -1; store.markSeen("tut_tap") }
    }

    /** Nearest tappable arrow under (x, y) and, for two-way arrows, which end was tapped. */
    private fun arrowAt(x: Float, y: Float): Pair<Int, Boolean>? {
        val bx = (x - ox) / k - .5f
        val by = (y - oy) / k - .5f
        var best = -1
        var bestRev = false
        var bd = max(.5f, ui.dp(15f) / k)
        for (s in state.sprites) {
            if (s.state != SpriteState.IDLE) continue
            val pts = s.info.pts
            var arc = 0f
            var total = 0f
            for (i in 1 until pts.size) if (!pts[i].jump) total += 1f
            var tapArc = -1f
            var localBest = Float.MAX_VALUE
            for (i in 1 until pts.size) {
                if (pts[i].jump) continue
                val ax = pts[i - 1].x.toFloat(); val ay = pts[i - 1].y.toFloat()
                val ex = pts[i].x.toFloat(); val ey = pts[i].y.toFloat()
                val t = (((bx - ax) * (ex - ax) + (by - ay) * (ey - ay))).coerceIn(0f, 1f)
                val d = hypot(bx - (ax + (ex - ax) * t), by - (ay + (ey - ay) * t))
                if (d < localBest) { localBest = d; tapArc = arc + t }
                arc += 1f
            }
            // the head triangle sticks out past the last cell
            val hd = pts.last()
            val tipD = hypot(bx - (hd.x + s.info.dx * .3f), by - (hd.y + s.info.dy * .3f))
            if (tipD < localBest) { localBest = tipD; tapArc = total }
            if (s.info.twoWay) {
                val tl = pts.first()
                val tailD = hypot(bx - (tl.x + s.info.tdx * .3f), by - (tl.y + s.info.tdy * .3f))
                if (tailD < localBest) { localBest = tailD; tapArc = 0f }
            }
            if (localBest < bd) {
                bd = localBest; best = s.id
                bestRev = s.info.twoWay && tapArc < total / 2f
            }
        }
        return if (best >= 0) best to bestRev else null
    }

    fun restart() = when (state.mode) {
        GameMode.RUSH -> v.startRush()
        GameMode.DUEL -> v.startDuel()
        GameMode.CLASSIC -> start(state.level, state.salt, false)
    }

    fun saveProgress() {
        if (state.mode == GameMode.CLASSIC && state.board != null && !state.won && !state.lost) store.savedMoves = state.moveLog.toString()
    }

    // ---------------------------------------------------------------------------------
    // GameState.Listener
    // ---------------------------------------------------------------------------------

    override fun onLifeLost(index: Int) { if (index in 0..2) heartLostAge[index] = 0f; v.invalidate() }
    override fun onPraise(text: String) { praise = text; praiseAge = 0f }
    override fun onToast(text: String) { toast = text; toastAge = 0f; v.invalidate() }
    override fun onSaved() { saveProgress(); v.invalidate() }

    override fun onWin() {
        state.duel?.let { onDuelOver(it); return }
        state.rush?.let { r ->
            if (r.finished) onRushOver(r)
            else {
                // the board is clear and the clock is still running: on to the next one
                onToast("+${RushGame.BOARD_SECONDS.toInt()} seconds")
                v.sfx.play(Sound.UNLOCK)
                start(Levels.rushBoardLevel(r.boards), Levels.rushSalt(rushRun, r.boards), false, GameMode.RUSH, r)
            }
            return
        }
        val perfect = state.mistakes == 0
        val reward = if (state.daily) 50 else 10 + min(30, state.level / 2) + if (perfect) 5 else 0
        store.coins = store.coins + reward
        store.levelsCompleted = store.levelsCompleted + 1
        if (perfect) store.perfectLevels = store.perfectLevels + 1
        store.winStreak = store.winStreak + 1
        if (store.winStreak > store.bestStreak) store.bestStreak = store.winStreak
        if (state.daily) store.setDailyDone(state.salt) else store.level = state.level + 1
        store.clearSave()
        v.sfx.play(Sound.WIN)
        v.haptics.tick(60)
        val b = state.board ?: return
        v.showWin(WinInfo(state.level, state.salt, reward, perfect,
            v.renderer.recordPreview(state.initial, b.cols, b.rows), b.cols, b.rows, state.mode))
    }

    /** Pass-and-play has no coins (two players could farm them); just the result. */
    private fun onDuelOver(d: DuelGame) {
        store.duelsPlayed = store.duelsPlayed + 1
        v.sfx.play(Sound.WIN)
        v.haptics.tick(60)
        val b = state.board ?: return
        v.showWin(WinInfo(state.level, state.salt, 0, false,
            v.renderer.recordPreview(state.initial, b.cols, b.rows), b.cols, b.rows, GameMode.DUEL,
            title = ModeCopy.duelTitle(d), lead = ModeCopy.duelLead(d),
            keyword = if (d.winner == DuelGame.DRAW) null else "Player ${d.winner + 1}",
            detail = "${d.scores[0]}  :  ${d.scores[1]}"))
    }

    /** A coin per 20 points, at most 50, and the best score is kept. */
    private fun onRushOver(r: RushGame) {
        val best = r.score > store.rushBest
        if (best) store.rushBest = r.score
        val reward = min(50, r.score / 20)
        store.coins = store.coins + reward
        v.sfx.play(Sound.WIN)
        v.haptics.tick(60)
        val b = state.board ?: return
        v.showWin(WinInfo(state.level, state.salt, reward, false,
            v.renderer.recordPreview(state.initial, b.cols, b.rows), b.cols, b.rows, GameMode.RUSH,
            title = "Time's up", lead = ModeCopy.rushLead(r), keyword = "cleared",
            detail = if (best) "${r.score} points, a new best" else "${r.score} points, best ${store.rushBest}"))
    }

    override fun onLose() {
        store.winStreak = 0
        v.sfx.play(Sound.LOSE)
        v.showLose(state.percentCleared(), !state.secondChanceUsed)
    }

    // ---------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------

    val needsFrame: Boolean
        get() = loading || introT < introEnd || state.busy || state.awaitingResult || state.clockRunning || state.hintId >= 0 || ripples.isNotEmpty() || state.mistakeAge < .4f || praiseAge < 1.3f || toastAge < 2f ||
            heartLostAge.any { it < .8f } || abs(vx) + abs(vy) > 1f || tutorialArrow >= 0 ||
            state.features.portals.isNotEmpty() || state.features.redirects.isNotEmpty() || state.features.stops.any { it.alive } ||
            state.sprites.any { it.info.link >= 0 && it.state != SpriteState.GONE }

    fun draw(c: Canvas, dt: Float) {
        if (loading) {
            spinner += dt
            ui.loader(c, w / 2, h * .42f, ui.dp(132f), spinner, "Loading your board")
            drawTopBar(c)
            return
        }
        // the mode clocks wait for the intro and stop under popups
        state.update(dt, clocks = introT >= introZoomEnd && !v.hasOverlay)
        if (introT < introEnd) {
            if (introT < introZoomEnd + .05f) updateIntroCamera()
            introT += dt
        }
        if (abs(vx) + abs(vy) > 1f) {
            ox += vx * dt; oy += vy * dt
            val d = exp(-5f * dt); vx *= d; vy *= d
            clampCamera()
        }
        // the board lies straight on the page; only the arrows carry the real game's look
        val area = boardArea()
        c.save()
        c.clipRect(0f, area.top, w, area.bottom)
        v.renderer.draw(c, state, ui, k, ox, oy, dt, store.gridGuide)
        c.restore()
        drawEdgeFades(c, area)
        drawMistakeVignette(c)
        drawTutorial(c)
        drawTopBar(c)
        val hudA = Ease.clamp01((introT - introZoomEnd) / .3f)
        if (hudA < 1f) c.saveLayerAlpha(0f, 0f, w, h, (255 * hudA).toInt())
        drawStats(c, dt)
        drawBoosters(c)
        if (hudA < 1f) c.restore()
        drawRipples(c, dt)
        drawPraise(c, dt)
        drawToast(c, dt)
    }

    /** Red glow along the screen edges after a mistake (~300 ms on the real game). */
    private fun drawMistakeVignette(c: Canvas) {
        val t = state.mistakeAge
        if (t >= .33f) return
        val k = if (t < .08f) t / .08f else 1f - (t - .08f) / .25f
        val ew = ui.dp(34f)
        val red = C.MISTAKE and 0x00FFFFFF
        val a = ((0x70 * k).toInt().coerceIn(0, 255)) shl 24
        ui.fill.style = Paint.Style.FILL; ui.fill.alpha = 255
        ui.fill.shader = android.graphics.LinearGradient(0f, 0f, ew, 0f, red or a, red, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, ew, h, ui.fill)
        ui.fill.shader = android.graphics.LinearGradient(w, 0f, w - ew, 0f, red or a, red, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(w - ew, 0f, w, h, ui.fill)
        ui.fill.shader = null
    }

    /** Soft fade where the board meets the HUD and the booster bar (GameBoardFade*). */
    /** The screen area the board is drawn in, between the HUD and the boosters. */
    fun boardArea() = RectF(0f, boardTop - ui.dp(8f), w, boardBottom + ui.dp(8f))

    /** Soft fade where the board meets the HUD and the booster bar (GameBoardFade*). */
    private fun drawEdgeFades(c: Canvas, area: RectF) {
        val f = ui.dp(26f)
        val clear = C.BG and 0x00FFFFFF
        ui.fill.style = Paint.Style.FILL; ui.fill.alpha = 255
        ui.fill.shader = android.graphics.LinearGradient(0f, area.top, 0f, area.top + f, C.BG, clear, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(area.left, area.top, area.right, area.top + f, ui.fill)
        ui.fill.shader = android.graphics.LinearGradient(0f, area.bottom - f, 0f, area.bottom, clear, C.BG, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(area.left, area.bottom - f, area.right, area.bottom, ui.fill)
        ui.fill.shader = null
    }

    private fun drawTopBar(c: Canvas) {
        val pad = ui.dp(16f)
        ui.iconBack(c, pad + ui.dp(8f), row1, ui.dp(24f), C.FG)
        ui.hit("g_back", RectF(0f, row1 - ui.dp(24f), pad + ui.dp(40f), row1 + ui.dp(24f))) { v.openMenu() }
        val title = when {
            state.mode == GameMode.DUEL -> "Duel"
            state.mode == GameMode.RUSH -> "Time rush, board ${(state.rush?.boards ?: 0) + 1}"
            state.daily -> java.time.LocalDate.of(state.salt / 10000, state.salt / 100 % 100, state.salt % 100).let {
                it.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + " " + it.dayOfMonth
            }
            else -> "Level ${state.level}"
        }
        ui.text(c, title, w / 2, row1 + ui.dp(6.5f), ui.dp(18f), C.FG, face = ui.medium)
        ui.iconGear(c, w - pad - ui.dp(12f), row1, ui.dp(24f), C.FG)
        ui.hit("g_settings", RectF(w - pad - ui.dp(44f), row1 - ui.dp(24f), w, row1 + ui.dp(24f))) { v.showSettings(true) }
    }

    private fun drawStats(c: Canvas, dt: Float) {
        state.duel?.let { drawDuelHud(c, it, dt); return }
        state.rush?.let { drawRushHud(c, it); return }
        val pad = ui.dp(16f)
        val ph = ui.dp(40f)
        // arrows left
        val count = state.remaining.toString()
        val lw = ui.dp(52f) + ui.textW(count, ui.dp(16f), ui.medium)
        val left = RectF(pad, row2 - ph / 2, pad + lw, row2 + ph / 2)
        ui.pill(c, left)
        ui.iconArrowCount(c, left.left + ui.dp(20f), row2, ui.dp(15f), C.FG)
        ui.text(c, count, left.left + ui.dp(36f), row2 + ui.dp(5.8f), ui.dp(16f), C.FG, Paint.Align.LEFT, ui.medium)
        // difficulty, as a Level chip: meaning in the text and a thin outline
        val di = (state.board?.difficulty ?: 0).coerceIn(0, 3)
        val diff = Levels.names[di]
        val tone = when (di) { 0 -> C.GREEN; 1 -> C.YELLOW; 2 -> C.RED; else -> C.PURPLE }
        val dw = ui.dp(28f) + ui.textW(diff, ui.dp(15f), ui.medium)
        val right = RectF(w - pad - dw, row2 - ph / 2, w - pad, row2 + ph / 2)
        c.drawRoundRect(right, ui.dp(10f), ui.dp(10f), ui.strokePaint(tone and 0x00FFFFFF or 0x66000000, ui.dp(1f)))
        ui.text(c, diff, right.centerX(), row2 + ui.dp(5.5f), ui.dp(15f), tone, face = ui.medium)
        // hearts
        val size = ui.dp(30f); val gap = ui.dp(8f)
        val x0 = w / 2 - (3 * size + 2 * gap) / 2 + size / 2
        for (i in 0 until 3) {
            heartLostAge[i] += dt
            val a = heartLostAge[i]
            val on = i < state.lives
            var sc = 1f
            if (a < .45f) sc = 1f + .35f * sin(a / .45f * PI).toFloat()
            val pop = Ease.clamp01((introT - introZoomEnd - .08f - i * .12f) / .25f)
            if (pop < 1f) sc *= Ease.outBack(pop).coerceAtLeast(0f)
            ui.glossyHeart(c, x0 + i * (size + gap), row2, size * sc, on)
            if (!on && a < .6f) {
                val k = a / .6f
                ui.fillPaint(C.HEART, (255 * (1 - k)).toInt())
                for (j in 0 until 5) {
                    val ang = j * 1.25f + .3f
                    c.drawCircle(x0 + i * (size + gap) + kotlin.math.cos(ang) * size * k, row2 + sin(ang) * size * k + size * k * k, size * .1f * (1 - k), ui.fill)
                }
            }
        }
    }

    /** Duel HUD: a panel per player with hearts and points; the one to play is lit, with the turn clock under it. */
    private fun drawDuelHud(c: Canvas, d: DuelGame, dt: Float) {
        val pad = ui.dp(16f); val gap = ui.dp(10f)
        val pw = (w - pad * 2 - gap) / 2
        val ph = ui.dp(46f)
        val rad = ui.dp(12f)
        for (p in 0..1) {
            // pop the heart that was just lost
            if (d.hearts[p] < duelHearts[p]) {
                for (i in d.hearts[p].coerceAtLeast(0) until duelHearts[p]) duelHeartAge[p * 3 + i] = 0f
                duelHearts[p] = d.hearts[p]
            }
            val left = pad + p * (pw + gap)
            val r = RectF(left, row2 - ph / 2, left + pw, row2 + ph / 2)
            val active = !d.finished && !state.won && d.current == p
            ui.card(c, r, rad = rad)
            if (active) c.drawRoundRect(r, rad, rad, ui.strokePaint(C.FG, ui.dp(1.5f)))
            val col = if (active) C.FG else C.MUTED
            val label = ModeCopy.turnLabel(p, if (active) d.timeLeft else null)
            ui.text(c, label, r.left + ui.dp(12f), row2 - ui.dp(4f), ui.dp(13f),
                if (active && d.timeLeft < 3f) C.RED else col, Paint.Align.LEFT, ui.medium)
            for (i in 0 until 3) {
                val a = duelHeartAge[p * 3 + i]
                duelHeartAge[p * 3 + i] = a + dt
                val sc = if (a < .45f) 1f + .35f * sin(a / .45f * PI).toFloat() else 1f
                ui.glossyHeart(c, r.left + ui.dp(19f) + i * ui.dp(17f), row2 + ui.dp(11f), ui.dp(15f) * sc, i < d.hearts[p])
            }
            ui.text(c, d.scores[p].toString(), r.right - ui.dp(14f), row2 + ui.dp(8f), ui.dp(22f), col, Paint.Align.RIGHT, ui.displayBold)
            if (active) {
                // the turn clock: a bar under the panel, red for the last three seconds
                val f = (d.timeLeft / d.turnSeconds).coerceIn(0f, 1f)
                val bar = RectF(r.left + ui.dp(12f), r.bottom + ui.dp(5f), r.right - ui.dp(12f), r.bottom + ui.dp(9f))
                c.drawRoundRect(bar, ui.dp(1.5f), ui.dp(1.5f), ui.fillPaint(C.SECONDARY))
                val fill = RectF(bar.left, bar.top, bar.left + bar.width() * f, bar.bottom)
                if (fill.width() > 0f) c.drawRoundRect(fill, ui.dp(1.5f), ui.dp(1.5f), ui.fillPaint(if (d.timeLeft < 3f) C.RED else C.FG))
            }
        }
    }

    /** Rush HUD: points on the left, the clock in the middle, the combo multiplier on the right. */
    private fun drawRushHud(c: Canvas, r: RushGame) {
        val pad = ui.dp(16f)
        val ph = ui.dp(40f)
        val pts = "${r.score} pts"
        val lw = ui.dp(32f) + ui.textW(pts, ui.dp(16f), ui.medium)
        val left = RectF(pad, row2 - ph / 2, pad + lw, row2 + ph / 2)
        ui.pill(c, left)
        ui.text(c, pts, left.centerX(), row2 + ui.dp(5.8f), ui.dp(16f), C.FG, face = ui.medium)
        val low = r.timeLeft < 10f
        ui.text(c, ModeCopy.clock(r.timeLeft), w / 2, row2 + ui.dp(9f), ui.dp(26f), if (low) C.RED else C.FG, face = ui.displayBold)
        val m = "x${r.multiplier}"
        val hot = r.multiplier > 1
        val dw = ui.dp(28f) + ui.textW(m, ui.dp(15f), ui.medium)
        val right = RectF(w - pad - dw, row2 - ph / 2, w - pad, row2 + ph / 2)
        c.drawRoundRect(right, ui.dp(10f), ui.dp(10f), ui.strokePaint(if (hot) C.HIGHLIGHT else C.CARD_EDGE_HOVER, ui.dp(1f)))
        ui.text(c, m, right.centerX(), row2 + ui.dp(5.5f), ui.dp(15f), if (hot) C.HIGHLIGHT else C.MUTED, face = ui.medium)
    }

    private fun drawBoosters(c: Canvas) {
        // duels and rushes are played on skill alone
        if (state.mode != GameMode.CLASSIC) return
        val shown = Booster.values()
            .filter { state.daily || state.level >= it.unlockLevel }
            .sortedBy { if (it == Booster.GRID) 9 else it.ordinal }
        val slots = shown.size
        if (slots == 0) return
        val gap = ui.dp(26f)
        val total = slots * 2 * boosterR + (slots - 1) * gap
        var cx = w / 2 - total / 2 + boosterR
        for (b in shown) {
            val active = when (b) {
                Booster.ERASER -> state.eraserArmed
                Booster.GRID -> store.gridGuide
                Booster.HINT -> state.hintId >= 0
                Booster.WAND -> false
            }
            ui.circleButton(c, "boost_${b.key}", cx, boosterCy, boosterR, active) { onBooster(b) }
            val icon = ui.dp(28f)
            val col = if (active) C.HIGHLIGHT else C.FG
            when (b) {
                Booster.HINT -> ui.iconBulb(c, cx, boosterCy, icon, col)
                Booster.ERASER -> ui.iconEraser(c, cx, boosterCy, icon, col)
                Booster.WAND -> ui.iconWand(c, cx, boosterCy, icon, col)
                Booster.GRID -> ui.iconGrid(c, cx, boosterCy, icon * .9f, col)
            }
            if (b != Booster.GRID) {
                val n = store.count(b)
                ui.badge(c, cx + boosterR * .72f, boosterCy - boosterR * .72f, if (n > 0) n.toString() else "+")
            }
            cx += 2 * boosterR + gap
        }
    }

    private fun onBooster(b: Booster) {
        if (state.won || state.lost) return
        if (b == Booster.GRID) {
            store.gridGuide = !store.gridGuide
            v.sfx.play(Sound.PICK)
            return
        }
        if (store.count(b) <= 0) { v.showGetBooster(b); return }
        when (b) {
            Booster.HINT -> state.useHint()
            Booster.ERASER -> {
                state.eraserArmed = !state.eraserArmed
                v.sfx.play(Sound.PICK)
                if (state.eraserArmed) onToast("Tap an arrow to erase it")
            }
            Booster.WAND -> state.useWand()
            Booster.GRID -> {}
        }
    }

    private fun drawRipples(c: Canvas, dt: Float) {
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            r[2] += dt
            val a = r[2] / .16f
            if (a >= 1f) { it.remove(); continue }
            // ParticlesTapView: a soft grey dot that fades out (~150 ms on the real game)
            ui.fillPaint(0xFFC8CCD2.toInt(), (200 * (1 - a)).toInt())
            c.drawCircle(r[0], r[1], ui.dp(11f) * (.85f + .15f * a), ui.fill)
        }
    }

    private fun drawTutorial(c: Canvas) {
        val id = tutorialArrow
        if (id < 0 || id >= state.sprites.size) return
        val s = state.sprites[id]
        if (s.state != SpriteState.IDLE) return
        val p = s.info.pts[s.info.pts.size / 2]
        val x = ox + (p.x + .5f) * k
        val y = oy + (p.y + .5f) * k
        val t = (state.time % 1.4f) / 1.4f
        val press = if (t in .4f..0.6f) 1f else 0f
        val lift = if (t < .4f) (1 - t / .4f) * ui.dp(10f) else if (t > .6f) ((t - .6f) / .4f) * ui.dp(10f) else 0f
        if (press > 0f) {
            ui.strokePaint(C.HIGHLIGHT, ui.dp(2f), 180)
            c.drawCircle(x, y, ui.dp(18f), ui.stroke)
        }
        ui.hand(c, x + ui.dp(4f), y + lift + ui.dp(4f), ui.dp(54f), press)
        // tooltip
        val label = "Tap to remove"
        val tw = ui.textW(label, ui.dp(14f), ui.medium) + ui.dp(28f)
        val r = RectF(x - tw / 2, y - ui.dp(76f), x + tw / 2, y - ui.dp(44f))
        if (r.left < ui.dp(8f)) r.offset(ui.dp(8f) - r.left, 0f)
        if (r.right > w - ui.dp(8f)) r.offset(w - ui.dp(8f) - r.right, 0f)
        ui.dialogCard(c, r)
        ui.text(c, label, r.centerX(), r.centerY() + ui.dp(5f), ui.dp(14f), C.FG, face = ui.medium)
    }

    private fun drawPraise(c: Canvas, dt: Float) {
        praiseAge += dt
        val t = praiseAge / 1.2f
        if (t >= 1f) return
        val a = when { t < .15f -> t / .15f; t < .75f -> 1f; else -> 1f - (t - .75f) / .25f }
        val sc = if (t < .2f) .6f + .4f * Ease.outBack(t / .2f) else 1f
        val y = boardTop + ui.dp(30f) - ui.dp(16f) * t
        c.save(); c.translate(w / 2, y); c.scale(sc, sc)
        // the highlight face, with a halo in the page colour so it reads over arrows
        ui.tp.typeface = ui.display; ui.tp.textSize = ui.dp(26f); ui.tp.textAlign = Paint.Align.CENTER; ui.tp.letterSpacing = -.025f
        ui.tp.style = Paint.Style.STROKE; ui.tp.strokeWidth = ui.dp(6f); ui.tp.color = C.BG; ui.tp.alpha = (255 * a).toInt()
        c.drawText(praise, 0f, 0f, ui.tp)
        ui.tp.style = Paint.Style.FILL; ui.tp.color = C.HIGHLIGHT; ui.tp.alpha = (255 * a).toInt()
        c.drawText(praise, 0f, 0f, ui.tp)
        ui.tp.strokeWidth = 0f; ui.tp.letterSpacing = 0f
        c.restore()
    }

    /** ChaiUI toast: the dialog surface with its warm edge. */
    private fun drawToast(c: Canvas, dt: Float) {
        toastAge += dt
        if (toastAge > 2f) return
        val a = when { toastAge < .2f -> toastAge / .2f; toastAge > 1.6f -> (2f - toastAge) / .4f; else -> 1f }
        val tw = ui.textW(toast, ui.dp(14f), ui.medium) + ui.dp(32f)
        val y = boosterCy - boosterR - ui.dp(34f)
        val r = RectF(w / 2 - tw / 2, y - ui.dp(18f), w / 2 + tw / 2, y + ui.dp(18f))
        c.saveLayerAlpha(r.left - 2, r.top - 2, r.right + 2, r.bottom + 2, (255 * a).toInt())
        ui.dialogCard(c, r)
        ui.text(c, toast, w / 2, y + ui.dp(5f), ui.dp(14f), C.FG, face = ui.medium)
        c.restore()
    }

    fun onBack() { saveProgress(); v.openMenu() }
}

class WinInfo(
    val level: Int, val salt: Int, val reward: Int, val perfect: Boolean,
    val preview: android.graphics.Picture, val cols: Int, val rows: Int,
    val mode: GameMode = GameMode.CLASSIC,
    /** Mode results: their own title and lead, and a score line under the board. */
    val title: String? = null, val lead: String? = null, val keyword: String? = null, val detail: String? = null
)
