package com.arrowpuzzle.game

import android.test.InstrumentationTestCase
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertTrue

/**
 * Time rush on real native boards: the board sequence, and GameState running a run (points,
 * time lost to mistakes, the run carried from board to board, the clock running out).
 */
@Suppress("DEPRECATION")
class RushIntegrationTest : InstrumentationTestCase() {
    private lateinit var context: TestPrefsContext
    private lateinit var store: Store
    private val games = ArrayList<GameState>()

    private class Recorder : GameState.Listener {
        var wins = 0
        val toasts = ArrayList<String>()
        override fun onLifeLost(index: Int) {}
        override fun onWin() { wins++ }
        override fun onLose() {}
        override fun onPraise(text: String) {}
        override fun onToast(text: String) { toasts.add(text) }
        override fun onSaved() {}
    }

    private val rec = Recorder()

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

    private fun rushBoard(run: Int, index: Int) = Board(Levels.rushBoardLevel(index), Levels.rushSalt(run, index))

    private fun rush(run: Int, index: Int, r: RushGame? = null): GameState = main {
        val gs = GameState(store, Sfx(store), Haptics(context, store))
        gs.attach(rushBoard(run, index), "", GameMode.RUSH, r)
        gs.listener = rec
        games.add(gs)
        gs
    }

    private fun frames(gs: GameState, seconds: Float, clocks: Boolean = true) =
        main { repeat((seconds / .05f).toInt()) { gs.update(.05f, clocks) } }

    private fun hintTap(gs: GameState) = main {
        val h = gs.board!!.hint()!!
        gs.tap(h.first, h.second)
    }

    fun testRushBoardsAreSolvableAndDiffer() {
        val layouts = HashSet<List<List<Pair<Int, Int>>>>()
        for (run in 0..1) for (index in 0..7) rushBoard(run, index).use { b ->
            rushBoard(run, index).use { c ->
                assertEquals("run $run board $index could not be finished", b.arrowCount, Geometry.playByHints(c))
            }
            assertTrue("run $run board $index has mechanics", b.features().let { it.portals.isEmpty() && it.stops.isEmpty() && it.zones.isEmpty() })
            assertTrue("run $run board $index repeats a layout", layouts.add(List(b.arrowCount) { b.arrow(it).pts.map { p -> p.x to p.y } }))
        }
    }

    fun testClearsScoreAndMistakesCostTime() {
        val gs = rush(0, 0)
        val r = main { gs.rush!! }
        hintTap(gs)
        main {
            assertEquals(1, r.score); assertEquals(1, r.arrows)
            assertEquals(gs.total - 1, gs.remaining)
        }
        val blocked = main { Geometry.blockedArrow(gs.board!!) }
        main {
            gs.tap(blocked, false)
            assertEquals(60f - RushGame.MISTAKE_SECONDS, r.timeLeft, .001f)
            assertEquals(0, r.combo)
            assertEquals("no hearts in a rush", 3, gs.lives)
            assertFalse(gs.lost)
            assertTrue(rec.toasts.contains("-3 seconds"))
            assertFalse("a rush mistake is not a classic heart", gs.moveLog.contains("m;"))
        }
    }

    fun testAClearBoardCarriesTheRunToTheNextOne() {
        val gs = rush(2, 0)
        val r = main { gs.rush!! }
        while (main { gs.remaining } > 0) hintTap(gs)
        main {
            assertTrue(gs.won)
            assertFalse(r.finished)
            assertEquals(1, r.boards)
            assertEquals(60f + RushGame.BOARD_SECONDS, r.timeLeft, .001f)
            assertEquals(r.arrows, gs.total)
        }
        var n = 0
        while (rec.wins == 0 && n++ < 200) frames(gs, .05f)
        assertEquals(1, rec.wins)
        // the next board keeps the same run
        val next = rush(2, 1, r)
        main {
            assertSame(r, next.rush)
            assertEquals(next.total, next.remaining)
            assertFalse(next.won)
            assertTrue(next.clockRunning)
        }
        val scoreBefore = main { r.score }
        hintTap(next)
        main { assertTrue(r.score > scoreBefore) }
    }

    fun testTheRunEndsWhenTheClockRunsOut() {
        val gs = rush(1, 0)
        val r = main { gs.rush!! }
        frames(gs, 30f, clocks = false)
        main { assertEquals("paused under popups and the intro", 60f, r.timeLeft, 0f) }
        frames(gs, 60.2f)
        main {
            assertTrue(r.finished)
            assertEquals(0f, r.timeLeft, 0f)
            assertTrue(gs.won)
            assertFalse(gs.clockRunning)
        }
        frames(gs, 1f)
        assertEquals(1, rec.wins)
        val left = main { gs.remaining }
        hintTap(gs)
        assertEquals("taps after the end are ignored", left, main { gs.remaining })
    }
}
