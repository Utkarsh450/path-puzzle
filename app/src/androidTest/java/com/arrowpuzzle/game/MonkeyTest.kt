package com.arrowpuzzle.game

import android.graphics.RectF
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import java.util.Random

/**
 * A monkey that only ever touches the game: random taps, swipes, double taps, taps on real buttons
 * and the back key, injected into the game's own window (never when it lacks focus, so it cannot
 * wander into another app). A crash, a hang or a broken state fails the run. Runs on test-only
 * preferences and without ads.
 */
@Suppress("DEPRECATION")
class MonkeyTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private var act: MainActivity? = null
    private var view: GameView? = null
    private val ctx get() = instrumentation.targetContext
    private var playerSave: Map<String, *> = emptyMap<String, Any>()
    private fun readPlayerSave() = ctx.getSharedPreferences("arrowpuzzle.v2", android.content.Context.MODE_PRIVATE).all.toMap()

    override fun setUp() {
        super.setUp()
        playerSave = readPlayerSave()
        UiPrefs.isolate(ctx)
        AdsConfig.provider = { NoAds }
    }

    override fun tearDown() {
        val a = act
        if (a != null) { main { if (!a.isFinishing) a.finish() }; waitUntil("activity destroyed") { main { a.isDestroyed } }; act = null; setActivity(null) }
        AdsConfig.reset()
        UiPrefs.release(ctx)
        C.dark = true
        try {
            assertEquals("the player's save changed during a monkey test", playerSave, readPlayerSave())
        } finally {
            super.tearDown()
        }
    }

    private fun <T> main(block: () -> T): T = instrumentation.onMain(block)

    private fun open(): GameView {
        val a = activity
        act = a
        main { a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val v = main { a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as GameView }
        waitUntil("window focus") { main { a.hasWindowFocus() } }
        waitUntil("first frame") { main { v.ui.hits.isNotEmpty() } }
        view = v
        return v
    }

    private fun launch(setup: Store.() -> Unit = {}): GameView { UiPrefs.reset(ctx, setup); return open() }

    private fun relaunch(): GameView {
        val a = act
        if (a != null) { main { if (!a.isFinishing) a.finish() }; waitUntil("activity destroyed") { main { a.isDestroyed } }; act = null; setActivity(null) }
        return open()
    }

    private fun tap(v: GameView, x: Float, y: Float) {
        val loc = IntArray(2); main { v.getLocationOnScreen(loc) }
        val t = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, loc[0] + x, loc[1] + y, 0)
        val u = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, loc[0] + x, loc[1] + y, 0)
        inject(d); inject(u)
        d.recycle(); u.recycle()
    }

    private fun swipe(v: GameView, x0: Float, y0: Float, x1: Float, y1: Float) {
        val loc = IntArray(2); main { v.getLocationOnScreen(loc) }
        val t = SystemClock.uptimeMillis()
        val steps = 6
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, loc[0] + x0, loc[1] + y0, 0)
        inject(d); d.recycle()
        for (i in 1..steps) {
            val m = MotionEvent.obtain(t, t + i * 20L, MotionEvent.ACTION_MOVE, loc[0] + x0 + (x1 - x0) * i / steps, loc[1] + y0 + (y1 - y0) * i / steps, 0)
            inject(m); m.recycle()
        }
        val u = MotionEvent.obtain(t, t + 160, MotionEvent.ACTION_UP, loc[0] + x1, loc[1] + y1, 0)
        inject(u); u.recycle()
    }

    /** Away from the screen edges, where Android's own gestures (home, back) would take over. */
    private fun point(v: GameView, rnd: Random): Pair<Float, Float> =
        (120f + rnd.nextFloat() * (v.width - 240f)) to (170f + rnd.nextFloat() * (v.height - 460f))

    private fun buttonCentre(v: GameView, rnd: Random): Pair<Float, Float>? = main {
        val hits = v.ui.hits.toList()
        if (hits.isEmpty()) null else hits[rnd.nextInt(hits.size)].r.let { it.centerX() to it.centerY() }
    }

    private var actions = 0
    private var skipped = 0

    /** Android refuses to inject a touch that would land on a window the game does not own (an edge
     *  panel, a system bar): that is its safety net, so skip the touch and carry on. */
    private fun inject(e: MotionEvent) {
        try { instrumentation.sendPointerSync(e) } catch (x: IllegalArgumentException) { skipped++ }
    }

    private fun abuse(seconds: Int, seed: Long, backPercent: Int = 4) {
        val rnd = Random(seed)
        val end = SystemClock.uptimeMillis() + seconds * 1000L
        actions = 0; skipped = 0
        var v = view!!
        while (SystemClock.uptimeMillis() < end) {
            val a = act!!
            if (a.isFinishing || a.isDestroyed) v = relaunch()          // backed out of the app: start it again
            waitUntil("window focus (the monkey never injects without it)") { main { act!!.hasWindowFocus() } }
            when (rnd.nextInt(10)) {
                0, 1, 2 -> { val p = point(v, rnd); tap(v, p.first, p.second) }
                3, 4, 5, 6 -> { val p = buttonCentre(v, rnd) ?: point(v, rnd); tap(v, p.first, p.second) }
                7 -> { val p = point(v, rnd); val q = point(v, rnd); swipe(v, p.first, p.second, q.first, q.second) }
                8 -> { val p = point(v, rnd); tap(v, p.first, p.second); tap(v, p.first + rnd.nextInt(9) - 4, p.second + rnd.nextInt(9) - 4) }
                else -> if (rnd.nextInt(100) < backPercent * 10) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        else { val p = point(v, rnd); tap(v, p.first, p.second) }
            }
            actions++
            if (actions % 25 == 0) main {
                val g = v.game.state
                if (v.inGame && g.board != null) {
                    assertTrue("lives ${g.lives}", g.lives in 0..3)
                    assertTrue("remaining ${g.remaining}", g.remaining in 0..g.total)
                }
            }
            SystemClock.sleep(rnd.nextInt(30).toLong())
        }
        android.util.Log.i("PathPuzzle", "monkey: $actions actions in $seconds s without a problem (seed $seed, $skipped touches skipped by Android)")
    }

    fun testMonkeyInTheMenusAndPopups() {
        launch { coins = 500 }
        abuse(100, 1, backPercent = 3)
    }

    fun testMonkeyInTheMainGame() {
        val v = launch { level = 6; coins = 800 }
        waitForGame(v, "m_play")
        abuse(130, 2, backPercent = 2)
    }

    fun testMonkeyInTimeRush() {
        val v = launch { coins = 300 }
        waitForGame(v, "m_rush")
        abuse(110, 3, backPercent = 2)
    }

    fun testMonkeyInADuel() {
        val v = launch { coins = 300 }
        waitForGame(v, "m_duel")
        abuse(110, 4, backPercent = 2)
    }

    /** Taps a menu card, then closes the rules popup so the monkey starts in the game. */
    private fun waitForGame(v: GameView, id: String) {
        var r: RectF? = null
        waitUntil("button $id") { r = main { v.ui.hits.lastOrNull { it.id == id }?.r?.let(::RectF) }; r != null }
        tap(v, r!!.centerX(), r!!.centerY())
        waitUntil("game or rules popup", 15_000) { main { v.inGame } }
    }
}

