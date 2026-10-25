package com.arrowpuzzle.game

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

/** Main menu with the Main / Daily / Me tab bar, in the ChaiUI layout. */
class MenuScreen(private val v: GameView) {
    enum class Tab { MAIN, DAILY, ME }

    private val ui get() = v.ui
    private val store get() = v.store
    var tab = Tab.MAIN
    private var w = 0f
    private var h = 0f
    private var time = 0f
    private var tabAge = 9f

    private var month: YearMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()

    private val pad get() = ui.dp(24f)

    fun layout(width: Float, height: Float) { w = width; h = height }

    fun select(t: Tab) { if (tab != t) { tab = t; tabAge = 0f } }

    val needsFrame get() = tabAge < .4f

    private fun ymd(d: LocalDate) = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth

    fun draw(c: Canvas, dt: Float) {
        time += dt; tabAge += dt
        val barH = ui.dp(64f) + v.insetBottom
        val a = if (Motion.reduced) 255 else (255 * Ease.clamp01(tabAge / .25f)).toInt()
        c.saveLayerAlpha(0f, 0f, w, h - barH, a)
        when (tab) {
            Tab.MAIN -> drawMain(c, h - barH)
            Tab.DAILY -> drawDaily(c, h - barH)
            Tab.ME -> drawMe(c)
        }
        c.restore()
        drawTabBar(c, barH)
    }

    // ---- Main --------------------------------------------------------------------

    /** Navbar: wordmark, then coins, the theme switcher and settings. */
    private fun drawNavbar(c: Canvas, cy: Float) {
        ui.logoTile(c, pad + ui.dp(15f), cy, ui.dp(30f))
        ui.text(c, "Path Puzzle", pad + ui.dp(38f), cy + ui.dp(7f), ui.dp(20f), C.FG, Paint.Align.LEFT, ui.brand)
        val gx = w - pad - ui.dp(10f)
        ui.iconGear(c, gx, cy, ui.dp(22f), C.FG)
        ui.hit("m_settings", RectF(gx - ui.dp(24f), cy - ui.dp(24f), w, cy + ui.dp(24f))) { v.showSettings(false) }
        val tx = gx - ui.dp(44f)
        ui.iconTheme(c, tx, cy, ui.dp(22f), C.FG)
        ui.hit("m_theme", RectF(tx - ui.dp(22f), cy - ui.dp(24f), tx + ui.dp(20f), cy + ui.dp(24f))) {
            v.setDarkTheme(!store.darkTheme)
        }
        coinPill(c, tx - ui.dp(26f), cy)
    }

    private fun card(c: Canvas, id: String, r: RectF, title: String, caption: String, done: Boolean,
                     icon: (Float, Float, Float) -> Unit, action: () -> Unit) {
        val pressed = ui.pressed(id)
        ui.card(c, r, pressed)
        val ip = ui.dp(16f)
        icon(r.left + ip + ui.dp(12f), r.top + ip + ui.dp(12f), ui.dp(26f))
        ui.text(c, title, r.left + ip, r.top + ui.dp(68f), ui.dp(16f), C.FG, Paint.Align.LEFT, ui.displayBold)
        ui.text(c, caption, r.left + ip, r.top + ui.dp(88f), ui.dp(13.5f), C.MUTED, Paint.Align.LEFT, ui.medium)
        val by = r.bottom - ui.dp(14f) - ui.dp(32f)
        if (r.height() < ui.dp(146f)) {
            if (done) ui.iconCheck(c, r.right - ip - ui.dp(8f), r.top + ip + ui.dp(12f), ui.dp(14f), C.GREEN)
        } else if (done) {
            ui.iconCheck(c, r.left + ip + ui.dp(8f), by + ui.dp(17f), ui.dp(14f), C.GREEN)
            ui.text(c, "Completed", r.left + ip + ui.dp(22f), by + ui.dp(22f), ui.dp(14f), C.GREEN, Paint.Align.LEFT, ui.medium)
        } else {
            ui.softButton(c, RectF(r.left + ip, by, r.left + ip + ui.dp(84f), by + ui.dp(32f)), "Play", pressed)
        }
        ui.hit(id, r, action)
    }

