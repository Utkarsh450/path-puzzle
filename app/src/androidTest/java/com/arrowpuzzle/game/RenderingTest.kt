package com.arrowpuzzle.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.test.InstrumentationTestCase
import android.view.View
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue

/**
 * Drawing code on the device: board renderer, previews, the Ui toolkit, every menu tab and
 * every popup, rendered into bitmaps. Uses a [GameView] that is never attached to a window and
 * reads test-only preferences.
 */
@Suppress("DEPRECATION")
class RenderingTest : InstrumentationTestCase() {
    private lateinit var context: TestPrefsContext
    private lateinit var store: Store

    override fun setUp() {
        super.setUp()
        context = TestPrefsContext(instrumentation.targetContext)
        context.wipe()
        store = Store(context)
        store.sound = false
        store.vibration = false
    }

    override fun tearDown() {
        C.dark = true
        context.wipe()
        AdsConfig.reset()
        super.tearDown()
    }

    private fun <T> main(block: () -> T): T = instrumentation.onMain(block)

    private fun Bitmap.count(color: Int): Int {
        val px = IntArray(width * height)
        getPixels(px, 0, width, 0, 0, width, height)
        return px.count { it == color }
    }

    private fun Bitmap.distinctColors(): Int {
        val px = IntArray(width * height)
        getPixels(px, 0, width, 0, 0, width, height)
        return px.toSet().size
    }

    fun testPreviewDrawsEveryArrowWhereTheBoardHasIt() {
        Board(6).use { b ->
            val infos = List(b.arrowCount) { b.arrow(it) }
            val pic = BoardRenderer().recordPreview(infos, b.cols, b.rows)
            val cell = 20
            val bmp = Bitmap.createBitmap(b.cols * cell, b.rows * cell, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawPreview(pic, b.cols, b.rows, RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), 0f)
            for ((id, a) in infos.withIndex()) {
                // the middle of every straight segment lies on the stroke
                for (i in 1 until a.pts.size) {
                    val x = ((a.pts[i - 1].x + a.pts[i].x) / 2f + .5f) * cell
                    val y = ((a.pts[i - 1].y + a.pts[i].y) / 2f + .5f) * cell
                    assertEquals("arrow $id segment $i not drawn", C.ARROW, bmp.getPixel(x.toInt(), y.toInt()))
                }
            }
        }
    }

    /**
     * A drawn arrow measures like the real game's on the test phone (24.75 px cells, scaled
     * here to 50 px so a pixel is a finer step): shaft 8 px, head 28 px wide, tip 11 px past
     * the centre.
     */
    fun testArrowShapeMatchesTheRealGame() {
        // (0,1) -> (1,1) -> (2,1), heading right, on a 3x3 board drawn at 50 px per cell
        val arrow = ArrowInfo(true, 1, 0, -1, 0, false, -1, -1, -1, listOf(Pt(0, 1, false), Pt(1, 1, false), Pt(2, 1, false)))
        val pic = BoardRenderer().recordPreview(listOf(arrow), 3, 3, 0xFF000000.toInt())
        val bmp = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawPreview(pic, 3, 3, RectF(0f, 0f, 150f, 150f), 0f)
        fun ink(x: Int, y: Int) = (bmp.getPixel(x, y) ushr 24) >= 128
        fun column(x: Int) = (0 until 150).count { ink(x, it) }
        val shaft = column(50)
        val head = (100..140).maxOf { column(it) }
        val tip = (0 until 150).last { x -> (0 until 150).any { ink(x, it) } }
        val tail = (0 until 150).first { x -> (0 until 150).any { ink(x, it) } }
        assertEquals("shaft width", 8f, shaft.toFloat(), 1.5f)
        assertEquals("head width", 28f, head.toFloat(), 2.5f)
        assertEquals("tip position (centre 125 + 11)", 136f, tip.toFloat(), 2f)
        assertEquals("round tail cap half a line before the centre (25 - 4)", 21f, tail.toFloat(), 1.5f)
    }
    fun testBoardRendererDrawsEveryArrowState() {
        val bmp = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        main {
            val gs = GameState(store, Sfx(store), Haptics(context, store))
            gs.attach(Board(6), "")
            val ui = Ui(context.resources)
            val r = BoardRenderer()
            val c = Canvas(bmp)
            fun frame(dt: Float) { bmp.eraseColor(0); gs.update(dt); r.draw(c, gs, ui, 40f, 10f, 10f, dt, true) }

            frame(.01f)                                    // intro: arrows growing in
            repeat(40) { frame(.05f) }                     // intro finished
            assertTrue("idle arrows", bmp.count(C.ARROW) > 1000)

            val blocked = Geometry.blockedArrow(gs.board!!)
            gs.tap(blocked, false)                         // red mistake arrow
            frame(.02f)
            assertTrue("red arrow", bmp.count(C.MISTAKE) > 50)

            gs.hintId = Geometry.freeAtStart(gs.board!!).first()   // hinted arrow
            frame(.02f)
            assertTrue("hinted arrow", bmp.count(C.ACCENT) > 50)

            gs.tap(gs.hintId, false)                       // flying arrow
            frame(.05f)
            assertTrue("moving arrow", bmp.count(C.ACCENT) > 20)

            gs.eraserArmed = true                          // fading arrow + particles
            gs.tap(Geometry.blockedArrow(gs.board!!), false)
            frame(.05f)
            repeat(60) { frame(.05f) }
            gs.close()
        }
    }

