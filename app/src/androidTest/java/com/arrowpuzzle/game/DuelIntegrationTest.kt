package com.arrowpuzzle.game

import android.test.InstrumentationTestCase
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue

/**
 * Duel mode on real native boards: the duel boards themselves, and GameState running a duel
 * (points, hearts, the turn clock, the end of the game).
 */
@Suppress("DEPRECATION")
class DuelIntegrationTest : InstrumentationTestCase() {
    private lateinit var context: TestPrefsContext
    private lateinit var store: Store
    private val games = ArrayList<GameState>()

    private class Recorder : GameState.Listener {
        var wins = 0
        var loses = 0
        val lives = ArrayList<Int>()
        val toasts = ArrayList<String>()
        override fun onLifeLost(index: Int) { lives.add(index) }
        override fun onWin() { wins++ }
        override fun onLose() { loses++ }
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

    private fun duelBoard(round: Int) = Board(Levels.DUEL_LEVEL, Levels.modeSalt(GameMode.DUEL, round))

    private fun duel(round: Int = 0): GameState = main {
        val gs = GameState(store, Sfx(store), Haptics(context, store))
        gs.attach(duelBoard(round), "", GameMode.DUEL)
        gs.listener = rec
        games.add(gs)
        gs
    }

    /** [seconds] of 50 ms frames, with the mode clocks running or paused. */
    private fun frames(gs: GameState, seconds: Float, clocks: Boolean = true) =
        main { repeat((seconds / .05f).toInt()) { gs.update(.05f, clocks) } }

    private fun hintTap(gs: GameState) = main {
        val h = gs.board!!.hint()!!
        gs.tap(h.first, h.second)
    }

    fun testEveryRoundHasItsOwnSolvableBoard() {
        val layouts = (0..5).map { round ->
            duelBoard(round).use { b -> duelBoard(round).use { c ->
                assertEquals("round $round could not be finished", b.arrowCount, Geometry.playByHints(c))
                assertTrue("round $round: ${b.arrowCount} arrows is too few for a duel", b.arrowCount >= 8)
                assertTrue("round $round has mechanics", b.features().let { it.portals.isEmpty() && it.stops.isEmpty() && it.zones.isEmpty() })
                List(b.arrowCount) { b.arrow(it).pts.map { p -> p.x to p.y } }
            } }
        }
        assertEquals("rounds share a layout", layouts.size, layouts.toSet().size)
    }

    fun testAClearScoresAndPassesTheTurn() {
        val gs = duel()
        val d = main { gs.duel!! }
        hintTap(gs)
        main {
            assertEquals(1, d.scores[0])
            assertEquals(1, d.current)
            assertEquals(gs.total - 1, gs.remaining)
            assertEquals("the classic hearts are not used", 3, gs.lives)
        }
        hintTap(gs)
        main { assertEquals(1, d.scores[1]); assertEquals(0, d.current) }
        // boosters do not work in a duel
        main {
            val id = (0 until gs.total).first { gs.board!!.arrow(it).alive }
            gs.erase(id)
            assertTrue(gs.board!!.arrow(id).alive)
        }
    }

    fun testABlockedTapCostsThePlayerAtTheBoardAHeart() {
        val gs = duel()
        val d = main { gs.duel!! }
        val blocked = main { Geometry.blockedArrow(gs.board!!) }
        main {
            gs.tap(blocked, false)
            assertEquals(2, d.hearts[0])
            assertEquals(3, d.hearts[1])
            assertEquals(1, d.current)
            assertEquals(1, gs.mistakes)
            assertEquals(3, gs.lives)
            assertFalse(gs.lost)
            assertTrue(gs.sprites[blocked].red)
            assertTrue("no classic heart callback", rec.lives.isEmpty())
            assertEquals("a duel writes no saved moves", "", gs.moveLog.toString())
        }
    }

    fun testTheTurnClockPausesAndTimesOut() {
        val gs = duel()
        val d = main { gs.duel!! }
        frames(gs, 20f, clocks = false)
        main { assertEquals(d.turnSeconds, d.timeLeft, 0f); assertEquals(0, d.current) }
        main { assertTrue("a running clock keeps frames coming", gs.clockRunning) }
        frames(gs, d.turnSeconds + .1f)
        main {
            assertEquals(2, d.hearts[0])
            assertEquals(1, d.current)
            assertTrue(rec.toasts.last(), rec.toasts.last().contains("Player 2"))
        }
    }

    fun testRunningOutOfHeartsEndsTheDuel() {
        val gs = duel()
        val d = main { gs.duel!! }
        // five timeouts in a row: player 1 has lost three hearts, player 2 two
        repeat(5) { frames(gs, d.turnSeconds + .1f) }
        main {
            assertTrue(d.finished)
            assertEquals(1, d.winner)
            assertEquals(0, d.hearts[0]); assertEquals(1, d.hearts[1])
            assertTrue(gs.won)
            assertFalse(gs.clockRunning)
        }
        frames(gs, 1f)
        assertEquals(1, rec.wins)
        assertEquals(0, rec.loses)
        // nothing more happens on the board
        val left = main { gs.remaining }
        hintTap(gs)
        assertEquals(left, main { gs.remaining })
    }

    fun testPlayingTheBoardOutDecidesTheWinner() {
        val gs = duel(3)
        val d = main { gs.duel!! }
        val total = main { gs.total }
        while (main { gs.remaining } > 0) hintTap(gs)
        main {
            assertTrue(d.finished)
            assertEquals(total, d.scores[0] + d.scores[1])
            // the first player gets the odd arrow; an even board is a draw
            assertEquals((total + 1) / 2, d.scores[0])
            assertEquals(if (total % 2 == 1) 0 else DuelGame.DRAW, d.winner)
        }
        var n = 0
        while (rec.wins == 0 && n++ < 200) frames(gs, .05f)
        assertEquals(1, rec.wins)
    }
}
