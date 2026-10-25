package com.arrowpuzzle.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests of when a full-screen ad may appear between levels. */
class AdPolicyTest {
    private val minute = 60_000L

    @Test
    fun noInterstitialUntilAFewLevelsWereCompleted() {
        val p = AdPolicy(firstLevel = 6, everyLevels = 3, minGapMs = 0)
        assertFalse(p.allowInterstitial(GameMode.CLASSIC, 10, 0))
        p.levelCompleted(); p.levelCompleted()
        assertFalse(p.allowInterstitial(GameMode.CLASSIC, 10, 0))
        p.levelCompleted()
        assertTrue(p.allowInterstitial(GameMode.CLASSIC, 10, 0))
    }

    @Test
    fun newPlayersAreLeftAloneOnTheFirstLevels() {
        val p = AdPolicy(firstLevel = 6, everyLevels = 1, minGapMs = 0)
        repeat(5) { p.levelCompleted() }
        assertFalse(p.allowInterstitial(GameMode.CLASSIC, 5, 0))
        assertTrue(p.allowInterstitial(GameMode.CLASSIC, 6, 0))
    }

    @Test
    fun duelsAndRushesNeverGetAnInterstitial() {
        val p = AdPolicy(firstLevel = 1, everyLevels = 1, minGapMs = 0)
        repeat(10) { p.levelCompleted() }
        assertFalse(p.allowInterstitial(GameMode.DUEL, 20, 0))
        assertFalse(p.allowInterstitial(GameMode.RUSH, 20, 0))
    }

    @Test
    fun aQuietPeriodFollowsEveryAd() {
        val p = AdPolicy(firstLevel = 1, everyLevels = 1, minGapMs = 2 * minute)
        p.levelCompleted()
        assertTrue(p.allowInterstitial(GameMode.CLASSIC, 9, 10 * minute))
        p.shown(10 * minute)
        p.levelCompleted()
        assertFalse("a minute later", p.allowInterstitial(GameMode.CLASSIC, 9, 11 * minute))
        assertTrue("two minutes later", p.allowInterstitial(GameMode.CLASSIC, 9, 12 * minute))
    }

    @Test
    fun showingAnAdRestartsTheCount() {
        val p = AdPolicy(firstLevel = 1, everyLevels = 3, minGapMs = 0)
        repeat(3) { p.levelCompleted() }
        assertTrue(p.allowInterstitial(GameMode.CLASSIC, 9, 0))
        p.shown(0)   // for instance a rewarded ad the player chose to watch
        assertFalse(p.allowInterstitial(GameMode.CLASSIC, 9, 1))
        repeat(3) { p.levelCompleted() }
        assertTrue(p.allowInterstitial(GameMode.CLASSIC, 9, 2))
    }

    @Test
    fun theDefaultsAreGentle() {
        val p = AdPolicy()
        repeat(2) { p.levelCompleted() }
        assertFalse(p.allowInterstitial(GameMode.CLASSIC, 50, Long.MAX_VALUE / 4))
        p.levelCompleted()
        assertTrue(p.allowInterstitial(GameMode.CLASSIC, 50, Long.MAX_VALUE / 4))
        assertFalse("not in the first levels", AdPolicy().apply { repeat(9) { levelCompleted() } }.allowInterstitial(GameMode.CLASSIC, 4, Long.MAX_VALUE / 4))
        p.shown(1_000)
        repeat(3) { p.levelCompleted() }
        assertFalse("a minute after an ad", p.allowInterstitial(GameMode.CLASSIC, 50, 1_000 + minute))
    }
}
