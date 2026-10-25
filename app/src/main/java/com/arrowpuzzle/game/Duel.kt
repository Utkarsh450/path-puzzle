package com.arrowpuzzle.game

/**
 * Pass-and-play duel: two players share one phone and one board, taking turns. Clearing an
 * arrow scores a point; a wrong tap or a turn that runs out of time costs that player a heart.
 * Either way the turn passes. The game ends when a player has no hearts left (the other wins)
 * or the board is empty (more points wins, then more hearts, else a draw).
 * Pure logic, no Android, so it is unit tested on the JVM.
 */
class DuelGame(val turnSeconds: Float = 8f, hearts: Int = 3) {
    companion object {
        const val DRAW = 2
    }

    val hearts = intArrayOf(hearts, hearts)
    val scores = intArrayOf(0, 0)
    /** Whose turn it is: 0 or 1. */
    var current = 0
        private set
    var timeLeft = turnSeconds
        private set
    /** 0 or 1 for a winner, [DRAW], or -1 while playing. */
    var winner = -1
        private set
    val finished get() = winner >= 0
    var turns = 0
        private set

    private fun pass() {
        current = 1 - current
        timeLeft = turnSeconds
        turns++
    }

    private fun hurt() {
        hearts[current]--
        if (hearts[current] <= 0) { winner = 1 - current; return }
        pass()
    }

    /** The current player cleared [arrows] (usually one); [boardEmpty] after the last one. */
    fun cleared(arrows: Int, boardEmpty: Boolean) {
        if (finished) return
        scores[current] += arrows
        if (boardEmpty) { decide(); return }
        pass()
    }

    /** The current player tapped a blocked arrow. */
    fun mistake() { if (!finished) hurt() }

    /** Advances the turn clock; running out costs a heart. Returns true if the turn timed out. */
    fun tick(dt: Float): Boolean {
        if (finished) return false
        timeLeft -= dt
        if (timeLeft > 0f) return false
        hurt()
        return true
    }

    private fun decide() {
        winner = when {
            scores[0] != scores[1] -> if (scores[0] > scores[1]) 0 else 1
            hearts[0] != hearts[1] -> if (hearts[0] > hearts[1]) 0 else 1
            else -> DRAW
        }
    }
}
