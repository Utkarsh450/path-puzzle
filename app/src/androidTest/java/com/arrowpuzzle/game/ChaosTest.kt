package com.arrowpuzzle.game

import android.os.SystemClock
import android.test.InstrumentationTestCase
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import java.util.Random

/**
 * Tries to break the game logic: thousands of random and invalid actions in every mode, damaged
 * saved games, and save/replay round trips. Whatever happens, the rules' invariants must hold and
 * nothing may throw.
 */
@Suppress("DEPRECATION")
class ChaosTest : InstrumentationTestCase() {
    private lateinit var context: TestPrefsContext
    private lateinit var store: Store
    private val games = ArrayList<GameState>()
    /** How often the player continued after a loss (the game lets them, for coins, as often as they like). */
    private var continues = 0

    private class Recorder : GameState.Listener {
        var wins = 0
        var loses = 0
        override fun onLifeLost(index: Int) {}
        override fun onWin() { wins++ }
        override fun onLose() { loses++ }
        override fun onPraise(text: String) {}
        override fun onToast(text: String) {}
        override fun onSaved() {}
    }

    override fun setUp() {
        super.setUp()
        context = TestPrefsContext(instrumentation.targetContext)
        context.wipe()
        store = Store(context)
        store.sound = false
        store.vibration = false
        store.coins = 100000
        for (b in Booster.values()) store.setCount(b, 500)
    }

    override fun tearDown() {
        main { for (g in games) g.close() }
        context.wipe()
        super.tearDown()
    }

    private fun <T> main(block: () -> T): T = instrumentation.onMain(block)

    private fun newGame(level: Int, salt: Int, mode: GameMode, replay: String = "", rush: RushGame? = null): Pair<GameState, Recorder> = main {
        val gs = GameState(store, Sfx(store), Haptics(context, store))
        val rec = Recorder()
        gs.listener = rec
        gs.attach(Board(level, salt), replay, mode, rush)
        games.add(gs)
        gs to rec
    }

    /** What must be true of a game after any action. */
    private fun checkInvariants(gs: GameState, rec: Recorder, label: String) {
        val b = gs.board!!
        assertTrue("$label: lives ${gs.lives}", gs.lives in 0..3)
        assertTrue("$label: mistakes ${gs.mistakes}", gs.mistakes >= 0)
        assertFalse("$label: won and lost at once", gs.won && gs.lost)
        assertEquals("$label: arrows left differ from the board", b.remaining(), gs.remaining)
        assertTrue("$label: percent ${gs.percentCleared()}", gs.percentCleared() in 0..100)
        assertTrue("$label: announced a win more than once", rec.wins <= 1)
        if (gs.mode == GameMode.CLASSIC) {
            if (gs.won && gs.duel == null && gs.rush == null) assertEquals("$label: won with arrows left", 0, gs.remaining)
            if (gs.lost) assertEquals("$label: lost with hearts left", 0, gs.lives)
        }
        gs.duel?.let { d ->
            assertTrue("$label: duel hearts", d.hearts.all { it in 0..3 })
            assertTrue("$label: duel turn", d.current in 0..1)
            assertTrue("$label: duel time ${d.timeLeft}", d.timeLeft in 0f..d.turnSeconds)
            if (d.finished) assertTrue("$label: finished duel must end the game", gs.won)
        }
        gs.rush?.let { r ->
            assertTrue("$label: rush time ${r.timeLeft}", r.timeLeft >= 0f)
            assertTrue("$label: rush score ${r.score}", r.score >= 0)
            if (r.finished) assertTrue("$label: finished rush must end the game", gs.won)
        }
        for (s in gs.sprites) assertTrue("$label: sprite ${s.id} alpha ${s.alpha}", s.alpha in 0f..1f)
    }

    /** A random but plausible-or-hostile action on the game. */
    private fun randomAction(gs: GameState, rnd: Random, n: Int) {
        val id = rnd.nextInt(n + 8) - 4                       // includes -4..-1 and n..n+3
        when (rnd.nextInt(12)) {
            0, 1, 2, 3, 4 -> gs.tap(id, rnd.nextBoolean())
            5 -> gs.erase(id)
            6 -> { gs.useHint(); }
            7 -> gs.useWand()
            8 -> if (gs.lost) { gs.continueAfterLoss(); continues++ }
            9 -> gs.eraserArmed = rnd.nextBoolean()
            else -> gs.update(rnd.nextFloat() * .3f, rnd.nextBoolean())
        }
    }

    private fun hammer(level: Int, salt: Int, mode: GameMode, seed: Long, steps: Int, rush: RushGame? = null) {
        val (gs, rec) = newGame(level, salt, mode, rush = rush)
        continues = 0
        val rnd = Random(seed)
        val n = main { gs.total }
        repeat(steps) { i ->
            main { randomAction(gs, rnd, n) }
            if (i % 40 == 0) SystemClock.sleep(5)             // let background hints land
            main { checkInvariants(gs, rec, "level $level $mode seed $seed step $i") }
        }
        // however the game ended up, ticking on never throws and the announcements stay unique
        main { repeat(60) { gs.update(.1f, true) } ; checkInvariants(gs, rec, "level $level $mode seed $seed final") }
        assertTrue("losses announced ${rec.loses} after $continues continues", rec.loses <= continues + 1)
    }

