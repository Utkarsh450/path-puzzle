package com.arrowpuzzle.game

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Constant tables, colour maths and the particle system (paint calls are stubbed on the JVM). */
class TablesAndEffectsTest {
    @Test
    fun mechanicTablesMatchTheNativeCore() {
        // kUnlockLevel and kMechanicsEnabled in puzzle_core.cpp
        assertArrayEquals(intArrayOf(15, 25, 35, 45, 55, 65), Mechanic.unlockLevels)
        assertEquals(6, Mechanic.values().size)
        assertFalse(Mechanic.ENABLED)
        // Outcome order must match puzzle::Outcome
        assertEquals(listOf("EXIT", "BLOCKED", "STOPPED", "NOT_TAPPABLE", "LOOP"), Outcome.values().map { it.name })
    }

    @Test
    fun boostersUnlockInOrderAndHaveUniqueKeys() {
        assertEquals(Booster.values().size, Booster.values().map { it.key }.toSet().size)
        assertEquals(1, Booster.HINT.unlockLevel)
        assertTrue(Booster.ERASER.unlockLevel < Booster.WAND.unlockLevel)
        assertEquals(0, Booster.GRID.cost)
        for (b in listOf(Booster.HINT, Booster.ERASER, Booster.WAND)) assertTrue(b.cost > 0)
    }

    @Test
    fun lerpColorBlendsChannelsAndClamps() {
        val a = 0xFF000000.toInt(); val b = 0xFFFFFFFF.toInt()
        assertEquals(a, BoardRenderer.lerpColor(a, b, 0f))
        assertEquals(b, BoardRenderer.lerpColor(a, b, 1f))
        assertEquals(0xFF7F7F7F.toInt(), BoardRenderer.lerpColor(a, b, .5f))
        assertEquals(b, BoardRenderer.lerpColor(a, b, 4f))
        assertEquals(a, BoardRenderer.lerpColor(a, b, -1f))
        assertEquals(C.MISTAKE, BoardRenderer.lerpColor(C.ARROW, C.MISTAKE, 1f))
    }

    @Test
    fun themeColoursAreOpaque() {
        for (c in listOf(C.BG_TOP, C.BG_BOTTOM, C.ARROW, C.ACCENT, C.MOVING, C.MISTAKE, C.HEART, C.TEXT))
            assertEquals(0xFF, c ushr 24)
    }

    @Test
    fun particlesMoveFadeAndExpire() {
        val ps = Particles()
        ps.add(Particle(0f, 0f, 10f, 0f, .5f, .5f, 1f, 0, PKind.DOT, gravity = 20f))
        ps.update(.1f)
        val p = ps.list.single()
        assertTrue(p.x > 0f)
        assertTrue("gravity pulls down", p.vy > 0f)
        assertEquals(.4f, p.life, 1e-5f)
        ps.update(1f)
        assertFalse(ps.active)
    }

    @Test
    fun particleBurstIsCapped() {
        val ps = Particles()
        ps.burst(0f, 0f, 1000, 1f, 1f, intArrayOf(1, 2, 3))
        assertEquals(600, ps.list.size)
        assertTrue(ps.list.all { it.color in 1..3 })
    }
}