    fun testUiToolkitDrawsAndRegistersHits() {
        val bmp = Bitmap.createBitmap(1080, 1600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val ui = Ui(context.resources)
        assertEquals(context.resources.displayMetrics.density * 10f, ui.dp(10f), 1e-3f)
        assertTrue(ui.textW("Level 12", ui.dp(18f)) > ui.textW("1", ui.dp(18f)))

        ui.primaryButton(c, "a", RectF(0f, 0f, 400f, 120f), "Play", "Level 3") {}
        ui.primaryButton(c, "disabled", RectF(0f, 140f, 400f, 260f), "Completed", null, false) {}
        ui.whiteButton(c, "b", RectF(0f, 280f, 400f, 400f), "New Game") {}
        ui.secondaryButton(c, "c", RectF(0f, 420f, 400f, 540f), "Restart") {}
        ui.textButton(c, "d", 200f, 600f, "Main", C.ACCENT, 40f) {}
        ui.circleButton(c, "e", 600f, 100f, 80f, true) {}
        assertEquals(listOf("a", "b", "c", "d", "e"), ui.hits.map { it.id })
        assertTrue(ui.hits.first { it.id == "e" }.r.contains(600f, 100f))

        // every icon and decoration draws without throwing
        var x = 100f
        val y = 900f
        val s = 80f
        val icons: List<() -> Unit> = listOf(
            { ui.iconBack(c, x, y, s, C.ACCENT) }, { ui.iconGear(c, x, y, s, C.ACCENT) }, { ui.iconBulb(c, x, y, s, C.ACCENT) },
            { ui.iconEraser(c, x, y, s, C.ACCENT) }, { ui.iconWand(c, x, y, s, C.ACCENT) }, { ui.iconGrid(c, x, y, s, C.ACCENT) },
            { ui.iconArrowCount(c, x, y, s, C.ACCENT) }, { ui.heart(c, x, y, s, C.HEART) }, { ui.coin(c, x, y, s / 2) },
            { ui.star5(c, x, y, s / 2, C.GOLD) }, { ui.lock(c, x, y, s, .5f) }, { ui.key(c, x, y, s) }, { ui.cup(c, x, y, s) },
            { ui.iconHome(c, x, y, s, C.ACCENT) }, { ui.iconCalendar(c, x, y, s, C.ACCENT) }, { ui.iconUser(c, x, y, s, C.ACCENT) },
            { ui.iconClose(c, x, y, s, C.ACCENT) }, { ui.iconChevron(c, x, y, s, C.ACCENT, true) }, { ui.iconCheck(c, x, y, s, C.ACCENT) },
            { ui.glossyHeart(c, x, y, s, true) }, { ui.glossyHeart(c, x, y, s, false) }, { ui.bulb3d(c, x, y, s) },
            { ui.ruler3d(c, x, y, s) }, { ui.eraser3d(c, x, y, s) }, { ui.wand3d(c, x, y, s) }, { ui.calendar3d(c, x, y, s) },
            { ui.podium3d(c, x, y, s) }, { ui.bigTrophy(c, x, y, s) }, { ui.tabHome(c, x, y, s, C.ACCENT) },
            { ui.tabDaily(c, x, y, s, C.ACCENT) }, { ui.tabMe(c, x, y, s, C.ACCENT) }, { ui.hand(c, x, y, s, .5f) },
            { ui.badge(c, x, y, "3") }, { ui.toggle(c, x, y, true) }, { ui.toggle(c, x, y, false) }, { ui.pill(c, RectF(x, y, x + s, y + s)) }
        )
        for ((i, draw) in icons.withIndex()) { x = 60f + (i % 12) * 85f; draw() }
        for (m in Mechanic.values()) MechanicArt.draw(c, RectF(0f, 1200f, 500f, 1500f), 1.3f, m, ui)
        assertTrue(bmp.distinctColors() > 50)
    }

    /** An unattached GameView sized like a phone; drawing it runs the real onDraw. */
    private fun view(): GameView = main {
        val v = GameView(context)
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 2340)
        v
    }