    private fun drawMain(c: Canvas, bottom: Float) {
        val top = v.insetTop + ui.dp(10f)
        drawNavbar(c, top + ui.dp(22f))

        // hero: one central element, the level you are on
        val cw = w - pad * 2
        var y = top + ui.dp(72f)
        val diff = Levels.difficulty(store.level)
        ui.chip(c, pad + ui.textW("Level ${store.level}, $diff", ui.dp(13f), ui.medium) / 2 + ui.dp(10f), y,
            "Level ${store.level}, $diff", C.FG, edge = C.CARD_EDGE_HOVER)
        y += ui.dp(30f)
        y += ui.paragraph(c, "Clear every arrow", null, pad, y, cw, ui.dp(40f), C.FG, center = false, face = ui.bold)
        y += ui.dp(14f)
        y += ui.paragraph(c, "Tap an arrow to slide it off the board. Find the one clear path before your three hearts run out.",
            "the one clear path", pad, y, cw, ui.dp(16.5f), C.MUTED, center = false)
        y += ui.dp(26f)
        val resume = store.savedMode == GameMode.CLASSIC.key && store.savedLevel == store.level && store.savedSalt == 0 &&
            store.savedMoves.isNotEmpty()
        ui.primaryButton(c, "m_play", RectF(pad, y, w - pad, y + ui.dp(54f)), if (resume) "Continue game" else "New game") {
            v.startGame(store.level, 0, resume)
        }
        y += ui.dp(54f) + ui.dp(40f)

        // modes: a two-column grid of cards; with an odd count the first one spans the row
        ui.text(c, "Modes", pad, y, ui.dp(22f), C.FG, Paint.Align.LEFT, ui.medium)
        y += ui.dp(18f)
        val cards = modeCards()
        val gap = ui.dp(12f)
        val cardW = (cw - gap) / 2f
        val wide = cards.size % 2
        val rowsN = (cards.size + 1) / 2
        // short screens get compact cards (the whole card is the button) rather than running off the page
        val cardH = min(ui.dp(170f), (bottom - y - ui.dp(16f) - (rowsN - 1) * gap) / rowsN).coerceAtLeast(ui.dp(104f))
        for ((i, m) in cards.withIndex()) {
            val slot = if (i < wide) 0 else i + wide
            val x0 = if (i < wide) pad else pad + (slot % 2) * (cardW + gap)
            val y0 = y + (slot / 2) * (cardH + gap)
            val x1 = if (i < wide) w - pad else x0 + cardW
            card(c, m.id, RectF(x0, y0, x1, y0 + cardH), m.title, m.caption, m.done, { x, yy, s -> m.icon(c, x, yy, s) }, m.action)
        }
    }

    private class ModeCard(val id: String, val title: String, val caption: String, val done: Boolean,
                           val icon: (Canvas, Float, Float, Float) -> Unit, val action: () -> Unit)

    private fun modeCards(): List<ModeCard> {
        val today = LocalDate.now()
        val todayKey = ymd(today)
        val dailyDone = store.dailyDone(todayKey)
        return listOf(
            ModeCard("m_daily", "Daily challenge", today.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + today.dayOfMonth,
                dailyDone, { cc, x, yy, s -> ui.calendar3d(cc, x, yy, s) }) {
                if (dailyDone) select(Tab.DAILY)
                else v.startGame(Levels.dailyLevel(todayKey), todayKey, store.savedMode == GameMode.CLASSIC.key && store.savedSalt == todayKey)
            },
            ModeCard("m_rush", "Time rush", if (store.rushBest > 0) "Best ${store.rushBest}" else "60 seconds", false,
                { cc, x, yy, s -> ui.iconStopwatch(cc, x, yy, s, C.FG) }) { v.startRush() },
            ModeCard("m_duel", "Duel", "Two players, one phone", false,
                { cc, x, yy, s -> ui.iconDuel(cc, x, yy, s, C.FG) }) { v.startDuel() }
        )
    }

    private fun coinPillWidth() = ui.dp(40f) + ui.textW(store.coins.toString(), ui.dp(14f), ui.medium)

    /** Coins chip, right-aligned at [right]. */
    fun coinPill(c: Canvas, right: Float, cy: Float) {
        val label = store.coins.toString()
        val pw = coinPillWidth()
        val r = RectF(right - pw, cy - ui.dp(15f), right, cy + ui.dp(15f))
        c.drawRoundRect(r, ui.dp(15f), ui.dp(15f), ui.strokePaint(C.CARD_EDGE_HOVER, ui.dp(1f)))
        ui.coin(c, r.left + ui.dp(15f), cy, ui.dp(7f))
        ui.text(c, label, r.left + ui.dp(28f), cy + ui.dp(5f), ui.dp(14f), C.FG, Paint.Align.LEFT, ui.medium)
    }

    // ---- Daily -------------------------------------------------------------------

