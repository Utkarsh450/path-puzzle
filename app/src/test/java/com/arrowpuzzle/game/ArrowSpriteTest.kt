package com.arrowpuzzle.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Motion maths of [ArrowSprite]: the track an arrow slides along and the body window drawn on it. */
class ArrowSpriteTest {
    private val eps = 1e-4f

    // L-shaped arrow: (0,0) -> (1,0) -> (2,0) -> (2,1), heading down.
    private fun lArrow(alive: Boolean = true) = ArrowInfo(
        alive, 0, 1, -1, 0, false, -1, -1, -1,
        listOf(Pt(0, 0, false), Pt(1, 0, false), Pt(2, 0, false), Pt(2, 1, false))
    )

    @Test
    fun deadArrowsStartGone() {
        assertEquals(SpriteState.IDLE, ArrowSprite(0, lArrow()).state)
        assertEquals(SpriteState.GONE, ArrowSprite(0, lArrow(alive = false)).state)
    }

    @Test
    fun exitTrackRunsOffTheBoardInTheHeading() {
        val s = ArrowSprite(0, lArrow())
        s.prepareMotion(listOf(Pt(2, 2, false), Pt(2, 3, false)), reverse = false, exit = true, dx = 0, dy = 1, far = 10)
        assertEquals(7, s.ex.size)             // 4 body + 2 track + 1 far point
        assertEquals(3f, s.bodyArc, eps)       // three unit segments in the body
        assertEquals(2f, s.ex.last(), eps)
        assertEquals(13f, s.ey.last(), eps)    // (2,3) + 10 cells down
        assertEquals(15f, s.cum.last(), eps)
        assertEquals(0f, s.s, 0f)
    }

    @Test
    fun reverseMotionStartsFromTheTail() {
        val s = ArrowSprite(0, lArrow())
        s.prepareMotion(emptyList(), reverse = true, exit = false, dx = 0, dy = 0, far = 0)
        assertEquals(2f, s.ex[0], eps); assertEquals(1f, s.ey[0], eps)
        assertEquals(0f, s.ex.last(), eps); assertEquals(0f, s.ey.last(), eps)
    }

    @Test
    fun windowAtRestIsTheWholeBody() {
        val s = ArrowSprite(0, lArrow())
        s.prepareMotion(emptyList(), false, false, 0, 0, 0)
        val ox = FloatArray(32); val oy = FloatArray(32); val brk = BooleanArray(32)
        val n = s.window(0f, s.bodyArc, ox, oy, brk)
        assertEquals(4, n)
        assertTrue(brk[0])
        assertEquals(2f, ox[n - 1], eps); assertEquals(1f, oy[n - 1], eps)
        assertEquals(0f, s.headDx, eps); assertEquals(1f, s.headDy, eps)
    }

    @Test
    fun windowSlidesAlongTheTrack() {
        val s = ArrowSprite(0, lArrow())
        s.prepareMotion(listOf(Pt(2, 2, false)), false, true, 0, 1, 5)
        val ox = FloatArray(32); val oy = FloatArray(32); val brk = BooleanArray(32)
        val n = s.window(1.5f, 1.5f + s.bodyArc, ox, oy, brk)
        // tail is halfway between (1,0) and (2,0); head reached (2,2) + 0.5
        assertEquals(1.5f, ox[0], eps); assertEquals(0f, oy[0], eps)
        assertEquals(2f, ox[n - 1], eps); assertEquals(2.5f, oy[n - 1], eps)
    }

    @Test
    fun headPointFollowsTheOffset() {
        val s = ArrowSprite(0, lArrow())
        s.prepareMotion(listOf(Pt(2, 2, false)), false, true, 0, 1, 5)
        val out = FloatArray(2)
        s.headPoint(out)
        assertEquals(2f, out[0], eps); assertEquals(1f, out[1], eps)
        s.s = 2f
        s.headPoint(out)
        assertEquals(2f, out[0], eps); assertEquals(3f, out[1], eps)
        s.s = 1000f
        s.headPoint(out)
        assertEquals(s.ex.last(), out[0], eps); assertEquals(s.ey.last(), out[1], eps)
    }

    @Test
    fun portalJumpsAddNoDistance() {
        val s = ArrowSprite(0, lArrow())
        s.prepareMotion(listOf(Pt(2, 2, false), Pt(7, 7, true)), false, false, 0, 0, 0)
        assertTrue(s.ej[5])
        assertEquals(s.cum[4], s.cum[5], eps)
        val ox = FloatArray(32); val oy = FloatArray(32); val brk = BooleanArray(32)
        val n = s.window(0f, s.cum.last() + 1f, ox, oy, brk)
        assertTrue("a jump starts a new sub-path", (1 until n).any { brk[it] })
    }

    @Test
    fun bumpGoesOutAndComesBack() {
        val s = ArrowSprite(0, lArrow())
        s.bumpDist = 2f
        s.t = 0f
        assertEquals(0f, s.bumpOffset(), eps)
        s.t = .1f + .02f * 2f
        assertEquals(2f, s.bumpOffset(), 1e-3f)
        s.t = 5f
        assertEquals(0f, s.bumpOffset(), eps)
        assertFalse(s.bumpOffset() < 0f)
    }
}
