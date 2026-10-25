package com.arrowpuzzle.game

import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertTrue
import java.time.LocalDate

/**
 * End-to-end UI tests: the real [MainActivity] on the device, driven with injected touches and
 * the back key, from the menu through levels, popups, boosters, winning, losing and resuming.
 * The game runs on a test-only preferences file, so the player's save is never touched.
 */
@Suppress("DEPRECATION")
class UiFlowTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private var act: MainActivity? = null
    private val ctx get() = instrumentation.targetContext
    private val store get() = Store(ctx)

    private var playerSave: Map<String, *> = emptyMap<String, Any>()
    private fun readPlayerSave() = ctx.getSharedPreferences("arrowpuzzle.v2", android.content.Context.MODE_PRIVATE).all.toMap()

    override fun setUp() {
        super.setUp()
        playerSave = readPlayerSave()
        UiPrefs.isolate(ctx)
        AdsConfig.provider = { NoAds }   // never real ads in a test; the ad tests install a FakeAds
    }

    override fun tearDown() {
        closeActivity()
        AdsConfig.reset()
        UiPrefs.release(ctx)
        C.dark = true
        try {
            // guard: the player's own save is exactly as it was before the test
            assertEquals("the player's save changed during a UI test", playerSave, readPlayerSave())
        } finally {
            super.tearDown()
        }
    }

    // ---- driver ---------------------------------------------------------------------

    private fun <T> main(block: () -> T): T = instrumentation.onMain(block)

    private fun launch(setup: Store.() -> Unit = {}): GameView {
        UiPrefs.reset(ctx, setup)
        return open()
    }

    private fun open(): GameView {
        // the earlier suites take minutes and the screen may have gone off: wake it for each test
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        val a = activity
        act = a
        main {
            a.setShowWhenLocked(true)
            a.setTurnScreenOn(true)
            a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val v = main { a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as GameView }
        waitUntil("window focus") { main { a.hasWindowFocus() } }
        waitUntil("first frame") { main { v.ui.hits.isNotEmpty() } }
        return v
    }

    private fun closeActivity() {
        val a = act ?: return
        main { if (!a.isFinishing) a.finish() }
        waitUntil("activity destroyed") { main { a.isDestroyed } }
        act = null
        setActivity(null)
    }

    private fun relaunch(): GameView { closeActivity(); return open() }

    private fun overlays(v: GameView) = main { v.field<ArrayList<Overlay>>("overlays").size }

    private fun waitForHit(v: GameView, id: String, timeoutMs: Long = 8_000): RectF {
        var r: RectF? = null
        waitUntil("button $id", timeoutMs) { r = main { v.ui.hits.lastOrNull { it.id == id }?.r?.let(::RectF) }; r != null }
        return r!!
    }

    private fun tapAt(v: GameView, x: Float, y: Float) {
        val loc = IntArray(2)
        main { v.getLocationOnScreen(loc) }
        val t = SystemClock.uptimeMillis()
        val sx = loc[0] + x; val sy = loc[1] + y
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, sx, sy, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, sx, sy, 0)
        instrumentation.sendPointerSync(down)
        SystemClock.sleep(60)
        instrumentation.sendPointerSync(up)
        down.recycle(); up.recycle()
    }

    private fun tapHit(v: GameView, id: String) {
        waitForHit(v, id)
        // popups scale or slide in; read the button's place once they have settled
        waitUntil("popup animation") { main { v.field<ArrayList<Overlay>>("overlays").all { it.age() > .45f } } }
        SystemClock.sleep(50)
        val r = waitForHit(v, id)
        tapAt(v, r.centerX(), r.centerY())
    }

    private fun back() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

    private fun waitPlayable(v: GameView) {
        waitUntil("level loaded") { main { v.inGame && !v.game.loading && v.game.state.board != null } }
        waitUntil("level intro") {
            main { v.game.field<Float>("introT") >= v.game.call<Float>("getIntroZoomEnd") + .4f }
        }
    }

    /** Centre of one of the arrow's cells that is inside the visible board area, or null. */
    private fun arrowPoint(v: GameView, id: Int): PointF? = main {
        val g = v.game
        val k = g.field<Float>("k0") * g.field<Float>("z")
        val ox = g.field<Float>("ox"); val oy = g.field<Float>("oy")
        val top = g.field<Float>("boardTop"); val bottom = g.field<Float>("boardBottom")
        g.state.sprites[id].info.pts
            .map { PointF(ox + (it.x + .5f) * k, oy + (it.y + .5f) * k) }
            .firstOrNull { it.y in top..bottom && it.x in 1f..(v.width - 1f) }
    }

    private fun tapArrow(v: GameView, id: Int) {
        val p = arrowPoint(v, id) ?: throw AssertionError("arrow $id is not on screen")
        tapAt(v, p.x, p.y)
    }

    private fun remaining(v: GameView) = main { v.game.state.board!!.remaining() }

    /** Taps the arrow a hint suggests and waits for the board to register it. */
    private fun playHintMove(v: GameView) {
        val before = remaining(v)
        val id = main { v.game.state.board!!.hint()!!.first }
        tapArrow(v, id)
        waitUntil("arrow $id to leave") { remaining(v) == before - 1 }
    }

    // ---- tests ------------------------------------------------------------------------

    fun testMenuTabsAndEveryMeScreenPopup() {
        val v = launch()
        tapHit(v, "tab_1")
        waitUntil("Daily tab") { main { v.menu.tab == MenuScreen.Tab.DAILY } }
        waitForHit(v, "d_play")
        tapHit(v, "tab_2")
        waitUntil("Me tab") { main { v.menu.tab == MenuScreen.Tab.ME } }
        for (id in listOf("me_awards", "me_stats", "me_0", "me_1", "me_2")) {
            tapHit(v, id)
            waitUntil("$id popup") { overlays(v) == 1 }
            back()
            waitUntil("$id popup to close") { overlays(v) == 0 }
        }
        back()
        waitUntil("back to Main tab") { main { v.menu.tab == MenuScreen.Tab.MAIN } }
        back()
        waitUntil("app closes from the main menu") { main { act!!.isFinishing } }
    }

    fun testSettingsTogglesChangeTheStore() {
        val v = launch()
        tapHit(v, "m_settings")
        waitUntil("settings popup") { overlays(v) == 1 }
        assertFalse(store.sound); assertFalse(store.gridGuide)
        tapHit(v, "p0")
        waitUntil("sound on") { store.sound }
        tapHit(v, "p2")
        waitUntil("grid guide on") { store.gridGuide }
        tapHit(v, "p0")
        waitUntil("sound off again") { !store.sound }
        tapHit(v, "p3")
        waitUntil("light theme") { !store.darkTheme && !C.dark }
        tapHit(v, "p3")
        waitUntil("dark theme again") { store.darkTheme && C.dark }
        tapHit(v, "p_close")
        waitUntil("settings closed") { overlays(v) == 0 }
        assertTrue(store.gridGuide)
    }

    /** Like the real game, a level opens with the whole board on screen, nothing to pan. */
    fun testLevelOpensWithTheWholeBoardVisible() {
        val v = launch { level = 30 }  // a large Expert board
        tapHit(v, "m_play")
        waitPlayable(v)
        val total = main { v.game.state.total }
        val offScreen = (0 until total).count { id -> arrowPoint(v, id) == null }
        assertEquals("arrows that start off screen", 0, offScreen)
        main {
            val g = v.game
            val k = g.field<Float>("k0") * g.field<Float>("z")
            val b = g.state.board!!
            val ox = g.field<Float>("ox"); val oy = g.field<Float>("oy")
            assertTrue("left edge on screen", ox >= 0f)
            assertTrue("right edge on screen", ox + b.cols * k <= v.width)
            assertTrue("top inside the board area", oy >= g.field<Float>("boardTop") - 1f)
            assertTrue("bottom inside the board area", oy + b.rows * k <= g.field<Float>("boardBottom") + 1f)
            assertEquals("opens unzoomed", 1f, g.field<Float>("z"), .001f)
        }
    }

    fun testThemeSwitcherOnTheMenu() {
        val v = launch()
        assertTrue(C.dark)
        assertEquals(0xFF0A0A0A.toInt(), main { act!!.window.statusBarColor })
        tapHit(v, "m_theme")
        waitUntil("light theme") { !store.darkTheme && !C.dark }
        assertEquals("status bar follows the theme", 0xFFFFFFFF.toInt(), main { act!!.window.statusBarColor })
        // the choice survives a restart of the app
        val v2 = relaunch()
        assertFalse(C.dark)
        tapHit(v2, "m_theme")
        waitUntil("dark theme") { store.darkTheme && C.dark }
        assertEquals(0xFF0A0A0A.toInt(), main { act!!.window.statusBarColor })
    }

    fun testWinningALevelByTappingArrows() {
        val v = launch { level = 4; coins = 50 }
        tapHit(v, "m_play")
        waitPlayable(v)
        assertEquals(4, main { v.game.state.level })
        while (remaining(v) > 0) playHintMove(v)
        waitForHit(v, "w_next", 10_000)
        assertTrue(main { v.game.state.won })
        assertEquals(5, store.level)
        assertEquals("reward 10 + level/2 + 5 for a perfect level", 50 + 17, store.coins)
        assertEquals(1, store.levelsCompleted)
        assertEquals(1, store.perfectLevels)
        assertEquals(0, store.savedLevel)
        tapHit(v, "w_next")
        waitUntil("next level") { main { v.inGame && !v.game.loading && v.game.state.level == 5 } }
        back()
        waitUntil("menu") { main { !v.inGame } }
    }

    fun testThreeMistakesThenTheFreeSecondChance() {
        val v = launch { level = 4 }
        tapHit(v, "m_play")
        waitPlayable(v)
        val blocked = main { Geometry.blockedArrow(v.game.state.board!!) }
        for (n in 1..3) {
            tapArrow(v, blocked)
            waitUntil("mistake $n") { main { v.game.state.mistakes == n } }
        }
        assertTrue(main { v.game.state.lost })
        waitForHit(v, "p1")  // Out of Lives sheet: Get More Lives / Restart
        assertEquals(1, overlays(v))
        tapHit(v, "p0")
        waitUntil("second chance") { main { !v.game.state.lost && v.game.state.lives == 1 } }
        assertEquals(0, store.winStreak)
        playHintMove(v)
        back()
        waitUntil("menu") { main { !v.inGame } }
    }

    fun testRestartFromInGameSettings() {
        val v = launch { level = 4 }
        tapHit(v, "m_play")
        waitPlayable(v)
        playHintMove(v)
        val old = main { v.game.state.board }
        tapHit(v, "g_settings")
        tapHit(v, "p5")  // Sounds, Vibration, Grid guide, Dark theme, How to play, Restart level
        waitUntil("fresh board") { main { v.game.state.board.let { it != null && it !== old && it.remaining() == it.arrowCount } } }
        assertEquals("", main { v.game.state.moveLog.toString() })
    }

    fun testProgressIsSavedAndResumedAfterRelaunch() {
        var v = launch { level = 6 }
        tapHit(v, "m_play")
        waitPlayable(v)
        val total = main { v.game.state.total }
        playHintMove(v)
        playHintMove(v)
        back()
        waitUntil("menu") { main { !v.inGame } }
        assertEquals(6, store.savedLevel)
        assertEquals(2, store.savedMoves.count { it == 't' })

        v = relaunch()
        tapHit(v, "m_play")  // now "Continue Game"
        waitPlayable(v)
        assertEquals(total - 2, remaining(v))
        assertEquals(6, main { v.game.state.level })
    }

    fun testBoostersFromTheGameHud() {
        val v = launch {
            level = 30; coins = 500
            setCount(Booster.HINT, 1); setCount(Booster.ERASER, 1); setCount(Booster.WAND, 1)
        }
        tapHit(v, "m_play")
        waitPlayable(v)
        val total = main { v.game.state.total }

        tapHit(v, "boost_grid")
        waitUntil("grid guide") { store.gridGuide }

        tapHit(v, "boost_hint")
        waitUntil("hint shown") { main { v.game.state.hintId >= 0 } }
        assertEquals(0, store.count(Booster.HINT))

        tapHit(v, "boost_wand")
        waitUntil("wand move") { remaining(v) == total - 1 }
        assertEquals(0, store.count(Booster.WAND))

        tapHit(v, "boost_eraser")
        waitUntil("eraser armed") { main { v.game.state.eraserArmed } }
        val target = (0 until total).first { id ->
            main { v.game.state.sprites[id].state == SpriteState.IDLE } && arrowPoint(v, id) != null
        }
        tapArrow(v, target)
        waitUntil("erased") { remaining(v) == total - 2 }
        assertFalse(main { v.game.state.board!!.arrow(target).alive })
        assertEquals(0, store.count(Booster.ERASER))
        assertEquals(0, main { v.game.state.mistakes })

        // out of erasers: the shop popup sells three for 90 coins
        tapHit(v, "boost_eraser")
        waitUntil("shop popup") { overlays(v) == 1 }
        tapHit(v, "p0")
        waitUntil("bought erasers") { store.count(Booster.ERASER) == 3 }
        assertEquals(410, store.coins)
    }

    fun testDailyCardOpensTodaysBoard() {
        val v = launch()
        val today = LocalDate.now()
        val key = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        tapHit(v, "m_daily")
        waitUntil("daily board", 15_000) { main { v.inGame && !v.game.loading } }
        main {
            assertTrue(v.game.state.daily)
            assertEquals(key, v.game.state.salt)
            assertEquals(Levels.dailyLevel(key), v.game.state.level)
        }
        back()
        waitUntil("menu") { main { !v.inGame } }
    }

    /** Duel end to end: rules, the clock paused under a popup, turns passing, the result, a rematch. */
    fun testDuelFromTheMenu() {
        val v = launch { level = 6; savedLevel = 6; savedSalt = 0; savedMode = 0; savedMoves = "t1;" }
        tapHit(v, "m_duel")
        waitUntil("duel rules") { overlays(v) == 1 }
        tapHit(v, "p0")  // Got it
        waitUntil("rules closed") { overlays(v) == 0 }
        waitPlayable(v)
        val d = main { v.game.state.duel!! }
        main {
            assertEquals(GameMode.DUEL, v.game.state.mode)
            assertEquals(Levels.DUEL_LEVEL, v.game.state.level)
            assertEquals(Levels.modeSalt(GameMode.DUEL, 0), v.game.state.salt)
            assertFalse("no boosters in a duel", v.ui.hits.any { it.id.startsWith("boost_") })
        }
        // the turn clock stops while the settings popup is open
        tapHit(v, "g_settings")
        waitUntil("settings") { overlays(v) == 1 }
        val paused = main { d.timeLeft }
        SystemClock.sleep(1200)
        assertEquals(paused, main { d.timeLeft }, .001f)
        back()
        waitUntil("settings closed") { overlays(v) == 0 }
        // the players take turns until the board is empty
        val total = main { v.game.state.total }
        while (main { !d.finished }) {
            val who = main { d.current }
            playHintMove(v)
            assertTrue("the turn passed", main { d.finished || d.current != who })
        }
        assertEquals(total, d.scores[0] + d.scores[1])
        waitForHit(v, "w_next", 10_000)
        assertEquals(1, store.duelsPlayed)
        assertEquals(if (total % 2 == 1) 0 else DuelGame.DRAW, d.winner)
        // a duel never touches the main game's saved level
        assertEquals(6, store.savedLevel)
        assertEquals("t1;", store.savedMoves)
        assertEquals(GameMode.CLASSIC.key, store.savedMode)
        tapHit(v, "w_next")  // Play again
        waitUntil("rematch on a fresh board") {
            main { v.inGame && !v.game.loading && v.game.state.mode == GameMode.DUEL && v.game.state.salt == Levels.modeSalt(GameMode.DUEL, 1) }
        }
        assertEquals("rules shown once", 0, overlays(v))
        back()
        waitUntil("menu") { main { !v.inGame } }
        assertEquals("t1;", store.savedMoves)
    }

    /** Time rush end to end: rules, a mistake costing time, the next board, the clock running out, the best score. */
    fun testTimeRushFromTheMenu() {
        val v = launch { coins = 50 }
        tapHit(v, "m_rush")
        waitUntil("rush rules") { overlays(v) == 1 }
        tapHit(v, "p0")
        waitUntil("rules closed") { overlays(v) == 0 }
        waitPlayable(v)
        val r = main { v.game.state.rush!! }
        main {
            assertEquals(GameMode.RUSH, v.game.state.mode)
            assertEquals(Levels.rushBoardLevel(0), v.game.state.level)
            assertEquals(Levels.rushSalt(0, 0), v.game.state.salt)
            assertFalse("no boosters in a rush", v.ui.hits.any { it.id.startsWith("boost_") })
        }
        assertEquals(1, store.rushRuns)
        // a blocked arrow costs three seconds
        val blocked = main { Geometry.blockedArrow(v.game.state.board!!) }
        val before = main { r.timeLeft }
        tapArrow(v, blocked)
        waitUntil("mistake") { main { r.mistakes == 1 } }
        assertTrue("time after the mistake: ${r.timeLeft}", main { r.timeLeft } <= before - RushGame.MISTAKE_SECONDS + .01f)
        // clear the first board; the second follows within the same run
        val first = Levels.rushSalt(0, 0)
        while (main { v.game.state.salt == first && v.game.state.board!!.remaining() > 0 }) playHintMove(v)
        waitUntil("second board") { main { !v.game.loading && v.game.state.salt == Levels.rushSalt(0, 1) } }
        main {
            assertEquals(1, r.boards)
            assertSame(r, v.game.state.rush)
            assertEquals(Levels.rushBoardLevel(1), v.game.state.level)
        }
        waitPlayable(v)
        playHintMove(v)
        // let the clock run out
        main { v.game.state.update(1000f) }
        waitForHit(v, "w_next", 10_000)
        assertTrue(r.finished)
        assertTrue(r.score > 0)
        assertEquals(r.score, store.rushBest)
        assertEquals(50 + minOf(50, r.score / 20), store.coins)
        tapHit(v, "w_main")
        waitUntil("menu") { main { !v.inGame } }
        assertEquals("a rush is not saved", 0, store.savedLevel)
    }

    /** A rewarded ad after the free chance is used up gives the heart back; skipping it does not. */
    fun testWatchingAnAdBringsBackAHeart() {
        val fake = FakeAds(rewarded = false, watchThrough = false, reload = true)
        AdsConfig.provider = { fake }
        val v = launch { level = 4 }
        tapHit(v, "m_play")
        waitPlayable(v)
        val blocked = main { Geometry.blockedArrow(v.game.state.board!!) }
        for (n in 1..3) { tapArrow(v, blocked); waitUntil("mistake $n") { main { v.game.state.mistakes == n } } }
        waitForHit(v, "p1")
        tapHit(v, "p0")  // the free second chance
        waitUntil("second chance") { main { !v.game.state.lost && v.game.state.lives == 1 } }
        // the ad is ready by the time the player runs out again
        fake.rewarded = true
        tapArrow(v, blocked)
        waitUntil("lost again") { main { v.game.state.lost } }
        waitForHit(v, "p2")  // Get more lives, Watch an ad for a heart, Restart
        // closed before the end: no heart, and the sheet comes back
        tapHit(v, "p1")
        waitUntil("ad shown") { fake.rewardedShown == 1 }
        waitUntil("sheet again") { main { v.game.state.lost } && overlays(v) == 1 }
        assertEquals(0, main { v.game.state.lives })
        // watched through: a heart, and play goes on
        fake.watchThrough = true
        waitForHit(v, "p2")
        tapHit(v, "p1")
        waitUntil("heart back") { main { !v.game.state.lost && v.game.state.lives == 1 } }
        assertEquals(2, fake.rewardedShown)
    }

    /** Between levels an interstitial may show, and the next level starts after it closes. */
    fun testAnInterstitialShowsBetweenLevelsThenPlayGoesOn() {
        val fake = FakeAds()
        AdsConfig.provider = { fake }
        AdsConfig.policy = { AdPolicy(firstLevel = 1, everyLevels = 1, minGapMs = 0) }
        val v = launch { level = 4; coins = 50 }
        tapHit(v, "m_play")
        waitPlayable(v)
        while (remaining(v) > 0) playHintMove(v)
        waitForHit(v, "w_next", 10_000)
        assertEquals("no ad while the board was being played", 0, fake.interstitialShown)
        tapHit(v, "w_next")
        waitUntil("next level after the ad") { main { v.inGame && !v.game.loading && v.game.state.level == 5 } }
        assertEquals(1, fake.interstitialShown)
        assertEquals(5, store.level)
    }

    /** The booster shop sells boosters for coins or for a watched ad. */
    fun testWatchingAnAdGivesABooster() {
        val fake = FakeAds()
        AdsConfig.provider = { fake }
        val v = launch { level = 4; coins = 500; setCount(Booster.HINT, 0) }
        tapHit(v, "m_play")
        waitPlayable(v)
        tapHit(v, "boost_hint")
        waitUntil("shop") { overlays(v) == 1 }
        tapHit(v, "p1")  // Get 3, Watch an ad for 1 free, Cancel
        waitUntil("hint from the ad") { store.count(Booster.HINT) == 1 }
        assertEquals("an ad costs no coins", 500, store.coins)
        assertEquals(1, fake.rewardedShown)
    }
}