    private fun drawDaily(c: Canvas, bottom: Float) {
        val today = LocalDate.now()
        val top = v.insetTop
        var y = top + ui.dp(48f)
        ui.text(c, "Daily challenges", w / 2, y, ui.dp(26f), C.FG, face = ui.medium)
        y += ui.dp(16f)
        y += ui.paragraph(c, "One new board every day. Finish a whole month to earn its trophy.", "a whole month",
            pad, y, w - pad * 2, ui.dp(15f), C.MUTED)
        y += ui.dp(18f)

        // month card: trophy, month name, progress, arrows to change month
        val doneCount = store.dailyCount(month.year * 100 + month.monthValue)
        val days = month.lengthOfMonth()
        val mc = RectF(pad, y, w - pad, y + ui.dp(88f))
        ui.card(c, mc)
        ui.iconChevron(c, mc.left + ui.dp(22f), mc.centerY(), ui.dp(18f), C.MUTED, true)
        ui.hit("d_prev", RectF(mc.left, mc.top, mc.left + ui.dp(56f), mc.bottom)) {
            month = month.minusMonths(1); selected = month.atDay(1)
        }
        if (month.isBefore(YearMonth.from(today))) {
            ui.iconChevron(c, mc.right - ui.dp(22f), mc.centerY(), ui.dp(18f), C.MUTED, false)
            ui.hit("d_next", RectF(mc.right - ui.dp(56f), mc.top, mc.right, mc.bottom)) {
                month = month.plusMonths(1)
                selected = if (YearMonth.from(today) == month) today else month.atDay(1)
            }
        }
        val complete = doneCount >= days
        ui.bigTrophy(c, mc.left + ui.dp(76f), mc.centerY(), ui.dp(48f), complete)
        val monthName = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year
        ui.text(c, monthName, mc.left + ui.dp(112f), mc.centerY() - ui.dp(2f), ui.dp(18f), C.FG, Paint.Align.LEFT, ui.medium)
        ui.text(c, "$doneCount of $days days", mc.left + ui.dp(112f), mc.centerY() + ui.dp(20f), ui.dp(13.5f),
            if (complete) C.GREEN else C.MUTED, Paint.Align.LEFT, ui.medium)

        // calendar
        y = mc.bottom + ui.dp(30f)
        val gridW = min(w - pad * 2, ui.dp(400f))
        val x0 = w / 2 - gridW / 2
        val cell = gridW / 7f
        val names = arrayOf("S", "M", "T", "W", "T", "F", "S")
        for (i in 0..6) ui.text(c, names[i], x0 + cell * (i + .5f), y, ui.dp(12.5f), C.SUBTLE, face = ui.medium)
        y += ui.dp(10f)
        val offset = month.atDay(1).dayOfWeek.value % 7
        val rowH = min(cell, ui.dp(46f))
        for (d in 1..days) {
            val date = month.atDay(d)
            val idx = offset + d - 1
            val cx = x0 + cell * (idx % 7 + .5f)
            val cy = y + rowH * (idx / 7 + .5f)
            val r = rowH * .4f
            val future = date.isAfter(today)
            val isDone = store.dailyDone(ymd(date))
            when {
                date == selected -> {
                    c.drawCircle(cx, cy, r, ui.fillPaint(C.FG))
                    if (isDone) ui.iconCheck(c, cx, cy, r, C.BG)
                    else ui.text(c, d.toString(), cx, cy + ui.dp(5.5f), ui.dp(16f), C.BG)
                }
                isDone -> {
                    c.drawCircle(cx, cy, r, ui.strokePaint(C.GREEN, ui.dp(1f)))
                    ui.text(c, d.toString(), cx, cy + ui.dp(5.5f), ui.dp(16f), C.GREEN, face = ui.medium)
                }
                else -> ui.text(c, d.toString(), cx, cy + ui.dp(5.5f), ui.dp(16f), if (future) C.FAINT else C.FG, face = ui.regular)
            }
            if (!future) ui.hit("d_$d", RectF(cx - cell / 2, cy - rowH / 2, cx + cell / 2, cy + rowH / 2)) { selected = date; v.sfx.play(Sound.TAP) }
        }

        val btnTop = bottom - ui.dp(84f)
        val key = ymd(selected)
        val selDone = store.dailyDone(key)
        ui.primaryButton(c, "d_play", RectF(pad, btnTop, w - pad, btnTop + ui.dp(54f)),
            if (selDone) "Completed" else "Play", null, !selDone) {
            v.startGame(Levels.dailyLevel(key), key, store.savedSalt == key)
        }
    }

    // ---- Me ----------------------------------------------------------------------