    fun testRandomChaosInTheMainGame() {
        for ((i, level) in listOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 12, 15, 20, 30, 45, 60).withIndex())
            hammer(level, 0, GameMode.CLASSIC, 100L + i, 500)
    }

    fun testRandomChaosInDailies() {
        for (date in listOf(20261025, 20260101, 20361231, 20270229))   // the last is not a real date: still must not break
            hammer(Levels.dailyLevel(date), date, GameMode.CLASSIC, date.toLong(), 400)
    }

    fun testRandomChaosInDuels() {
        for (round in 0..7) hammer(Levels.DUEL_LEVEL, Levels.modeSalt(GameMode.DUEL, round), GameMode.DUEL, 500L + round, 500)
    }

    fun testRandomChaosInTimeRush() {
        for (run in 0..5) {
            val rush = RushGame()
            hammer(Levels.rushBoardLevel(run), Levels.rushSalt(run, run), GameMode.RUSH, 900L + run, 500, rush)
        }
    }

    /** Playing the board out with hints, then hammering a finished game, must stay calm. */
    fun testFinishedGamesIgnoreEverything() {
        val (gs, rec) = newGame(9, 0, GameMode.CLASSIC)
        while (main { gs.remaining } > 0) main { val h = gs.board!!.hint()!!; gs.tap(h.first, h.second) }
        repeat(80) { main { gs.update(.1f, true) } }
        assertEquals(1, rec.wins)
        val rnd = Random(7)
        repeat(300) { main { randomAction(gs, rnd, gs.total) } }
        main { checkInvariants(gs, rec, "after the end") }
        assertEquals("a win is announced exactly once", 1, rec.wins)
        assertEquals(0, rec.loses)
    }

    /** Damaged, truncated and hostile saved games must never crash, and must still produce a playable board. */
    fun testDamagedSavedGamesAreSurvived() {
        val hostile = listOf(
            "", ";", ";;;;", "t", "t;", "tabc;", "t-5;", "t99999999999;", "t2147483648;", "e;", "eX;", "m;m;m;m;m;m;", "r;", "rr;",
            "c;c;c;", "zzz;t1;", "t1r;t1r;t1r;", "t1;t1;t1;t1;t1;", "t0;t1;t2;t3;t4;t5;t6;t7;t8;t9;t10;t11;", "\u0000;￿;",
            "t1;m;m;m;m;", "t".repeat(5000), "t1;".repeat(3000), "m;".repeat(3000), "e1;".repeat(500), "😀;t1;😀;",
            "t 1;", "t1 ;", "T1;", ";t1", "t1;;;t2;;", "tr;", "ttt;eee;mmm;rrr;ccc;"
        )
        for (level in listOf(1, 6, 12)) for (replay in hostile) {
            val (gs, rec) = newGame(level, 0, GameMode.CLASSIC, replay)
            main { checkInvariants(gs, rec, "level $level replay '${replay.take(20)}'") }
            // and the board is still playable to the end if it is not already over
            if (main { !gs.lost && !gs.won }) {
                var guard = 0
                while (main { gs.remaining } > 0 && guard++ < 500) main { val h = gs.board!!.hint(); if (h != null) gs.tap(h.first, h.second) else guard = 999 }
                main { checkInvariants(gs, rec, "level $level replay '${replay.take(20)}' played out") }
            }
        }
    }

    /** What is saved always restores exactly: random play, then replay on a fresh board. */
    fun testSaveAndReplayAlwaysRestoreTheSameGame() {
        for (seed in 1..24) {
            val level = listOf(2, 3, 4, 6, 9, 12, 20)[seed % 7]
            val (gs, _) = newGame(level, 0, GameMode.CLASSIC)
            val rnd = Random(seed.toLong())
            repeat(80) {
                main {
                    val n = gs.total
                    // only actions that are logged: taps (valid, random and hint-guided), erases
                    when (rnd.nextInt(4)) {
                        0 -> { val h = gs.board!!.hint(); if (h != null) gs.tap(h.first, h.second) }
                        1 -> gs.erase(rnd.nextInt(n))
                        else -> gs.tap(rnd.nextInt(n), rnd.nextBoolean())
                    }
                }
            }
            val log = main { gs.moveLog.toString() }
            val (restored, _) = newGame(level, 0, GameMode.CLASSIC, log)
            main {
                assertEquals("seed $seed arrows left", gs.board!!.remaining(), restored.board!!.remaining())
                assertEquals("seed $seed mistakes", gs.mistakes, restored.mistakes)
                // a game that was lost before saving is restored with the one heart it had to continue on
                if (!gs.lost) assertEquals("seed $seed lives", gs.lives, restored.lives)
                for (id in 0 until gs.total) assertEquals("seed $seed arrow $id alive", gs.board!!.arrow(id).alive, restored.board!!.arrow(id).alive)
                assertEquals("seed $seed the log is kept as it was", log, restored.moveLog.toString())
            }
        }
    }

    /** Boards for every kind of salt, level and shape build and finish quickly enough on the phone. */
    fun testBoardsBuildFastEnoughOnThePhone() {
        var worstMs = 0L
        var worst = ""
        val jobs = ArrayList<Pair<Int, Int>>()
        for (level in listOf(1, 5, 9, 14, 25, 33, 50, 75, 100, 150, 200, 300)) jobs.add(level to 0)
        for (d in listOf(20261001, 20261015, 20261031, 20270101)) jobs.add(Levels.dailyLevel(d) to d)
        for (r in 0..11) jobs.add(Levels.DUEL_LEVEL to Levels.modeSalt(GameMode.DUEL, r))
        for ((level, salt) in jobs) {
            val t0 = SystemClock.elapsedRealtime()
            Board(level, salt).use { b -> assertTrue("level $level has no arrows", b.arrowCount > 0) }
            val t = SystemClock.elapsedRealtime() - t0
            if (t > worstMs) { worstMs = t; worst = "level $level salt $salt" }
        }
        android.util.Log.i("PathPuzzle", "chaos: slowest board on this phone: $worstMs ms ($worst)")
        assertTrue("a board took $worstMs ms ($worst): too slow for a loading screen", worstMs < 4000)
    }
}
