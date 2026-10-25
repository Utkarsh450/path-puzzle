package com.arrowpuzzle.game

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock

/** Redirects every SharedPreferences file to an `itest_` copy so tests never touch real progress. */
class TestPrefsContext(base: Context) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences("itest_$name", mode)

    fun wipe() {
        super.getSharedPreferences("itest_arrowpuzzle.v2", Context.MODE_PRIVATE).edit().clear().commit()
        deleteSharedPreferences("itest_arrowpuzzle.v2")
    }
}

/**
 * UI tests drive the real activity in the app's own process. Pointing [Store.fileName] at a
 * test-only file before the activity starts means the player's save is never read or written,
 * not even by an animation or a background load that outlives a test.
 */
object UiPrefs {
    const val FILE = "itest_ui_arrowpuzzle.v2"
    private const val PLAYER_FILE = "arrowpuzzle.v2"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isolate(c: Context) {
        Store.fileName = FILE
        prefs(c).edit().clear().commit()
    }

    fun release(c: Context) {
        prefs(c).edit().clear().commit()
        c.deleteSharedPreferences(FILE)
        Store.fileName = PLAYER_FILE
    }

    /** Fresh progress for a test: no tutorials, no sound or vibration. */
    fun reset(c: Context, setup: Store.() -> Unit = {}) {
        check(Store.fileName == FILE) { "UI test prefs are not isolated" }
        prefs(c).edit().clear().commit()
        val s = Store(c)
        s.sound = false
        s.vibration = false
        s.markSeen("tut_tap")
        s.markSeen("tut_zoom")
        s.setup()
    }
}

/** Runs [block] on the main thread and returns its result. */
fun <T> Instrumentation.onMain(block: () -> T): T {
    var out: Result<T>? = null
    runOnMainSync { out = runCatching(block) }
    return out!!.getOrThrow()
}

/** Polls [cond] until it holds or [timeoutMs] passes. */
fun waitUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
    val end = SystemClock.uptimeMillis() + timeoutMs
    while (!cond()) {
        if (SystemClock.uptimeMillis() > end) throw AssertionError("timed out waiting for $what")
        SystemClock.sleep(30)
    }
}

/** Reads a private field (camera, overlay list) of the app's screen objects. */
@Suppress("UNCHECKED_CAST")
fun <T> Any.field(name: String): T {
    var cls: Class<*>? = javaClass
    while (cls != null) {
        try {
            val f = cls.getDeclaredField(name)
            f.isAccessible = true
            return f.get(this) as T
        } catch (_: NoSuchFieldException) {
            cls = cls.superclass
        }
    }
    throw NoSuchFieldException(name)
}

/** Calls a private no-argument getter such as `getIntroZoomEnd`. */
@Suppress("UNCHECKED_CAST")
fun <T> Any.call(method: String): T {
    val m = javaClass.getDeclaredMethod(method)
    m.isAccessible = true
    return m.invoke(this) as T
}

/** Kotlin re-implementation of the exit rule, used to cross-check the native core. */
object Geometry {
    fun occupancy(b: Board): IntArray {
        val occ = IntArray(b.cols * b.rows) { -1 }
        for (id in 0 until b.arrowCount) {
            val a = b.arrow(id)
            if (!a.alive) continue
            for (p in a.pts) occ[p.y * b.cols + p.x] = id
        }
        return occ
    }

    /** True when nothing (its own body included) lies between the head and the board edge. */
    fun rayClear(b: Board, occ: IntArray, id: Int): Boolean {
        val a = b.arrow(id)
        var x = a.pts.last().x + a.dx
        var y = a.pts.last().y + a.dy
        while (x in 0 until b.cols && y in 0 until b.rows) {
            if (occ[y * b.cols + x] != -1) return false
            x += a.dx; y += a.dy
        }
        return true
    }

    fun freeAtStart(b: Board): List<Int> {
        val occ = occupancy(b)
        return (0 until b.arrowCount).filter { b.arrow(it).alive && rayClear(b, occ, it) }
    }

    fun blockedArrow(b: Board): Int {
        val occ = occupancy(b)
        return (0 until b.arrowCount).first { b.arrow(it).alive && !rayClear(b, occ, it) }
    }

    /** Clears the board by following hints; returns the number of taps, or -1 if a hint fails. */
    fun playByHints(b: Board, tap: (Int, Boolean) -> Boolean = { id, rev -> b.tap(id, rev).success }): Int {
        var moves = 0
        while (b.remaining() > 0) {
            val h = b.hint() ?: return -1
            if (!tap(h.first, h.second)) return -1
            if (++moves > 5000) return -1
        }
        return moves
    }
}

/** An ad network that shows nothing but counts what it was asked: ads are instant and synchronous. */
class FakeAds(var rewarded: Boolean = true, var interstitial: Boolean = true, var watchThrough: Boolean = true, var reload: Boolean = false) : AdProvider {
    var rewardedShown = 0
    var interstitialShown = 0
    override val rewardedReady get() = rewarded
    override val interstitialReady get() = interstitial
    override fun showRewarded(onReward: () -> Unit, onDone: () -> Unit) {
        rewardedShown++; rewarded = reload
        if (watchThrough) onReward()
        onDone()
    }
    override fun showInterstitial(onDone: () -> Unit) { interstitialShown++; interstitial = false; onDone() }
    override val privacyOptionsRequired = false
    override fun showPrivacyOptions() {}
}
