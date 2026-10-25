package com.arrowpuzzle.game

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import java.io.File

/**
 * Makes the Google Play listing art: six phone screenshots and the feature graphic, drawn
 * off-screen from the real game code (real boards, real HUD) on test-only preferences, so the
 * player's save is never touched. Output goes to the app's files/store-shots directory;
 * copy it out with adb (see store/README.md). Play wants every side between 320 and 3840 px
 * and the long side at most twice the short one, so the phone shots are 1080 x 2120.
 */
@Suppress("DEPRECATION")
class StoreShotsTest : InstrumentationTestCase() {
    private lateinit var context: TestPrefsContext
    private lateinit var store: Store
    private lateinit var dir: File

    private val W = 1080
    private val H = 2120

    override fun setUp() {
        super.setUp()
        context = TestPrefsContext(instrumentation.targetContext)
        context.wipe()
        store = Store(context)
        store.sound = false
        store.vibration = false
        store.coins = 240
        store.level = 12
        for (k in listOf("tut_tap", "tut_zoom", "tut_duel", "tut_rush")) store.markSeen(k)
        C.dark = true
        dir = File(instrumentation.targetContext.filesDir, "store-shots").apply { deleteRecursively(); mkdirs() }
    }

    override fun tearDown() {
        C.dark = true
        context.wipe()
        super.tearDown()
    }

    private fun <T> main(block: () -> T): T = instrumentation.onMain(block)

    /** A GameView sized like the phone shots; never attached to a window, as in RenderingTest. */
    private fun view(): GameView = main {
        val v = GameView(context)
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
        v
    }

    private fun frames(v: GameView, bmp: Bitmap, n: Int) = main {
        val c = Canvas(bmp)
        repeat(n) { SystemClock.sleep(30); v.draw(c) }
    }

    private fun set(target: Any, name: String, f: (java.lang.reflect.Field) -> Unit) {
        val fld = target.javaClass.getDeclaredField(name)
        fld.isAccessible = true
        f(fld)
    }

    /**
     * Opens [b] in the game screen without the background loader (a view that is not attached
     * to a window never runs posted work), with the level intro already played.
     */
    private fun open(v: GameView, b: Board, mode: GameMode, rush: RushGame? = null) = main {
        set(v, "inGame") { it.setBoolean(v, true) }
        val m = v.game.javaClass.getDeclaredMethod("attach", Board::class.java, String::class.java, GameMode::class.java, RushGame::class.java)
        m.isAccessible = true
        m.invoke(v.game, b, "", mode, rush)
        set(v.game, "introT") { it.setFloat(v.game, 99f) }
        for (s in v.game.state.sprites) s.appear = 1f
    }

    /** Taps the arrows a hint suggests, as a player would, [n] times. */
    private fun play(v: GameView, n: Int) = main {
        repeat(n) {
            val h = v.game.state.board!!.hint() ?: return@repeat
            v.game.state.tap(h.first, h.second)
        }
    }

    /** Lets flying arrows leave and fades finish, without running the mode clocks. */
    private fun settle(v: GameView) = main { repeat(80) { v.game.state.update(.05f, false) } }

    /** Long enough for the praise text and other short effects to fade before the shot. */
    private fun capture(v: GameView, name: String, n: Int = 40): File {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(false)
        frames(v, bmp, n)
        return save(bmp, name)
    }