    private fun drawFrames(v: GameView, frames: Int = 12): Bitmap = main {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        repeat(frames) { SystemClockSleep.sleep(30); v.draw(c) }
        bmp
    }

    private fun hitIds(v: GameView) = main { v.ui.hits.map { it.id } }

    private fun drawable(name: String) = context.resources.getIdentifier(name, "drawable", context.packageName)

    private fun Bitmap.nearBrand(): Int {
        val px = IntArray(width * height)
        getPixels(px, 0, width, 0, 0, width, height)
        // orange: strong red, medium green, little blue (the glowing arrow and the progress bar)
        return px.count { val r = it shr 16 and 255; val g = it shr 8 and 255; val b = it and 255; r > 200 && g in 90..190 && b < 90 }
    }

    /** The logo and every icon layer exist, have the right size and keep their transparent parts. */
    fun testLogoAndLauncherIconLayers() {
        val ui = Ui(context.resources)
        val logo = ui.logo
        assertEquals(512, logo.width); assertEquals(512, logo.height)
        assertEquals("rounded tile: transparent corner", 0, logo.getPixel(2, 2) ushr 24)
        assertEquals("opaque centre", 255, logo.getPixel(256, 256) ushr 24)
        assertTrue("the glowing orange arrow is in the logo", logo.nearBrand() > 500)

        for (name in listOf("ic_launcher_background", "ic_launcher_foreground", "ic_launcher_monochrome")) {
            val id = drawable(name)
            assertTrue("$name resource missing", id != 0)
            val bmp = android.graphics.BitmapFactory.decodeResource(context.resources, id)
            assertEquals("$name width", 432, bmp.width); assertEquals("$name height", 432, bmp.height)
            when (name) {
                "ic_launcher_background" -> assertTrue("background is opaque and dark", (0 until 432 step 36).all { y ->
                    val p = bmp.getPixel(216, y); (p ushr 24) == 255 && (p shr 16 and 255) < 40 })
                "ic_launcher_foreground" -> {
                    assertEquals("transparent corner", 0, bmp.getPixel(4, 4) ushr 24)
                    assertTrue("artwork in the middle", bmp.getPixel(216, 216) ushr 24 > 200)
                    assertTrue("orange arrow", bmp.nearBrand() > 200)
                }
                "ic_launcher_monochrome" -> {
                    val px = IntArray(432 * 432); bmp.getPixels(px, 0, 432, 0, 0, 432, 432)
                    val drawn = px.filter { it ushr 24 > 0 }
                    assertTrue("arrows only, not empty and not everything", drawn.size in 3_000..60_000)
                    assertTrue("one colour: black with alpha", drawn.all { (it and 0xFFFFFF) == 0 })
                }
            }
        }
        // the splash window and the launcher icon definition load
        assertTrue(context.resources.getDrawable(drawable("splash_window"), null) != null)
        assertTrue(context.resources.getDrawable(context.resources.getIdentifier("ic_launcher", "mipmap", context.packageName), null) != null)
    }

