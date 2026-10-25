package com.arrowpuzzle.game

import android.test.InstrumentationTestCase
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue

/** [Store] against real SharedPreferences (a test-only file). */
@Suppress("DEPRECATION")
class StoreIntegrationTest : InstrumentationTestCase() {
    private lateinit var context: TestPrefsContext

    override fun setUp() {
        super.setUp()
        context = TestPrefsContext(instrumentation.targetContext)
        context.wipe()
    }

    override fun tearDown() {
        context.wipe()
        super.tearDown()
    }

    fun testDefaultsForANewPlayer() {
        val s = Store(context)
        assertEquals(1, s.level)
        assertEquals(50, s.coins)
        assertTrue(s.sound)
        assertTrue(s.vibration)
        assertFalse(s.gridGuide)
        assertTrue("ChaiUI is dark by default", s.darkTheme)
        assertEquals(3, s.count(Booster.HINT))
        assertEquals(2, s.count(Booster.ERASER))
        assertEquals(2, s.count(Booster.WAND))
        assertEquals(0, s.levelsCompleted)
        assertEquals(0, s.savedLevel)
        assertEquals("", s.savedMoves)
        assertEquals(0, s.rushBest); assertEquals(0, s.rushRuns); assertEquals(0, s.duelRounds)
        assertFalse(s.seen("tut_tap"))
    }

    fun testEverySettingSurvivesANewStoreInstance() {
        val s = Store(context)
        s.level = 42; s.coins = 999; s.sound = false; s.vibration = false; s.gridGuide = true; s.darkTheme = false
        s.setCount(Booster.HINT, 7); s.setCount(Booster.ERASER, 0); s.setCount(Booster.WAND, 5)
        s.levelsCompleted = 41; s.perfectLevels = 20; s.arrowsCleared = 3000
        s.bestStreak = 9; s.winStreak = 4; s.hintsUsed = 11; s.rushBest = 340; s.rushRuns = 5; s.duelRounds = 6; s.duelsPlayed = 4; s.savedMode = 3
        s.markSeen("tut_zoom")
        s.savedLevel = 42; s.savedSalt = 0; s.savedMoves = "t3;m;r5;t7r;e2;"

        val r = Store(context)
        assertEquals(42, r.level); assertEquals(999, r.coins)
        assertFalse(r.sound); assertFalse(r.vibration); assertTrue(r.gridGuide); assertFalse(r.darkTheme)
        assertEquals(7, r.count(Booster.HINT)); assertEquals(0, r.count(Booster.ERASER)); assertEquals(5, r.count(Booster.WAND))
        assertEquals(41, r.levelsCompleted); assertEquals(20, r.perfectLevels); assertEquals(3000, r.arrowsCleared)
        assertEquals(9, r.bestStreak); assertEquals(4, r.winStreak); assertEquals(11, r.hintsUsed); assertEquals(340, r.rushBest); assertEquals(5, r.rushRuns)
        assertEquals(6, r.duelRounds); assertEquals(4, r.duelsPlayed); assertEquals(3, r.savedMode)
        assertTrue(r.seen("tut_zoom"))
        assertEquals("t3;m;r5;t7r;e2;", r.savedMoves)
    }

    fun testDailyCompletion() {
        val s = Store(context)
        assertFalse(s.dailyDone(20261024))
        for (d in listOf(20261001, 20261002, 20261024, 20260930)) s.setDailyDone(d)
        assertTrue(s.dailyDone(20261024))
        assertEquals(3, s.dailyCount(202610))
        assertEquals(1, s.dailyCount(202609))
        assertEquals(0, s.dailyCount(202611))
    }

    fun testClearSaveForgetsOnlyTheLevelInProgress() {
        val s = Store(context)
        s.level = 8; s.coins = 70
        s.savedLevel = 8; s.savedSalt = 0; s.savedMoves = "t1;"
        s.clearSave()
        assertEquals(0, s.savedLevel)
        assertEquals("", s.savedMoves)
        assertEquals(8, s.level)
        assertEquals(70, s.coins)
    }
}
