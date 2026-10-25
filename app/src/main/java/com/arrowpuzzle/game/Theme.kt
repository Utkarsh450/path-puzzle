package com.arrowpuzzle.game

/**
 * ChaiUI design tokens (ui.chaicode.com, references/tokens.css) as ARGB colours, for the dark
 * theme (the default) and the light one. Neutrals have no chroma; the only accent is the
 * highlight cream; orange appears only in the wordmark.
 */
object C {
    /** Current theme; GameView sets it from the player's setting. */
    @Volatile var dark = true

    private fun t(dark: Long, light: Long): Int = (if (this.dark) dark else light).toInt()

    // ---- surfaces and text ------------------------------------------------------------
    val BG get() = t(0xFF0A0A0A, 0xFFFFFFFF)               // --background
    val FG get() = t(0xFFFAFAFA, 0xFF0A0A0A)               // --foreground
    val MUTED get() = t(0xFFA3A3A3, 0xFF404040)            // lead text: neutral-400 / neutral-700
    val SUBTLE get() = t(0xFF737373, 0xFF737373)           // neutral-500: captions, inactive tabs
    val FAINT get() = t(0xFF404040, 0xFFD4D4D4)            // neutral-700 / neutral-300: disabled, future days
    val CARD get() = t(0xFF171717, 0xFFFFFFFF)             // --card (dialogs)
    val CARD_FILL get() = t(0x1A000000, 0xB3FFFFFF)        // black/10, white/70
    val CARD_EDGE get() = t(0x26FFF7ED, 0x2678350F)        // orange-50/15, amber-900/15
    val CARD_EDGE_HOVER get() = t(0x4DFFF7ED, 0x4078350F)  // /30
    val BORDER get() = t(0x1AFFFFFF, 0xFFE5E5E5)           // --border
    val INPUT get() = t(0x26FFFFFF, 0xFFE5E5E5)            // --input (switch track off, outline buttons)
    val SECONDARY get() = t(0xFF262626, 0xFFF5F5F5)        // --secondary / --muted
    val PRIMARY get() = t(0xFFE5E5E5, 0xFF171717)          // soft buttons on cards
    val PRIMARY_FG get() = t(0xFF171717, 0xFFFAFAFA)
    val SOLID get() = t(0xFFFFFFFF, 0xFF171717)            // the one solid button per screen
    val SOLID_FG get() = t(0xFF000000, 0xFFFFFFFF)
    val HIGHLIGHT get() = t(0xFFFFEDD5, 0xFF854D0E)        // orange-100 / yellow-800
    const val BLACKOUT = 0x8C000000.toInt()                // dialog backdrop, black/55
    const val BRAND = 0xFFFF7D0C.toInt()                   // logo only

    // ---- meaning: text and thin outlines only -----------------------------------------
    val GREEN get() = t(0xFF4ADE80, 0xFF15803D)
    val YELLOW get() = t(0xFFFACC15, 0xFFA16207)
    val RED get() = t(0xFFF87171, 0xFFDC2626)
    val PURPLE get() = t(0xFFC084FC, 0xFF7E22CE)

    // ---- board --------------------------------------------------------------------------
    val ARROW get() = t(0xFFD4D4D4, 0xFF262626)            // resting arrows
    val ACCENT get() = HIGHLIGHT                           // hinted and moving arrows
    val MOVING get() = HIGHLIGHT
    val MISTAKE get() = RED
    val MISTAKE_GLOW get() = t(0x4DF87171, 0x33DC2626)
    val HINT_GLOW get() = t(0x40FFEDD5, 0x33854D0E)
    val DOT get() = t(0xFF333333, 0xFFD4D4D4)
    val GRID get() = BORDER
    val HEART get() = RED

    // ---- names kept from the first palette, mapped onto the tokens -------------------
    val BG_TOP get() = BG
    val BG_BOTTOM get() = BG
    val TEXT get() = FG
    val TEXT_GREY get() = MUTED
    val PILL get() = SECONDARY
    val PILL_TEXT get() = FG
    val GREY3 get() = FAINT
    val HEART_OFF get() = FAINT
    val GOLD get() = HIGHLIGHT
    val ORANGE get() = HIGHLIGHT
    val ERASER get() = HIGHLIGHT
    val SPARK_A get() = HIGHLIGHT
    val SPARK_B get() = FG
    val TRAIL_A get() = HIGHLIGHT
    val TRAIL_B get() = FG

    /** Portal pair colours (special mechanics, switched off): neutral rims, cream glow. */
    val PORTAL_RIM get() = intArrayOf(FG, HIGHLIGHT, MUTED)
    val PORTAL_GLOW get() = intArrayOf(HINT_GLOW, HINT_GLOW, HINT_GLOW)

    /** Win-screen specks: white and cream, nothing loud. */
    val CONFETTI get() = intArrayOf(FG, HIGHLIGHT, MUTED, FG)
}
