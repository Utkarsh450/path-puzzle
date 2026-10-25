package com.arrowpuzzle.game

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/** Hosts the menu and game screens plus overlays, and owns the frame loop and touch routing. */
class GameView(context: Context) : View(context) {
    val ui = Ui(resources)
    val store = Store(context)
    val sfx = Sfx(store)
    val haptics = Haptics(context, store)
    val renderer = BoardRenderer()
    val loader: ExecutorService = Executors.newSingleThreadExecutor()
    /** Ads (AdMob). Plain contexts, as in rendering tests, and any SDK trouble just mean no ads. */
    val ads = Ads(
        try { (context as? Activity)?.let(AdsConfig.provider) ?: NoAds } catch (e: Throwable) { NoAds },
        AdsConfig.policy())

    var insetTop = 0f
        private set
    var insetBottom = 0f
        private set

    val game = GameScreen(this)
    val menu = MenuScreen(this)
    var inGame = false
        private set

    private val overlays = ArrayList<Overlay>()
    /** A popup is open: the duel and rush clocks wait. */
    val hasOverlay get() = overlays.isNotEmpty()
    private var overlayHitStart = 0
    private var screenAge = 9f
    private var lastFrame = System.nanoTime()

    // touch
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var multi = false
    private var tracker: VelocityTracker? = null
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (inGame && overlays.isEmpty()) { game.onScale(d.focusX, d.focusY, d.scaleFactor); invalidate() }
            return true
        }
    })

    init {
        setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            run { insetTop = ins.systemWindowInsetTop.toFloat(); insetBottom = ins.systemWindowInsetBottom.toFloat() }
            relayout(); invalidate(); ins
        }
        C.dark = store.darkTheme
        applySystemBars()
    }

    /** ChaiUI theme switch: dark (the default) or light, remembered in the store. */
    fun setDarkTheme(on: Boolean) {
        store.darkTheme = on
        C.dark = on
        applySystemBars()
        for (s in game.state.sprites) s.invalidate()
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        (context as? Activity)?.window?.let { it.statusBarColor = C.BG; it.navigationBarColor = C.BG }
        systemUiVisibility = if (C.dark) 0 else SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    /** Boxed art for popups: a hairline square with an icon in it. */
    private fun boxedArt(c: Canvas, r: RectF, icon: (Float, Float, Float) -> Unit) {
        val s = r.height()
        val box = RectF(r.centerX() - s / 2, r.top, r.centerX() + s / 2, r.bottom)
        ui.card(c, box, rad = ui.dp(18f))
        icon(box.centerX(), box.centerY(), s * .5f)
    }

    // ---------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------

    fun startGame(level: Int, salt: Int, resume: Boolean, mode: GameMode = GameMode.CLASSIC) {
        inGame = true; screenAge = 0f
        game.start(level, salt, resume, mode)
        invalidate()
    }

    fun openMenu() {
        game.saveProgress()
        for (o in overlays) o.close()
        inGame = false; screenAge = 0f
        invalidate()
    }

    fun afterWin(info: WinInfo, toMenu: Boolean) {
        val classic = info.mode == GameMode.CLASSIC
        when {
            toMenu -> { if (classic) ads.countLevel(); menu.select(MenuScreen.Tab.MAIN); openMenu() }
            info.mode == GameMode.DUEL -> startDuel()
            info.mode == GameMode.RUSH -> startRush()
            // between levels: an interstitial now and then, then on as before
            else -> ads.betweenLevels(info.mode, store.level, true) {
                if (info.salt != 0) { menu.select(MenuScreen.Tab.DAILY); openMenu() } else startGame(store.level, 0, false)
            }
        }
    }

    private fun push(o: Overlay) { overlays.add(o); invalidate() }

    fun showWin(info: WinInfo) = push(WinOverlay(this, info))

    /** Bottom sheet like the real game's out_of_lives popup. */
    fun showLose(percent: Int, freeChance: Boolean): Unit = push(Popup(this, "Out of lives",
        "You made 3 mistakes, but $percent% of the board is already clear. Keep going with one more heart.",
        "$percent% of the board", ui.dp(76f),
        { c, r, t ->
            boxedArt(c, r) { x, y, s ->
                val pulse = if (Motion.reduced) 1f else 1f + .05f * kotlin.math.sin(t * 5f)
                ui.heart(c, x, y + ui.dp(2f), s * pulse, C.RED, false)
            }
            ui.badge(c, r.centerX() + r.height() * .5f, r.top + ui.dp(4f), "+1")
        },
        listOf(
            PItem.Primary("Get more lives", if (freeChance) "Free" else "100 coins", freeChance || store.coins >= 100) {
                if (!freeChance) store.coins = store.coins - 100
                game.state.continueAfterLoss()
            },
            *(if (!freeChance && ads.rewardedReady) arrayOf(PItem.Secondary("Watch an ad for a heart") {
                ads.watchForReward({ game.state.continueAfterLoss() }) { if (game.state.lost) showLose(percent, freeChance) }
            }) else emptyArray()),
            PItem.Secondary("Restart") { game.restart() }
        ), dismissable = false, sheet = true))

    fun showSettings(fromGame: Boolean) {
        val items = mutableListOf<PItem>(
            PItem.Toggle("Sounds", { store.sound }) { store.sound = it },
            PItem.Toggle("Vibration", { store.vibration }) { store.vibration = it; if (it) haptics.tick(30) },
            PItem.Toggle("Grid guide", { store.gridGuide }) { store.gridGuide = it },
            PItem.Toggle("Dark theme", { store.darkTheme }) { setDarkTheme(it) },
            PItem.Secondary("How to play") { showHowToPlay() }
        )
        if (ads.provider.privacyOptionsRequired) items.add(PItem.Secondary("Ad privacy choices") { ads.provider.showPrivacyOptions() })
        if (fromGame) items.add(PItem.Secondary("Restart level") { game.restart() })
        items.add(PItem.Primary("Done") {})
        push(Popup(this, "Settings", null, null, 0f, null, items, showClose = true))
    }

    fun showMechanicTutorial(m: Mechanic) = push(Popup(this, m.title, m.description, m.keyword, ui.dp(116f),
        { c, r, t -> MechanicArt.draw(c, r, t, m, ui) }, listOf(PItem.Primary("Got it") {})))

    fun showHowToPlay() = push(Popup(this, "How to play",
        "Tap an arrow to slide it off the board. It can only leave if nothing blocks its way. Clear every arrow to win; three mistakes and the level is over.",
        "Tap an arrow", ui.dp(110f), { c, r, t -> howToArt(c, r, t) }, listOf(PItem.Primary("Got it") {})))

    fun showGetBooster(b: Booster) {
        val cost = b.cost * 3
        val title = when (b) { Booster.HINT -> "Get a hint"; Booster.ERASER -> "Get an eraser"; Booster.WAND -> "Get a magic wand"; Booster.GRID -> "Grid guide" }
        push(Popup(this, title, "Get 3 for $cost coins. You have ${store.coins} coins.", "3 for $cost coins", ui.dp(76f),
            { c, r, _ ->
                boxedArt(c, r) { x, y, s ->
                    when (b) {
                        Booster.HINT -> ui.iconBulb(c, x, y, s, C.FG)
                        Booster.ERASER -> ui.iconEraser(c, x, y, s, C.FG)
                        Booster.WAND -> ui.iconWand(c, x, y, s, C.FG)
                        Booster.GRID -> ui.iconGrid(c, x, y, s, C.FG)
                    }
                }
            },
            listOf(
                PItem.Primary("Get 3", "$cost coins", store.coins >= cost) {
                    store.coins = store.coins - cost
                    store.setCount(b, store.count(b) + 3)
                    sfx.play(Sound.COIN)
                },
                *(if (ads.rewardedReady) arrayOf(PItem.Secondary("Watch an ad for 1 free") {
                    ads.watchForReward({
                        store.setCount(b, store.count(b) + 1)
                        sfx.play(Sound.COIN)
                    })
                }) else emptyArray()),
                PItem.TextBtn("Cancel") {}
            )))
    }

    fun showStatistics() = push(Popup(this, "Statistics", null, null, 0f, null, listOf(
        PItem.Progress(1f, "Levels completed: ${store.levelsCompleted}"),
        PItem.Progress(if (store.levelsCompleted == 0) 0f else store.perfectLevels / store.levelsCompleted.toFloat(), "Perfect levels: ${store.perfectLevels}"),
        PItem.Progress(1f, "Arrows cleared: ${store.arrowsCleared}"),
        PItem.Progress(if (store.bestStreak == 0) 0f else store.winStreak / store.bestStreak.toFloat(), "Win streak: ${store.winStreak} (best ${store.bestStreak})"),
        PItem.Progress(1f, "Hints used: ${store.hintsUsed}"),
        PItem.Progress(1f, "Time rush best: ${store.rushBest} points"),
        PItem.Progress(1f, "Duels played: ${store.duelsPlayed}"),
        PItem.Primary("Close") {}
    ), showClose = true))

    fun showAwards() {
        val now = java.time.YearMonth.now()
        var months = 0
        var stars = 0
        for (i in 0 until 12) {
            val ym = now.minusMonths(i.toLong())
            val n = store.dailyCount(ym.year * 100 + ym.monthValue)
            stars += n
            if (n >= ym.lengthOfMonth()) months++
        }
        push(Popup(this, "Awards", "Complete every daily challenge in a month to win its trophy.", "its trophy", ui.dp(76f),
            { c, r, _ -> boxedArt(c, r) { x, y, s -> ui.cup(c, x, y, s * 1.3f, if (months > 0) C.HIGHLIGHT else C.FG) } },
            listOf(
                PItem.Progress(minOf(1f, months / 12f), "Monthly trophies: $months"),
                PItem.Progress(minOf(1f, stars / 365f), "Daily stars this year: $stars"),
                PItem.Primary("Close") {}
            ), showClose = true))
    }

    fun showAbout() = push(Popup(this, "Path Puzzle",
        "A from-scratch rebuild in Kotlin and C++. Clear the board, take on the daily challenge, race the clock in Time rush or duel a friend on one phone.",
        "Time rush", 0f, null, listOf(PItem.Primary("Close") {}), showClose = true))

    /** A pass-and-play duel on a fresh board. */
    fun startDuel() {
        val round = store.duelRounds
        store.duelRounds = round + 1
        startGame(Levels.DUEL_LEVEL, Levels.modeSalt(GameMode.DUEL, round), false, GameMode.DUEL)
    }

    /** A new Time rush run. */
    fun startRush() {
        val run = store.rushRuns
        store.rushRuns = run + 1
        inGame = true; screenAge = 0f
        game.startRush(run)
        invalidate()
    }

    /** First time in a mode: its rules. */
    fun showModeTutorial(mode: GameMode) {
        val (title, body, keyword) = when (mode) {
            GameMode.DUEL -> Triple("Duel",
                "Two players, one phone. Take turns clearing one arrow each; every arrow is a point. A blocked arrow or a turn longer than 8 seconds costs a heart. Most points when the board is clear wins.",
                "every arrow is a point")
            else -> Triple("Time rush",
                "Clear as many arrows as you can in 60 seconds. Quick clears build a combo that multiplies your points, a clear board adds 10 seconds, and a blocked arrow costs 3.",
                "a combo")
        }
        push(Popup(this, title, body, keyword, ui.dp(76f),
            { c, r, _ ->
                boxedArt(c, r) { x, y, s -> if (mode == GameMode.DUEL) ui.iconDuel(c, x, y, s, C.FG) else ui.iconStopwatch(c, x, y, s, C.FG) }
            },
            listOf(PItem.Primary("Got it") {})))
    }

    private fun howToArt(c: Canvas, r: RectF, t: Float) {
        val cs = minOf(r.width() / 5f, r.height() / 3f) * .85f
        val area = RectF(r.centerX() - cs * 2.7f, r.centerY() - cs * 1.6f, r.centerX() + cs * 2.7f, r.centerY() + cs * 1.6f)
        c.save(); c.clipRect(area)
        c.translate(r.centerX() - cs * 2f, r.centerY() - cs); c.scale(cs, cs)
        ui.fillPaint(C.DOT)
        for (y in 0..2) for (x in 0..4) c.drawCircle(x.toFloat(), y.toFloat(), .07f, ui.fill)
        val k = Ease.clamp01(((t % 2.2f) - .6f) / .6f)
        val p = android.graphics.Path()
        val xs = floatArrayOf(0f, 0f, 2f + k * 4f); val ys = floatArrayOf(2f, 1f, 1f)
        val xs2 = floatArrayOf(4f, 4f); val ys2 = floatArrayOf(0f, 2f)
        fun arrow(px: FloatArray, py: FloatArray, n: Int, dx: Float, dy: Float, col: Int) {
            p.reset(); BoardRenderer.polyline(p, px, py, null, n, BoardRenderer.CORNER)
            val s = ui.strokePaint(col, BoardRenderer.LW)
            c.drawPath(p, s)
            p.reset(); BoardRenderer.headTriangle(p, px[n - 1], py[n - 1], dx, dy); c.drawPath(p, ui.fillPaint(col))
        }
        arrow(xs2, ys2, 2, 0f, 1f, C.ARROW)
        if (k < 1f) arrow(xs, ys, 3, 1f, 0f, if (k > 0f) C.ACCENT else C.ARROW)
        c.restore()
    }

    fun handleBack(): Boolean {
        if (overlays.isNotEmpty()) { overlays.last().onBack(); invalidate(); return true }
        if (inGame) { game.onBack(); return true }
        if (menu.tab != MenuScreen.Tab.MAIN) { menu.select(MenuScreen.Tab.MAIN); invalidate(); return true }
        return false
    }

    fun onPauseSave() = game.saveProgress()

    // ---------------------------------------------------------------------------------
    // Frame
    // ---------------------------------------------------------------------------------

    private fun relayout() {
        if (width == 0) return
        game.layout(width.toFloat(), height.toFloat())
        menu.layout(width.toFloat(), height.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = relayout()

    private var firstFrameLogged = false

    override fun onDraw(c: Canvas) {
        if (!firstFrameLogged) {
            firstFrameLogged = true
            android.util.Log.i("PathPuzzle", "start-up: first frame at process age ${android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()} ms")
        }
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1e9f).coerceIn(0f, .05f)
        lastFrame = now
        val w = width.toFloat(); val h = height.toFloat()
        ui.hits.clear()

        c.drawColor(C.BG)
        // the hexagon glow belongs to pages; in a level the board is the only thing on the page
        if (!inGame) ui.backdrop(c, width, height)

        screenAge += dt
        val fade = Ease.clamp01(screenAge / .25f)
        if (fade < 1f) c.saveLayerAlpha(0f, 0f, w, h, (255 * fade).toInt())
        if (inGame) game.draw(c, dt) else menu.draw(c, dt)
        if (fade < 1f) c.restore()

        overlayHitStart = ui.hits.size
        for (o in overlays.toList()) o.draw(c, w, h, dt)
        val done = overlays.filter { it.finished }
        if (done.isNotEmpty()) {
            overlays.removeAll(done.toSet())
            // Drop the closed popup's buttons now; otherwise the next tap lands on them and is lost.
            ui.hits.subList(overlayHitStart, ui.hits.size).clear()
            for (o in done) o.onClosed?.invoke()
            invalidate()
        }

        val busy = screenAge < .3f || (inGame && game.needsFrame) || (!inGame && menu.needsFrame) || overlays.any { it.needsFrame }
        if (busy) postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------------------------
    // Touch
    // ---------------------------------------------------------------------------------

    private fun hitAt(x: Float, y: Float): Hit? {
        val floor = if (overlays.isNotEmpty()) overlayHitStart else 0
        for (i in ui.hits.indices.reversed()) {
            if (i < floor) break
            if (ui.hits[i].r.contains(x, y)) return ui.hits[i]
        }
        return null
    }

    private val boardTouch get() = inGame && overlays.isEmpty()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; moved = false; multi = false
                ui.pressedId = hitAt(e.x, e.y)?.id
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> { multi = true; moved = true; ui.pressedId = null }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(idx); lastY = e.getY(idx)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scaler.isInProgress) {
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) { moved = true; ui.pressedId = null }
                    if (moved && boardTouch) { game.onDrag(e.x - lastX, e.y - lastY); invalidate() }
                    else if (moved && !inGame && overlays.isEmpty()) { menu.onDrag(e.y - lastY); invalidate() }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && !multi) {
                    val hit = hitAt(e.x, e.y)
                    if (hit != null && hit.id == ui.pressedId) { sfx.play(Sound.TAP); hit.action() }
                    else if (hit == null && boardTouch) game.onTap(e.x, e.y)
                } else if (moved && !multi && boardTouch) {
                    tracker?.computeCurrentVelocity(1000)
                    game.onFling(tracker?.xVelocity ?: 0f, tracker?.yVelocity ?: 0f)
                }
                ui.pressedId = null
                tracker?.recycle(); tracker = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { ui.pressedId = null; tracker?.recycle(); tracker = null; invalidate() }
        }
        return true
    }
}