    /** The loading screen shows the logo with its orange glow and a progress bar, in both themes. */
    fun testLoadingScreenShowsTheLogo() {
        for (dark in listOf(true, false)) {
            C.dark = dark
            val ui = Ui(context.resources)
            val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(C.BG)
            main { ui.loader(c, 540f, 980f, 360f, 1.2f, "Loading your board") }
            assertTrue("dark=$dark: logo drawn", bmp.distinctColors() > 500)
            assertTrue("dark=$dark: orange arrow and bar", bmp.nearBrand() > 500)
            assertTrue("dark=$dark: the tile sits in the middle", bmp.getPixel(540, 980) != C.BG)
            assertEquals("dark=$dark: the corners stay clean", C.BG, bmp.getPixel(10, 10))
        }
        // and the real game screen draws it while a level loads
        val v = view()
        main { v.javaClass.getDeclaredField("inGame").apply { isAccessible = true }.setBoolean(v, true) }
        assertTrue("a fresh game screen is loading", main { v.game.loading })
        val bmp = drawFrames(v, 6)
        assertTrue("logo on the loading screen", bmp.nearBrand() > 500)
    }

    fun testEveryMenuTabRenders() {
        val v = view()
        drawFrames(v)
        assertTrue(hitIds(v).containsAll(listOf("m_play", "m_daily", "m_rush", "m_duel", "m_settings", "m_theme", "tab_0", "tab_1", "tab_2")))
        main { v.menu.select(MenuScreen.Tab.DAILY) }
        val daily = drawFrames(v)
        assertTrue(hitIds(v).containsAll(listOf("d_play", "d_prev", "d_1")))
        assertTrue(daily.distinctColors() > 30)
        main { v.menu.select(MenuScreen.Tab.ME) }
        drawFrames(v)
        assertTrue(hitIds(v).containsAll(listOf("me_awards", "me_stats", "me_0", "me_1", "me_2")))
    }

    fun testEveryPopupRendersItsButtons() {
        store.coins = 500  // so the paid buttons are enabled too
        val v = view()
        drawFrames(v, 2)
        val cases: List<Pair<String, () -> Unit>> = listOf(
            "settings" to { v.showSettings(false) },
            "settings in game" to { v.showSettings(true) },
            "statistics" to { v.showStatistics() },
            "awards" to { v.showAwards() },
            "about" to { v.showAbout() },
            "how to play" to { v.showHowToPlay() },
            "lose (free)" to { v.showLose(40, true) },
            "lose (paid)" to { v.showLose(40, false) }
        ) + Booster.values().map { b -> "get ${b.key}" to { v.showGetBooster(b) } } +
            Mechanic.values().map { m -> "mechanic ${m.name}" to { v.showMechanicTutorial(m) } }
        for ((name, open) in cases) {
            main { open() }
            drawFrames(v, 14)
            val ids = hitIds(v)
            assertTrue("$name has no buttons: $ids", ids.any { it.startsWith("p") })
            // close it before the next case
            main { for (o in v.field<ArrayList<Overlay>>("overlays")) o.close() }
            drawFrames(v, 12)
            assertTrue("$name did not close", main { v.field<ArrayList<Overlay>>("overlays").isEmpty() })
        }
        // the settings popup in a level has the extra "Restart level" row before "Done"
        main { v.showSettings(true) }
        drawFrames(v, 14)
        assertTrue(hitIds(v).contains("p6"))
    }

    private fun pixelAt(bmp: Bitmap, x: Float, y: Float) = bmp.getPixel(x.toInt().coerceIn(0, bmp.width - 1), y.toInt().coerceIn(0, bmp.height - 1))

