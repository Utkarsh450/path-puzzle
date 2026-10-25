package com.arrowpuzzle.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the Kotlin-side level rules that must agree with puzzle_core.cpp. */
class LevelsTest {
    // Copy of difficultyOf() in puzzle_core.cpp.
    private fun nativeDifficulty(level: Int) = when {
        level < 6 -> 0
        level % 25 == 0 -> 3
        level % 10 == 0 -> 2
        level % 5 == 0 -> 1
        else -> 0
    }

    @Test
    fun difficultyMatchesTheNativeCore() {
        for (level in 1..500) assertEquals("level $level", nativeDifficulty(level), Levels.difficultyIndex(level))
    }

    @Test
    fun difficultyNames() {
        assertEquals("Normal", Levels.difficulty(1))
        assertEquals("Normal", Levels.difficulty(5))
        assertEquals("Hard", Levels.difficulty(15))
        assertEquals("Expert", Levels.difficulty(20))
        assertEquals("Master", Levels.difficulty(50))
    }

    @Test
    fun dailyLevelsStayInRange() {
        for (date in 20260101..20261231) {
            val level = Levels.dailyLevel(date)
            assertTrue("date $date -> level $level", level in 30 until 75)
        }
    }

    @Test
    fun modeBoardsAreFlaggedAndNotDaily() {
        // the Kotlin flag must equal kVariantFlag in puzzle_core.h
        assertEquals(0x40000000, Levels.VARIANT_FLAG)
        val salts = GameMode.values().filter { it != GameMode.CLASSIC }.map { Levels.modeSalt(it) }
        assertEquals("one layout per mode", salts.size, salts.toSet().size)
        for (salt in salts) {
            assertTrue(Levels.isModeBoard(salt))
            assertFalse(Levels.isDaily(salt))
        }
    }

    @Test
    fun dailySaltIsNotAModeBoard() {
        assertTrue(Levels.isDaily(20261024))
        assertFalse(Levels.isModeBoard(20261024))
        assertFalse(Levels.isDaily(0))
    }

    @Test
    fun everyRoundGetsItsOwnModeBoard() {
        val salts = (0 until 200).flatMap { listOf(Levels.modeSalt(GameMode.DUEL, it), Levels.modeSalt(GameMode.RUSH, it)) }
        assertEquals(salts.size, salts.toSet().size)
        for (s in salts) { assertTrue(Levels.isModeBoard(s)); assertTrue("salt stays positive", s > 0) }
        assertEquals("round 0 is the plain mode salt", Levels.VARIANT_FLAG or GameMode.DUEL.key, Levels.modeSalt(GameMode.DUEL))
    }

    @Test
    fun rushBoardsGrowAndStayBelowTheFirstMechanic() {
        val levels = (0..20).map { Levels.rushBoardLevel(it) }
        assertEquals(4, levels.first())
        assertEquals(levels, levels.sorted())
        assertTrue(levels.all { it < Mechanic.unlockLevels.first() })
        assertTrue(Levels.DUEL_LEVEL < Mechanic.unlockLevels.first())
        // runs and boards within a run never share a layout
        val salts = (0 until 5).flatMap { run -> (0 until 64).map { Levels.rushSalt(run, it) } }
        assertEquals(salts.size, salts.toSet().size)
    }
}
