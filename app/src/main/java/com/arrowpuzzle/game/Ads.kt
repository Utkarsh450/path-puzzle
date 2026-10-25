package com.arrowpuzzle.game

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * When a full-screen ad may appear between levels. Google Play and AdMob reject apps that
 * interrupt play or show ads too eagerly, so: never in the first levels, never in a duel or a
 * rush, only every few completed levels, and never twice in quick succession. Pure logic, unit
 * tested on the JVM.
 */
class AdPolicy(
    private val firstLevel: Int = 6,
    private val everyLevels: Int = 3,
    private val minGapMs: Long = 120_000L
) {
    private var sinceAd = 0
    private var lastAdAt = Long.MIN_VALUE / 2

    /** A main-game level or a daily was completed. */
    fun levelCompleted() { sinceAd++ }

    /** Whether an interstitial may show now, before the player starts [nextLevel] in [mode]. */
    fun allowInterstitial(mode: GameMode, nextLevel: Int, nowMs: Long): Boolean =
        mode == GameMode.CLASSIC && nextLevel >= firstLevel && sinceAd >= everyLevels && nowMs - lastAdAt >= minGapMs

    /** Any full-screen ad (rewarded ones too) restarts the count and the quiet period. */
    fun shown(nowMs: Long) { sinceAd = 0; lastAdAt = nowMs }
}

/** What the game needs from an ad network; tests replace it with a fake. */
interface AdProvider {
    val rewardedReady: Boolean
    val interstitialReady: Boolean
    /** Shows a rewarded ad. [onReward] only if the player watched it through; [onDone] always, last. */
    fun showRewarded(onReward: () -> Unit, onDone: () -> Unit)
    /** Shows an interstitial; [onDone] always, whether or not one was shown. */
    fun showInterstitial(onDone: () -> Unit)
    /** Settings entry for the consent choices, when the player's region requires one. */
    val privacyOptionsRequired: Boolean
    fun showPrivacyOptions()
}

/** No ads: used when they are switched off (tests) and as the fallback if the SDK is unavailable. */
object NoAds : AdProvider {
    override val rewardedReady = false
    override val interstitialReady = false
    override fun showRewarded(onReward: () -> Unit, onDone: () -> Unit) = onDone()
    override fun showInterstitial(onDone: () -> Unit) = onDone()
    override val privacyOptionsRequired = false
    override fun showPrivacyOptions() {}
}

/** Where ads come from; instrumented tests swap these before the activity starts. */
object AdsConfig {
    @Volatile var provider: ((Activity) -> AdProvider) = { AdMobProvider(it) }
    @Volatile var policy: () -> AdPolicy = { AdPolicy() }

    fun reset() { provider = { AdMobProvider(it) }; policy = { AdPolicy() } }
}

/**
 * Google AdMob: asks for consent where the law requires it (UMP), initialises the SDK once that
 * allows ads, and keeps one rewarded and one interstitial ad loaded. Without a network nothing
 * loads and the game simply offers no ads; it never waits for one.
 */
class AdMobProvider(private val activity: Activity) : AdProvider {
    private val main = Handler(Looper.getMainLooper())
    private val consent: ConsentInformation = UserMessagingPlatform.getConsentInformation(activity)
    private var started = false
    private var rewarded: RewardedAd? = null
    private var interstitial: InterstitialAd? = null
    private var loadingRewarded = false
    private var loadingInterstitial = false
    private var retry = 0
    private var retryPending = false

    init {
        // Keep the ad SDK off the start-up path: the first screens draw first, consent and ads follow.
        main.postDelayed({ begin() }, 1500L)
    }

    private fun begin() {
        if (activity.isFinishing || activity.isDestroyed) return
        consent.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { start() }
        }, { start() })
        // a returning player who already gave an answer can load ads straight away
        start()
    }

    private fun start() {
        if (started || !consent.canRequestAds()) return
        started = true
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder().setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_PG).build())
        // the SDK's own set-up (it starts a WebView) is heavy, so never run it on the UI thread
        Thread { MobileAds.initialize(activity.applicationContext) { main.post { load() } } }.start()
    }

    private fun online(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.activeNetwork != null
    }

    private fun load() {
        if (!started || activity.isDestroyed) return
        // Started offline? Keep checking quietly, so ads appear once the player gets a connection.
        if (!online()) { retryLater(); return }
        if (rewarded == null && !loadingRewarded && online()) {
            loadingRewarded = true
            RewardedAd.load(activity, BuildConfig.REWARDED_UNIT, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) { loadingRewarded = false; rewarded = ad; retry = 0; android.util.Log.i("PathPuzzle", "rewarded ad ready") }
                override fun onAdFailedToLoad(e: LoadAdError) { loadingRewarded = false; android.util.Log.i("PathPuzzle", "rewarded ad failed: ${e.code}"); retryLater() }
            })
        }
        if (interstitial == null && !loadingInterstitial && online()) {
            loadingInterstitial = true
            InterstitialAd.load(activity, BuildConfig.INTERSTITIAL_UNIT, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { loadingInterstitial = false; interstitial = ad; android.util.Log.i("PathPuzzle", "interstitial ad ready") }
                override fun onAdFailedToLoad(e: LoadAdError) { loadingInterstitial = false; retryLater() }
            })
        }
    }

    /** Back off (up to a minute) so a bad connection never hammers the ad server. */
    private fun retryLater() {
        if (retryPending) return          // one waiting retry at a time, however often load() is asked for
        retryPending = true
        retry = minOf(retry + 1, 6)
        main.postDelayed({ retryPending = false; load() }, 10_000L * retry)
    }

    override val rewardedReady get() = rewarded != null
    override val interstitialReady get() = interstitial != null

    override fun showRewarded(onReward: () -> Unit, onDone: () -> Unit) {
        val ad = rewarded
        if (ad == null || activity.isFinishing) { load(); onDone(); return }
        rewarded = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { load(); onDone() }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { load(); onDone() }
        }
        ad.show(activity) { onReward() }
    }

    override fun showInterstitial(onDone: () -> Unit) {
        val ad = interstitial
        if (ad == null || activity.isFinishing) { load(); onDone(); return }
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { load(); onDone() }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { load(); onDone() }
        }
        ad.show(activity)
    }

    override val privacyOptionsRequired
        get() = consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    override fun showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { }
    }
}

/** What the screens use: the provider plus the cadence rules and the "no ads" safety nets. */
class Ads(val provider: AdProvider, private val policy: AdPolicy) {
    val rewardedReady get() = provider.rewardedReady

    /** Offers a rewarded ad; the reward is granted only after it was watched through. */
    fun watchForReward(onReward: () -> Unit, onDone: () -> Unit = {}) {
        if (!provider.rewardedReady) { onDone(); return }
        var earned = false
        provider.showRewarded({ earned = true }) {
            policy.shown(System.currentTimeMillis())
            if (earned) onReward()
            onDone()
        }
    }

    /** A level was completed without going on to another one (back to the menu). */
    fun countLevel() = policy.levelCompleted()

    /** The player finished a level and is about to start the next: maybe an interstitial, then [next]. */
    fun betweenLevels(mode: GameMode, nextLevel: Int, completed: Boolean, next: () -> Unit) {
        if (completed) policy.levelCompleted()
        if (provider.interstitialReady && policy.allowInterstitial(mode, nextLevel, System.currentTimeMillis())) {
            provider.showInterstitial {
                policy.shown(System.currentTimeMillis())
                next()
            }
        } else next()
    }
}