    /** ChaiUI surfaces, read off-screen so no display filter can tint them. */
    fun testChaiSurfacesInBothThemes() {
        for (dark in listOf(true, false)) {
            store.darkTheme = dark
            val v = view()
            assertEquals(dark, C.dark)
            val bmp = drawFrames(v, 14)
            val bg = if (dark) 0xFF0A0A0A.toInt() else 0xFFFFFFFF.toInt()
            // page background below the hexagon glow, left of the content column
            assertEquals("page background (dark=$dark)", bg, pixelAt(bmp, 4f, v.height * .8f))
            // the one solid button: top-left corner square, top-right corner rounded
            val play = main { RectF(v.ui.hits.last { it.id == "m_play" }.r) }
            assertEquals("solid fill (dark=$dark)", C.SOLID, pixelAt(bmp, play.left + 6f, play.centerY()))
            assertEquals("square top-left corner", C.SOLID, pixelAt(bmp, play.left + 1f, play.top + 1f))
            assertFalse("rounded top-right corner", pixelAt(bmp, play.right - 1f, play.top + 1f) == C.SOLID)
            assertEquals("square bottom-right corner", C.SOLID, pixelAt(bmp, play.right - 1f, play.bottom - 1f))
            assertFalse("rounded bottom-left corner", pixelAt(bmp, play.left + 1f, play.bottom - 1f) == C.SOLID)
        }
    }

    fun testDarkThemeSettingSwitchesTheTheme() {
        val v = view()
        assertTrue(C.dark)
        main { v.showSettings(false) }
        drawFrames(v, 14)
        val toggle = main { v.ui.hits.last { it.id == "p3" } }  // Sounds, Vibration, Grid guide, Dark theme
        main { toggle.action() }
        assertFalse(store.darkTheme)
        assertFalse(C.dark)
        main { for (o in v.field<ArrayList<Overlay>>("overlays")) o.close() }
        val bmp = drawFrames(v, 14)
        assertEquals("light page after the switch", 0xFFFFFFFF.toInt(), pixelAt(bmp, 4f, v.height * .8f))
        main { v.showSettings(false) }
        drawFrames(v, 14)
        main { v.ui.hits.last { it.id == "p3" }.action() }
        assertTrue(store.darkTheme)
        assertTrue(C.dark)
    }

    /** Regression: a closed popup's buttons used to stay hittable until something redrew the screen. */
    fun testClosedPopupLeavesNoButtonsBehind() {
        val v = view()
        drawFrames(v, 2)
        main { v.showSettings(false) }
        drawFrames(v, 14)
        main { v.field<ArrayList<Overlay>>("overlays").single().close() }
        val bmp = main { Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888) }
        val c = Canvas(bmp)
        waitUntil("popup removed") {
            main { SystemClockSleep.sleep(20); v.draw(c); v.field<ArrayList<Overlay>>("overlays").isEmpty() }
        }
        // no further frame: the hit list must already be free of the popup's buttons
        val ids = hitIds(v)
        assertFalse("stale popup buttons: $ids", ids.any { it.startsWith("p") })
        assertTrue(ids.contains("m_play"))
    }

    fun testWinScreenRendersItsButtons() {
        val v = view()
        drawFrames(v, 2)
        main {
            Board(6).use { b ->
                val infos = List(b.arrowCount) { b.arrow(it) }
                v.showWin(WinInfo(6, 0, 18, true, v.renderer.recordPreview(infos, b.cols, b.rows), b.cols, b.rows))
            }
        }
        // buttons fade in after ~0.6 s
        main { SystemClockSleep.sleep(800) }
        val bmp = drawFrames(v, 10)
        assertTrue(hitIds(v).containsAll(listOf("w_next", "w_main")))
        assertTrue("level preview on the win card", bmp.count(C.ARROW) > 100)
        assertFalse(main { v.field<ArrayList<Overlay>>("overlays").isEmpty() })
    }
}

/** Short real-time pause so overlay timers (wall-clock based) advance between frames. */
object SystemClockSleep {
    fun sleep(ms: Long) = android.os.SystemClock.sleep(ms)
}
