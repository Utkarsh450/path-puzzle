package com.arrowpuzzle.game

import android.content.pm.PackageManager
import android.test.InstrumentationTestCase
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue

/** The ad layer on the device: how the game asks for ads, and that the app is set up for AdMob. */
@Suppress("DEPRECATION")
class AdsIntegrationTest : InstrumentationTestCase() {
    private fun ads(fake: FakeAds, policy: AdPolicy = AdPolicy(firstLevel = 1, everyLevels = 1, minGapMs = 0)) = Ads(fake, policy)

    fun testTheAppIsConfiguredForAdMob() {
        val ctx = instrumentation.targetContext
        // a missing AdMob app id makes the SDK crash the app at start-up, so this must never be absent
        val meta = ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA).metaData
        val appId = meta.getString("com.google.android.gms.ads.APPLICATION_ID")
        assertNotNull(appId)
        assertTrue(appId, Regex("ca-app-pub-\\d{16}~\\d{10}").matches(appId!!))
        assertTrue(BuildConfig.REWARDED_UNIT, Regex("ca-app-pub-\\d{16}/\\d{10}").matches(BuildConfig.REWARDED_UNIT))
        assertTrue(BuildConfig.INTERSTITIAL_UNIT, Regex("ca-app-pub-\\d{16}/\\d{10}").matches(BuildConfig.INTERSTITIAL_UNIT))
        // debug builds only ever show Google's test ads, so developing never risks the AdMob account
        assertTrue("debug builds use test ads", BuildConfig.TEST_ADS)
        assertEquals("ca-app-pub-3940256099942544~3347511713", appId)
        for (p in listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"))
            assertEquals(p, PackageManager.PERMISSION_GRANTED, ctx.packageManager.checkPermission(p, ctx.packageName))
    }

    fun testARewardIsGrantedOnlyWhenTheAdWasWatchedThrough() {
        val fake = FakeAds(watchThrough = true)
        var rewards = 0; var done = 0
        ads(fake).watchForReward({ rewards++ }) { done++ }
        assertEquals(1, rewards); assertEquals(1, done); assertEquals(1, fake.rewardedShown)

        val skipped = FakeAds(watchThrough = false)
        ads(skipped).watchForReward({ rewards++ }) { done++ }
        assertEquals("closing the ad early earns nothing", 1, rewards); assertEquals(2, done)
    }

    fun testNoAdReadyMeansNoRewardAndNoWaiting() {
        val fake = FakeAds(rewarded = false)
        var rewards = 0; var done = 0
        ads(fake).watchForReward({ rewards++ }) { done++ }
        assertEquals(0, rewards); assertEquals(1, done); assertEquals(0, fake.rewardedShown)
        assertFalse(ads(fake).rewardedReady)
        // and the offline fallback behaves the same
        Ads(NoAds, AdPolicy()).watchForReward({ rewards++ }) { done++ }
        assertEquals(0, rewards); assertEquals(2, done)
    }

    fun testInterstitialsFollowTheCadenceAndNeverBlockTheGame() {
        val fake = FakeAds()
        val a = ads(fake, AdPolicy(firstLevel = 4, everyLevels = 2, minGapMs = 0))
        var next = 0
        a.betweenLevels(GameMode.CLASSIC, 3, true) { next++ }   // too early in the game
        a.betweenLevels(GameMode.CLASSIC, 4, true) { next++ }   // second completed level: ad
        assertEquals(2, next); assertEquals(1, fake.interstitialShown)
        fake.interstitial = true
        a.betweenLevels(GameMode.CLASSIC, 5, true) { next++ }   // count restarted
        assertEquals(3, next); assertEquals(1, fake.interstitialShown)
        // no ad loaded: the game just goes on
        val none = FakeAds(interstitial = false)
        ads(none).betweenLevels(GameMode.CLASSIC, 9, true) { next++ }
        assertEquals(4, next); assertEquals(0, none.interstitialShown)
        // duels and rushes are left alone
        fake.interstitial = true
        ads(fake).betweenLevels(GameMode.DUEL, 9, true) { next++ }
        ads(fake).betweenLevels(GameMode.RUSH, 9, true) { next++ }
        assertEquals(6, next); assertEquals(1, fake.interstitialShown)
    }

    fun testWithoutAnActivityTheGameHasNoAds() {
        val v = instrumentation.onMain { GameView(instrumentation.targetContext) }
        assertFalse(v.ads.rewardedReady)
        assertFalse(v.ads.provider.privacyOptionsRequired)
    }
}
