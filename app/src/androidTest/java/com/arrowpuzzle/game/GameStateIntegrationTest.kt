package com.arrowpuzzle.game

import android.test.InstrumentationTestCase
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue

/**
 * Integration tests of [GameState] on real native boards: hearts, win/lose, boosters, the
 * second chance, animations, listener callbacks and the saved move log. Everything runs on
 * the main thread, as in the game.
 */
@Suppress("DEPRECATION")
class GameStateIntegrationTest : InstrumentationTestCase() {
    private lateinit var context: TestPrefsContext
    private lateinit var store: Store
    private val games = ArrayList<GameState>()

    private class Recorder : GameState.Listener {
        val lives = ArrayList<Int>()
        var wins = 0
        var loses = 0
        val toasts = ArrayList<String>()
        override fun onLifeLost(index: Int) { lives.add(index) }
        override fun onWin() { wins++ }
        override fun onLose() { loses++ }
        override fun onPraise(text: String) {}
        override fun onToast(text: String) { toasts.add(text) }
        override fun onSaved() {}
    }

    override fun setUp() {
        super.setUp()
        context = TestPrefsContext(instrumentation.targetContext)
        context.wipe()
        store = Store(context)
        store.sound = false
        store.vibration = false
    }

    override fun tearDown() {
        main { for (g in games) g.close() }
        context.wipe()
        super.tearDown()
    }

    private fun <T> main(block: () -> T): T = instrumentation.onMain(block)

    private fun game(level: Int, replay: String = "", salt: Int = 0): GameState = main {
        val gs = GameState(store, Sfx(store), Haptics(context, store))
        gs.attach(Board(level, salt), replay)
        games.add(gs)
        gs
    }

    /** Advances animations by [seconds] in 50 ms frames. */
    private fun tick(gs: GameState, seconds: Float) = main { repeat((seconds / .05f).toInt()) { gs.update(.05f) } }

    private fun playHintMove(gs: GameState) = main {
        val h = gs.board!!.hint()!!
        gs.tap(h.first, h.second)
    }

    fun testClearingEveryArrowWinsWithoutLosingHearts() {
        val gs = game(9)
        val rec = Recorder()
        main { gs.listener = rec }
        val total = main { gs.total }
        repeat(total) { playHintMove(gs) }
        main {
            assertTrue(gs.won)
            assertFalse(gs.lost)
            assertEquals(3, gs.lives)
            assertEquals(0, gs.mistakes)
            assertEquals(100, gs.percentCleared())
            assertEquals(0, gs.board!!.remaining())
        }
        assertEquals(total, store.arrowsCleared)
        tick(gs, 4f)
        main {
            assertEquals("onWin fires once the last arrow has flown out", 1, rec.wins)
            assertTrue(gs.sprites.all { it.state == SpriteState.GONE })
            assertFalse(gs.busy)
        }
    }

    fun testThreeBlockedTapsLoseTheLevel() {
        val gs = game(12)
        val rec = Recorder()
        main { gs.listener = rec }
        val blocked = main { Geometry.blockedArrow(gs.board!!) }
        repeat(3) { main { gs.tap(blocked, false) } }
        main {
            assertTrue(gs.lost)
            assertEquals(0, gs.lives)
            assertEquals(3, gs.mistakes)
            assertEquals(gs.total, gs.board!!.remaining())
            assertTrue(gs.sprites[blocked].red)
            assertEquals(listOf(2, 1, 0), rec.lives)
            gs.tap(Geometry.freeAtStart(gs.board!!).first(), false)  // no moves once the level is lost
            assertEquals(gs.total, gs.board!!.remaining())
        }
        assertEquals("onLose waits for the mistake effect", 0, rec.loses)
        tick(gs, 1.2f)
        assertEquals(1, rec.loses)
    }

    /** Regression: the win and the out-of-lives sheet need frames; the state must ask for them. */
    fun testWinAndLossKeepFramesComingUntilAnnounced() {
        val win = game(4)
        val rec = Recorder()
        main { win.listener = rec }
        repeat(main { win.total }) { playHintMove(win) }
        // frame by frame until announced: every frame before that must still ask for the next one
        var frames = 0
        while (main { rec.wins } == 0 && frames++ < 200) main {
            assertTrue("frame $frames: win pending but no frame requested", win.awaitingResult)
            win.update(.05f)
        }
        main { assertEquals(1, rec.wins); assertFalse(win.awaitingResult); assertFalse(win.busy) }

        val lose = game(12)
        val rec2 = Recorder()
        main { lose.listener = rec2 }
        val blocked = main { Geometry.blockedArrow(lose.board!!) }
        repeat(3) { main { lose.tap(blocked, false) } }
        frames = 0
        while (main { rec2.loses } == 0 && frames++ < 200) main {
            assertTrue("frame $frames: loss pending but no frame requested", lose.awaitingResult)
            lose.update(.05f)
        }
        main { assertEquals(1, rec2.loses); assertFalse(lose.awaitingResult) }
    }

