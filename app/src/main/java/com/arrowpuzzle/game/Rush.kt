package com.arrowpuzzle.game

import kotlin.math.min

/**
 * Time rush: clear as many arrows as you can before the clock runs out. Boards follow one
 * another and each cleared board adds time. Quick clears build a combo that multiplies the
 * points; a wrong tap costs time and breaks the combo. Pure logic, no Android, so it is unit
 * tested on the JVM.
 */
class RushGame(startSeconds: Float = START_SECONDS) {
    companion object {
        const val START_SECONDS = 60f
        const val MISTAKE_SECONDS = 3f
        const val BOARD_SECONDS = 10f
        /** Clears this close together keep the combo going. */
        const val COMBO_WINDOW = 2f
        /** Clears in a row per multiplier step. */
        const val COMBO_STEP = 4
        const val MAX_MULTIPLIER = 5
        const val BOARD_POINTS = 10
    }

    var timeLeft = startSeconds
        private set
    var score = 0
        private set
    /** Clears in a row, each within [COMBO_WINDOW] of the last. */
    var combo = 0
        private set
    var boards = 0
        private set
    var arrows = 0
        private set
    var mistakes = 0
        private set
    var finished = false
        private set
    private var sinceClear = 0f

    val multiplier get() = min(MAX_MULTIPLIER, 1 + combo / COMBO_STEP)

    /** The player cleared [n] arrows with one tap. Returns the points scored. */
    fun cleared(n: Int): Int {
        if (finished || n <= 0) return 0
        if (sinceClear > COMBO_WINDOW) combo = 0
        combo += n
        sinceClear = 0f
        val points = n * multiplier
        score += points
        arrows += n
        return points
    }

    /** A wrong tap: time off the clock and the combo is gone. */
    fun mistake() {
        if (finished) return
        mistakes++
        combo = 0
        timeLeft -= MISTAKE_SECONDS
        if (timeLeft <= 0f) { timeLeft = 0f; finished = true }
    }

    /** The board is empty: bonus points scaled by the multiplier, and more time for the next one. */
    fun boardCleared() {
        if (finished) return
        boards++
        score += BOARD_POINTS * multiplier
        timeLeft += BOARD_SECONDS
    }

    /** Advances the clock. Returns true on the frame the time runs out. */
    fun tick(dt: Float): Boolean {
        if (finished) return false
        timeLeft -= dt
        sinceClear += dt
        if (timeLeft > 0f) return false
        timeLeft = 0f
        finished = true
        return true
    }
}

/** Result screen and HUD text for the modes, kept here so it is unit tested. */
object ModeCopy {
    /** A duel panel's name, with the seconds left in the turn for the player to move. */
    fun turnLabel(player: Int, secondsLeft: Float?) =
        "Player ${player + 1}" + if (secondsLeft == null) "" else "  ${kotlin.math.ceil(secondsLeft.coerceAtLeast(0f).toDouble()).toInt()}s"

    fun duelTitle(d: DuelGame) = when (d.winner) {
        DuelGame.DRAW -> "Draw"
        0, 1 -> "Player ${d.winner + 1} wins"
        else -> "Duel"
    }

    fun duelLead(d: DuelGame): String {
        val a = d.scores[0]; val b = d.scores[1]
        return when {
            d.winner == DuelGame.DRAW -> "Tied at ${plural(a, "point")} each, with the same hearts left."
            d.hearts[0] <= 0 || d.hearts[1] <= 0 -> "Player ${2 - d.winner} ran out of hearts. Final score $a to $b."
            a == b -> "Tied at ${plural(a, "point")}, won on hearts left."
            else -> "The board is clear. Final score $a to $b."
        }
    }

    fun rushLead(r: RushGame) = "${plural(r.arrows, "arrow")} cleared across ${plural(r.boards, "board")}."

    fun clock(seconds: Float): String {
        val s = kotlin.math.ceil(seconds.coerceAtLeast(0f).toDouble()).toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
}
