package com.arrowpuzzle.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests of the pass-and-play duel rules. */
class DuelGameTest {
    @Test
    fun clearingScoresAndPassesTheTurn() {
        val d = DuelGame()
        assertEquals(0, d.current)
        d.cleared(1, false)
        assertEquals(1, d.scores[0])
        assertEquals(1, d.current)
        assertEquals(8f, d.timeLeft, 0f)
        d.cleared(2, false)
        assertEquals(2, d.scores[1])
        assertEquals(0, d.current)
        assertEquals(2, d.turns)
    }

    @Test
    fun aMistakeCostsAHeartAndPassesTheTurn() {
        val d = DuelGame()
        d.mistake()
        assertEquals(2, d.hearts[0])
        assertEquals(3, d.hearts[1])
        assertEquals(1, d.current)
        assertFalse(d.finished)
    }

    @Test
    fun runningOutOfTimeIsAMistake() {
        val d = DuelGame(turnSeconds = 5f)
        assertFalse(d.tick(4.9f))
        assertEquals(0, d.current)
        assertTrue(d.tick(.2f))
        assertEquals(2, d.hearts[0])
        assertEquals(1, d.current)
        assertEquals(5f, d.timeLeft, 0f)
    }

    @Test
    fun losingAllHeartsHandsTheOtherPlayerTheWin() {
        val d = DuelGame(hearts = 2)
        d.mistake()      // player 0: 1 heart, turn to 1
        d.cleared(1, false)  // player 1 scores, turn to 0
        d.mistake()      // player 0: out
        assertTrue(d.finished)
        assertEquals(1, d.winner)
        // nothing changes after the end
        d.cleared(5, false); d.mistake(); assertFalse(d.tick(100f))
        assertEquals(1, d.scores[1])
    }

    @Test
    fun anEmptyBoardGoesToTheHigherScore() {
        val d = DuelGame()
        d.cleared(1, false)  // 1-0
        d.cleared(1, false)  // 1-1
        d.cleared(1, true)   // 2-1, board empty
        assertTrue(d.finished)
        assertEquals(0, d.winner)
    }

    @Test
    fun scoreTiesGoToHeartsThenDraw() {
        val byHearts = DuelGame()
        byHearts.mistake()        // p0 loses a heart, turn to p1
        byHearts.cleared(1, false) // 0-1, turn to p0
        byHearts.cleared(1, true)  // 1-1, board empty; p1 has more hearts
        assertEquals(1, byHearts.winner)

        val draw = DuelGame()
        draw.cleared(1, false)
        draw.cleared(1, true)
        assertEquals(DuelGame.DRAW, draw.winner)
    }
}