    fun testSecondChanceGivesOneMoreHeart() {
        val gs = game(12)
        val blocked = main { Geometry.blockedArrow(gs.board!!) }
        repeat(3) { main { gs.tap(blocked, false) } }
        main {
            gs.continueAfterLoss()
            assertFalse(gs.lost)
            assertEquals(1, gs.lives)
            assertTrue(gs.secondChanceUsed)
        }
        playHintMove(gs)
        main {
            assertEquals(gs.total - 1, gs.board!!.remaining())
            gs.tap(Geometry.blockedArrow(gs.board!!), false)
            assertTrue(gs.lost)
        }
    }

    fun testHintBoosterMarksAMoveAndCostsOne() {
        store.setCount(Booster.HINT, 2)
        val gs = game(9)
        main { gs.useHint() }
        waitUntil("hint") { main { gs.hintId >= 0 } }
        assertEquals(1, store.count(Booster.HINT))
        assertEquals(1, store.hintsUsed)
        main {
            val id = gs.hintId
            gs.tap(id, gs.hintRev)
            assertEquals("the hinted arrow leaves", gs.total - 1, gs.board!!.remaining())
            assertFalse(gs.board!!.arrow(id).alive)
            assertEquals(-1, gs.hintId)
            assertEquals(0, gs.mistakes)
        }
    }

    fun testMagicWandPlaysAMoveByItself() {
        store.setCount(Booster.WAND, 1)
        val gs = game(9)
        main { gs.useWand() }
        waitUntil("wand move") {
            main { gs.update(.05f); gs.board!!.remaining() == gs.total - 1 }
        }
        assertEquals(0, store.count(Booster.WAND))
        main { assertEquals(0, gs.mistakes) }
    }

    fun testEraserRemovesEvenABlockedArrow() {
        store.setCount(Booster.ERASER, 2)
        val gs = game(12)
        main {
            val blocked = Geometry.blockedArrow(gs.board!!)
            gs.eraserArmed = true
            gs.tap(blocked, false)
            assertFalse(gs.eraserArmed)
            assertFalse(gs.board!!.arrow(blocked).alive)
            assertEquals(SpriteState.ERASING, gs.sprites[blocked].state)
            assertEquals(0, gs.mistakes)
            assertEquals(gs.total - 1, gs.remaining)
        }
        assertEquals(1, store.count(Booster.ERASER))
        tick(gs, 1f)
        main { assertEquals(SpriteState.GONE, gs.sprites.first { !gs.board!!.arrow(it.id).alive }.state) }
    }

    fun testExitingArrowAnimatesOffTheBoard() {
        val gs = game(6)
        val id = main { Geometry.freeAtStart(gs.board!!).first() }
        main {
            gs.tap(id, false)
            val s = gs.sprites[id]
            assertEquals(SpriteState.MOVING, s.state)
            assertTrue(s.exiting)
            assertTrue(gs.busy)
        }
        tick(gs, 3f)
        main { assertEquals(SpriteState.GONE, gs.sprites[id].state) }
    }

    fun testSavedMoveLogRestoresTheGame() {
        store.setCount(Booster.ERASER, 1)
        val level = 15
        val gs = game(level)
        main {
            gs.tap(Geometry.blockedArrow(gs.board!!), false)
            gs.eraserArmed = true
            gs.tap(Geometry.blockedArrow(gs.board!!), false)
        }
        repeat(main { gs.total } / 2) { playHintMove(gs) }
        val log = main { gs.moveLog.toString() }
        assertTrue(log.contains("m;") && log.contains("e"))

        val restored = game(level, log)
        main {
            val b = gs.board!!; val r = restored.board!!
            assertEquals(b.remaining(), r.remaining())
            assertEquals(gs.lives, restored.lives)
            assertEquals(gs.mistakes, restored.mistakes)
            for (id in 0 until b.arrowCount) {
                assertEquals("arrow $id", b.arrow(id).alive, r.arrow(id).alive)
                assertEquals("red flag of arrow $id", gs.sprites[id].red, restored.sprites[id].red)
            }
            assertEquals(log, restored.moveLog.toString())
        }
        // and the restored game can still be finished
        main { assertTrue(Geometry.playByHints(restored.board!!) >= 0) }
    }

    fun testDailyAndModeFlags() {
        val daily = game(Levels.dailyLevel(20261024), salt = 20261024)
        val duel = main {
            val gs = GameState(store, Sfx(store), Haptics(context, store))
            gs.attach(Board(Levels.DUEL_LEVEL, Levels.modeSalt(GameMode.DUEL)), "", GameMode.DUEL)
            games.add(gs); gs
        }
        main {
            assertTrue(daily.daily); assertEquals(GameMode.CLASSIC, daily.mode)
            assertEquals(daily.total, daily.remaining)
            assertNull(daily.duel); assertNull(daily.rush)
            assertFalse("a mode board is not a daily", duel.daily)
            assertEquals(GameMode.DUEL, duel.mode)
            assertNotNull(duel.duel); assertNull(duel.rush)
        }
    }
}