    private fun drawMe(c: Canvas) {
        val top = v.insetTop
        var y = top + ui.dp(48f)
        ui.text(c, "Me", w / 2, y, ui.dp(26f), C.FG, face = ui.medium)
        y += ui.dp(28f)
        bigRow(c, "me_awards", RectF(pad, y, w - pad, y + ui.dp(80f)), "Awards", "Monthly trophies and daily stars",
            { cx, cy, s -> ui.cup(c, cx, cy, s * .7f, C.FG) }) { v.showAwards() }
        y += ui.dp(92f)
        bigRow(c, "me_stats", RectF(pad, y, w - pad, y + ui.dp(80f)), "Statistics", "${store.levelsCompleted} levels completed",
            { cx, cy, s -> ui.iconChart(c, cx, cy, s * .5f, C.FG) }) { v.showStatistics() }
        y += ui.dp(104f)
        val rows = listOf(
            Triple("Settings", 0) { v.showSettings(false) },
            Triple("How to play", 1) { v.showHowToPlay() },
            Triple("About the game", 2) { v.showAbout() }
        )
        val rh = ui.dp(58f)
        val group = RectF(pad, y, w - pad, y + rh * rows.size)
        ui.card(c, group)
        for ((i, r) in rows.withIndex()) {
            val ry = y + rh * i
            val row = RectF(pad, ry, w - pad, ry + rh)
            if (ui.pressed("me_$i")) c.drawRect(row.left + ui.dp(1f), row.top + ui.dp(1f), row.right - ui.dp(1f), row.bottom - ui.dp(1f), ui.fillPaint(C.SECONDARY, 160))
            val ix = pad + ui.dp(32f)
            when (r.second) {
                0 -> ui.iconGear(c, ix, ry + rh / 2, ui.dp(20f), C.FG)
                1 -> ui.iconHelp(c, ix, ry + rh / 2, ui.dp(22f), C.FG)
                else -> ui.iconInfo(c, ix, ry + rh / 2, ui.dp(22f), C.FG)
            }
            ui.text(c, r.first, pad + ui.dp(58f), ry + rh / 2 + ui.dp(5.5f), ui.dp(16f), C.FG, Paint.Align.LEFT, ui.medium)
            ui.iconChevron(c, w - pad - ui.dp(22f), ry + rh / 2, ui.dp(14f), C.SUBTLE, false)
            if (i < rows.size - 1) c.drawRect(pad + ui.dp(58f), ry + rh - ui.dp(.5f), w - pad, ry + rh + ui.dp(.5f), ui.fillPaint(C.BORDER))
            ui.hit("me_$i", row, r.third)
        }
    }

    private fun bigRow(c: Canvas, id: String, r: RectF, label: String, caption: String,
                       icon: (Float, Float, Float) -> Unit, action: () -> Unit) {
        ui.card(c, r, ui.pressed(id))
        val cx = r.left + ui.dp(44f); val cy = r.centerY()
        ui.rowIcon(c, cx, cy, ui.dp(48f), C.FG) { x, yy, s -> icon(x, yy, s) }
        ui.text(c, label, r.left + ui.dp(84f), cy - ui.dp(2f), ui.dp(17f), C.FG, Paint.Align.LEFT, ui.displayBold)
        ui.text(c, caption, r.left + ui.dp(84f), cy + ui.dp(18f), ui.dp(13f), C.MUTED, Paint.Align.LEFT, ui.medium)
        ui.iconChevron(c, r.right - ui.dp(22f), cy, ui.dp(14f), C.SUBTLE, false)
        ui.hit(id, r, action)
    }

    // ---- Tab bar -----------------------------------------------------------------

    private fun drawTabBar(c: Canvas, barH: Float) {
        val top = h - barH
        c.drawRect(0f, top, w, h, ui.fillPaint(C.BG))
        c.drawRect(0f, top, w, top + ui.dp(1f), ui.fillPaint(C.BORDER))
        val tabs = Tab.values()
        val tw = w / tabs.size
        for ((i, t) in tabs.withIndex()) {
            val cx = tw * (i + .5f)
            val cy = top + ui.dp(24f)
            val col = if (tab == t) C.FG else C.SUBTLE
            when (t) {
                Tab.MAIN -> ui.tabHome(c, cx, cy, ui.dp(28f), col)
                Tab.DAILY -> ui.tabDaily(c, cx, cy, ui.dp(28f), col)
                Tab.ME -> ui.tabMe(c, cx, cy, ui.dp(28f), col)
            }
            val label = when (t) { Tab.MAIN -> "Main"; Tab.DAILY -> "Daily"; Tab.ME -> "Me" }
            ui.text(c, label, cx, top + ui.dp(52f), ui.dp(12f), col, face = ui.medium)
            ui.hit("tab_$i", RectF(tw * i, top, tw * (i + 1), top + ui.dp(64f))) { select(t); v.sfx.play(Sound.TAP) }
        }
    }
}
