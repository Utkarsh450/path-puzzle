package com.arrowpuzzle.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EaseTest {
    private val eps = 1e-5f

    @Test
    fun curvesStartAtZeroAndEndAtOne() {
        for (f in listOf(Ease::outQuad, Ease::outCubic, Ease::inOutCubic, Ease::outBack)) {
            assertEquals(0f, f(0f), eps)
            assertEquals(1f, f(1f), eps)
        }
    }

    @Test
    fun easeOutCurvesAreMonotonic() {
        for (f in listOf(Ease::outQuad, Ease::outCubic, Ease::inOutCubic)) {
            var prev = f(0f)
            for (i in 1..100) {
                val v = f(i / 100f)
                assertTrue("not increasing at ${i / 100f}", v >= prev - eps)
                prev = v
            }
        }
    }

    @Test
    fun inOutCubicIsSymmetric() {
        assertEquals(.5f, Ease.inOutCubic(.5f), eps)
        for (i in 0..50) {
            val t = i / 100f
            assertEquals(1f, Ease.inOutCubic(t) + Ease.inOutCubic(1f - t), 1e-4f)
        }
    }

    @Test
    fun outBackOvershoots() {
        val peak = (1..99).maxOf { Ease.outBack(it / 100f) }
        assertTrue("outBack should overshoot past 1, peak $peak", peak > 1.05f)
    }

    @Test
    fun clamp01() {
        assertEquals(0f, Ease.clamp01(-3f), 0f)
        assertEquals(.25f, Ease.clamp01(.25f), 0f)
        assertEquals(1f, Ease.clamp01(7f), 0f)
    }
}