    private fun save(bmp: Bitmap, name: String): File {
        val f = File(dir, name)
        f.outputStream().use { assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return f
    }

    private fun board(level: Int, salt: Int) = Board(level, salt)

    fun testMakeStoreArt() {
        // 1. main menu, dark, with the mode cards
        run {
            val v = view()
            capture(v, "phone-01.png")
        }
        // 2. a heart board (level 12) a few moves in
        run {
            val v = view()
            val b = board(12, 0)
            open(v, b, GameMode.CLASSIC)
            play(v, b.arrowCount * 8 / 100)
            settle(v)
            capture(v, "phone-02.png")
        }
        // 3. a bow board (level 22)
        run {
            val v = view()
            val b = board(22, 0)
            open(v, b, GameMode.CLASSIC)
            play(v, b.arrowCount * 8 / 100)
            settle(v)
            capture(v, "phone-03.png")
        }
        // 4. the win screen, with a butterfly board on its card
        run {
            val v = view()
            val b = board(Levels.DUEL_LEVEL, Levels.modeSalt(GameMode.DUEL, 12))
            val pic = v.renderer.recordPreview(List(b.arrowCount) { b.arrow(it) }, b.cols, b.rows)
            main { v.showWin(WinInfo(12, 0, 22, true, pic, b.cols, b.rows)) }
            b.close()
            capture(v, "phone-04.png", 60)       // the buttons fade in after about 0.6 s
        }
        // 5. Time rush on a moon board, a combo running
        run {
            val v = view()
            val salt = Levels.rushSalt(4, 2)
            open(v, board(Levels.rushBoardLevel(2), salt), GameMode.RUSH, RushGame())
            play(v, 8)
            settle(v)
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            frames(v, bmp, 40)                      // praise and effects fade away
            main { v.game.state.rush!!.tick(7f) }    // the clock a few seconds in
            bmp.setHasAlpha(false)
            frames(v, bmp, 14)
            save(bmp, "phone-05.png")
            assertTrue("a combo is running", main { v.game.state.rush!!.multiplier } >= 2)
        }
        // 6. a Duel on a house board: scores, hearts, the turn clock
        run {
            val v = view()
            val salt = Levels.modeSalt(GameMode.DUEL, 10)
            val b = board(Levels.DUEL_LEVEL, salt)
            open(v, b, GameMode.DUEL)
            play(v, 3)
            val blocked = main { Geometry.blockedArrow(v.game.state.board!!) }
            main { v.game.state.tap(blocked, false) }            // the second player's slip costs a heart
            play(v, 2)
            settle(v)
            main { for (s in v.game.state.sprites) s.red = false }
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            frames(v, bmp, 30)
            main { v.game.state.duel!!.tick(1.5f) }
            bmp.setHasAlpha(false)
            frames(v, bmp, 14)
            save(bmp, "phone-06.png")
        }
        featureGraphic()

        // More screens for the listing and for the player's own use (Play takes at most 8 of them).
        // 7. the Daily tab
        run {
            val v = view()
            main { v.menu.select(MenuScreen.Tab.DAILY) }
            capture(v, "phone-07.png", 50)
        }
        // 8. the Me tab
        run {
            val v = view()
            main { v.menu.select(MenuScreen.Tab.ME) }
            capture(v, "phone-08.png", 50)
        }
        // 9. Settings
        run {
            val v = view()
            main { v.showSettings(false) }
            capture(v, "phone-09.png", 60)
        }
        // 10. the light theme, main menu
        run {
            val v = view()
            main { v.setDarkTheme(false) }
            capture(v, "phone-10.png", 50)
            main { v.setDarkTheme(true) }
        }
        // 11. a butterfly board a few moves in
        run {
            val v = view()
            val b = board(Levels.DUEL_LEVEL, Levels.modeSalt(GameMode.DUEL, 12))
            open(v, b, GameMode.CLASSIC)
            play(v, b.arrowCount * 8 / 100)
            settle(v)
            capture(v, "phone-11.png")
        }
        // 12. the out-of-lives sheet over a board
        run {
            val v = view()
            val b = board(12, 0)
            open(v, b, GameMode.CLASSIC)
            play(v, b.arrowCount * 12 / 100)
            settle(v)
            main { v.showLose(46, true) }
            capture(v, "phone-12.png", 60)
        }

        // 13. the loading screen, shown while a level is built
        run {
            val v = view()
            set(v, "inGame") { it.setBoolean(v, true) }
            capture(v, "phone-13.png", 34)
        }

        for (i in 1..13) {
            val f = File(dir, String.format("phone-%02d.png", i))
            assertTrue("${f.name} missing", f.exists())
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            assertEquals(f.name, W, o.outWidth); assertEquals(f.name, H, o.outHeight)
            assertTrue("Play: the long side may be at most twice the short one", o.outHeight <= 2 * o.outWidth)
            assertTrue("Play: every side between 320 and 3840 px", o.outWidth in 320..3840 && o.outHeight in 320..3840)
        }
        val g = File(dir, "feature-graphic-1024x500.png")
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(g.path, o)
        assertEquals(1024, o.outWidth); assertEquals(500, o.outHeight)
        // the art must not be blank
        val bmp = BitmapFactory.decodeFile(File(dir, "phone-02.png").path)
        val px = IntArray(W * H)
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        assertTrue("phone-02 looks blank", px.toSet().size > 50)
    }

    // ---- the 1024 x 500 feature graphic ------------------------------------------------

    private fun featureGraphic() {
        val gw = 1024
        val gh = 500
        val ui = Ui(context.resources)
        val bmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(false)
        val c = Canvas(bmp)
        c.drawColor(C.BG)
        ui.backdrop(c, gw, gh)

        // right: four real boards in hairline cards (kept inside the central safe area)
        val boards = listOf(board(12, 0), board(9, Levels.modeSalt(GameMode.DUEL, 9)),
            board(9, Levels.modeSalt(GameMode.DUEL, 12)), board(9, Levels.modeSalt(GameMode.DUEL, 10)))
        val card = 178f
        val gap = 18f
        val gx = 590f
        val gy = (gh - (2 * card + gap)) / 2f
        val renderer = BoardRenderer()
        for ((i, b) in boards.withIndex()) {
            val x = gx + (i % 2) * (card + gap)
            val y = gy + (i / 2) * (card + gap)
            val r = RectF(x, y, x + card, y + card)
            ui.card(c, r, rad = 22f)
            val pic = renderer.recordPreview(List(b.arrowCount) { b.arrow(it) }, b.cols, b.rows)
            c.drawPreview(pic, b.cols, b.rows, r, 20f)
            b.close()
        }

        // left: the logo, the name and one line about the game
        val left = 72f
        val iconSize = 132f
        ui.logoTile(c, left + iconSize / 2, 52f + iconSize / 2, iconSize)

        val maxW = 480f
        val title = "Path Puzzle"
        val titleSize = minOf(88f, maxW / ui.textW(title, 100f, ui.bold) * 100f)
        ui.text(c, title, left, 262f, titleSize, C.FG, Paint.Align.LEFT, ui.bold)
        val tagH = ui.paragraph(c, "Tap. Slide. Clear the board.", "Clear the board", left, 292f, maxW, 33f, C.MUTED, center = false)
        val sub = "Shapes, daily challenges, Time rush and Duel"
        val subSize = minOf(21f, maxW / ui.textW(sub, 100f, ui.medium) * 100f)
        ui.text(c, sub, left, 292f + tagH + 40f, subSize, C.SUBTLE, Paint.Align.LEFT, ui.medium)

        save(bmp, "feature-graphic-1024x500.png")
    }
}
