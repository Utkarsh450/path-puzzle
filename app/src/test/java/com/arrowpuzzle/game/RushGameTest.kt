package com.arrowpuzzle.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests of the Time rush rules and of the mode result text. */
class RushGameTest {
    @Test
    fun aRunStartsWithSixtySecondsAndNoPoints() {
        val r = RushGame()
        assertEquals(60f, r.timeLeft, 0f)
        assertEquals(0, r.score)
        assertEquals(1, r.multiplier)
        assertFalse(r.finished)
    }

    @Test
    fun quickClearsBuildTheMultiplier() {
        val r = RushGame()
        val points = (1..12).map { r.tick(.5f); r.cleared(1) }
        // x1 for the first three, then a step every four clears in a row
        assertEquals(listOf(1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4), points)
        assertEquals(points.sum(), r.score)
        assertEquals(12, r.arrows)
    }

    @Test
    fun theMultiplierIsCapped() {
        val r = RushGame()
        repeat(60) { r.cleared(1) }
        assertEquals(RushGame.MAX_MULTIPLIER, r.multiplier)
    }

    @Test
    fun aSlowClearStartsTheComboAgain() {
        val r = RushGame()
        repeat(8) { r.cleared(1) }
        assertEquals(3, r.multiplier)
        r.tick(RushGame.COMBO_WINDOW + .1f)
        assertEquals("the first clear after a pause scores x1", 1, r.cleared(1))
    }

    @Test
    fun aMistakeCostsThreeSecondsAndTheCombo() {
        val r = RushGame()
        repeat(8) { r.cleared(1) }
        r.mistake()
        assertEquals(57f, r.timeLeft, 1e-4f)
        assertEquals(0, r.combo)
        assertEquals(1, r.multiplier)
        assertEquals(1, r.mistakes)
    }

    @Test
    fun mistakesCanEndTheRun() {
        val r = RushGame(startSeconds = 5f)
        r.mistake()
        assertFalse(r.finished)
        r.mistake()
        assertTrue(r.finished)
        assertEquals(0f, r.timeLeft, 0f)
    }

    @Test
    fun aClearBoardAddsTimeAndBonusPoints() {
        val r = RushGame()
        repeat(4) { r.cleared(1) }   // 1+1+1+2, multiplier now x2
        r.boardCleared()
        assertEquals(1, r.boards)
        assertEquals(70f, r.timeLeft, 1e-4f)
        assertEquals(5 + RushGame.BOARD_POINTS * 2, r.score)
    }

    @Test
    fun theClockRunsOutOnce() {
        val r = RushGame(startSeconds = 1f)
        assertFalse(r.tick(.6f))
        assertTrue(r.tick(.6f))
        assertTrue(r.finished)
        assertEquals(0f, r.timeLeft, 0f)
        // nothing counts after the end
        assertFalse(r.tick(1f))
        assertEquals(0, r.cleared(3))
        r.boardCleared(); r.mistake()
        assertEquals(0, r.score)
        assertEquals(0, r.boards)
    }

    @Test
    fun clockText() {
        assertEquals("1:00", ModeCopy.clock(60f))
        assertEquals("0:42", ModeCopy.clock(41.2f))
        assertEquals("0:01", ModeCopy.clock(.3f))
        assertEquals("0:00", ModeCopy.clock(-2f))
        assertEquals("1:10", ModeCopy.clock(70f))
    }

    @Test
    fun duelPanelShowsTheSecondsLeftForThePlayerToMove() {
        assertEquals("Player 1  8s", ModeCopy.turnLabel(0, 8f))
        assertEquals("Player 2  3s", ModeCopy.turnLabel(1, 2.1f))
        assertEquals("Player 2  1s", ModeCopy.turnLabel(1, .2f))
        assertEquals("Player 1  0s", ModeCopy.turnLabel(0, -1f))
        assertEquals("the waiting player has no clock", "Player 2", ModeCopy.turnLabel(1, null))
    }

    @Test
    fun resultText() {
        val r = RushGame()
        r.cleared(1)
        assertEquals("1 arrow cleared across 0 boards.", ModeCopy.rushLead(r))
        r.cleared(2); r.boardCleared()
        assertEquals("3 arrows cleared across 1 board.", ModeCopy.rushLead(r))

        val d = DuelGame()
        d.cleared(2, false); d.cleared(1, true)
        assertEquals("Player 1 wins", ModeCopy.duelTitle(d))
        assertEquals("The board is clear. Final score 2 to 1.", ModeCopy.duelLead(d))

        val out = DuelGame(hearts = 1)
        out.mistake()
        assertEquals("Player 2 wins", ModeCopy.duelTitle(out))
        assertEquals("Player 1 ran out of hearts. Final score 0 to 0.", ModeCopy.duelLead(out))

        val draw = DuelGame()
        draw.cleared(1, false); draw.cleared(1, true)
        assertEquals("Draw", ModeCopy.duelTitle(draw))
        assertEquals("Tied at 1 point each, with the same hearts left.", ModeCopy.duelLead(draw))
    }

    @Test
    fun resultTextFollowsTheCopyRules() {
        // sentence case, no exclamation marks, as CopyRulesTest asks of all game text
        for (s in listOf("Time's up", ModeCopy.duelTitle(DuelGame()), "Play again", "Two players, one phone", "60 seconds")) {
            assertFalse(s, s.contains('!'))
            assertTrue(s, s.first().isUpperCase() || s.first().isDigit())
        }
    }
}
