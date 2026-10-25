package com.arrowpuzzle.game

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** The ChaiUI rules that can be checked on the colour tokens, in both themes. */
class ChaiThemeTest {
    @After
    fun restoreTheme() { C.dark = true }

    private fun channels(c: Int) = Triple((c shr 16) and 255, (c shr 8) and 255, c and 255)

    private fun luminance(c: Int): Double {
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= .03928) s / 12.92 else ((s + .055) / 1.055).pow(2.4) }
        val (r, g, b) = channels(c)
        return .2126 * lin(r) + .7152 * lin(g) + .0722 * lin(b)
    }

    /** WCAG contrast ratio of two opaque colours. */
    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + .05) / (min(la, lb) + .05)
    }

    private fun bothThemes(block: (String) -> Unit) {
        for (dark in listOf(true, false)) { C.dark = dark; block(if (dark) "dark" else "light") }
    }

    @Test
    fun darkIsTheDefaultAndMatchesTheTokens() {
        assertTrue(C.dark)
        assertEquals(0xFF0A0A0A.toInt(), C.BG)          // oklch(0.145 0 0)
        assertEquals(0xFFFAFAFA.toInt(), C.FG)          // oklch(0.985 0 0)
        assertEquals(0xFFFFEDD5.toInt(), C.HIGHLIGHT)   // orange-100
        C.dark = false
        assertEquals(0xFFFFFFFF.toInt(), C.BG)
        assertEquals(0xFF854D0E.toInt(), C.HIGHLIGHT)   // yellow-800
    }

    @Test
    fun neutralsHaveNoChroma() = bothThemes { theme ->
        val neutrals = mapOf("BG" to C.BG, "FG" to C.FG, "MUTED" to C.MUTED, "SUBTLE" to C.SUBTLE, "FAINT" to C.FAINT,
            "CARD" to C.CARD, "SECONDARY" to C.SECONDARY, "PRIMARY" to C.PRIMARY, "PRIMARY_FG" to C.PRIMARY_FG,
            "SOLID" to C.SOLID, "SOLID_FG" to C.SOLID_FG, "ARROW" to C.ARROW, "DOT" to C.DOT)
        for ((name, c) in neutrals) {
            val (r, g, b) = channels(c)
            assertTrue("$theme $name is not a pure grey: ${Integer.toHexString(c)}", r == g && g == b)
        }
    }

    @Test
    fun orangeIsOnlyTheLogo() = bothThemes { theme ->
        // every token but the brand mark: no saturated orange (hue 15..45 degrees, strong saturation)
        val tokens = mapOf("BG" to C.BG, "FG" to C.FG, "CARD" to C.CARD, "PRIMARY" to C.PRIMARY, "SOLID" to C.SOLID,
            "ARROW" to C.ARROW, "ACCENT" to C.ACCENT, "HIGHLIGHT" to C.HIGHLIGHT, "GREEN" to C.GREEN,
            "YELLOW" to C.YELLOW, "RED" to C.RED, "PURPLE" to C.PURPLE, "HEART" to C.HEART)
        for ((name, c) in tokens) {
            val (r, g, b) = channels(c)
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            val hue = when {
                mx == mn -> 0f
                mx == r -> 60f * (((g - b).toFloat() / (mx - mn)) % 6f)
                mx == g -> 60f * ((b - r).toFloat() / (mx - mn) + 2f)
                else -> 60f * ((r - g).toFloat() / (mx - mn) + 4f)
            }.let { if (it < 0) it + 360f else it }
            assertTrue("$theme $name is an orange fill (hue $hue, sat $sat)", !(hue in 15f..45f && sat > .6f && mx > 180))
        }
    }

    @Test
    fun textAndArrowsAreReadable() = bothThemes { theme ->
        assertTrue("$theme FG", contrast(C.FG, C.BG) >= 15.0)
        assertTrue("$theme MUTED", contrast(C.MUTED, C.BG) >= 4.5)
        assertTrue("$theme HIGHLIGHT", contrast(C.HIGHLIGHT, C.BG) >= 4.5)
        assertTrue("$theme ARROW", contrast(C.ARROW, C.BG) >= 7.0)
        assertTrue("$theme MISTAKE", contrast(C.MISTAKE, C.BG) >= 4.5)
        assertTrue("$theme GREEN", contrast(C.GREEN, C.BG) >= 4.5)
        assertTrue("$theme solid button label", contrast(C.SOLID_FG, C.SOLID) >= 15.0)
        assertTrue("$theme soft button label", contrast(C.PRIMARY_FG, C.PRIMARY) >= 7.0)
        assertTrue("$theme card text", contrast(C.FG, C.CARD) >= 12.0)
    }

    @Test
    fun hintedArrowsStandOutFromRestingOnes() = bothThemes { theme ->
        assertNotEquals(C.ARROW, C.ACCENT)
        val (r1, g1, b1) = channels(C.ARROW); val (r2, g2, b2) = channels(C.ACCENT)
        val dist = sqrt(((r1 - r2) * (r1 - r2) + (g1 - g2) * (g1 - g2) + (b1 - b2) * (b1 - b2)).toDouble())
        assertTrue("$theme hint colour too close to the arrow colour ($dist)", dist >= 40.0)
        assertNotEquals(C.ARROW, C.MISTAKE)
    }

    @Test
    fun cardsAreHairlinesNotFills() = bothThemes { theme ->
        // depth comes from the warm edge, never a shadow; the fill stays faint
        assertTrue("$theme edge alpha", (C.CARD_EDGE ushr 24) in 0x20..0x40)
        assertTrue("$theme hover edge is stronger", (C.CARD_EDGE_HOVER ushr 24) > (C.CARD_EDGE ushr 24))
        assertTrue("$theme fill alpha", (C.CARD_FILL ushr 24) <= 0xB3)
    }
}
