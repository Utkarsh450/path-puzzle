package com.arrowpuzzle.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import junit.framework.TestCase.assertTrue
import java.io.File

/**
 * Draws the main screens at screen sizes other than the test phone's (small phones, big phones,
 * tablets, a wide window) and saves them for a look: the game is portrait-only, but Android may
 * still show it on a tablet or in a resized window. The test asserts that nothing throws and that
 * every picture is not blank; the pictures are judged by eye (see store/README.md).
 */
@Suppress("DEPRECATION")
class LayoutSizesTest : InstrumentationTestCase() {
    private lateinit var context: TestPrefsContext
    private lateinit var store: Store
    private lateinit var dir: File

    override fun setUp() {
        super.setUp()
        context = TestPrefsContext(instrumentation.targetContext)
        context.wipe()
        store = Store(context)
        store.sound = false; store.vibration = false; store.coins = 240; store.level = 12
        for (k in listOf("tut_tap", "tut_zoom", "tut_duel", "tut_rush")) store.markSeen(k)
        C.dark = true
        dir = File(instrumentation.targetContext.filesDir, "layout-shots").apply { deleteRecursively(); mkdirs() }
    }

    override fun tearDown() {
        C.dark = true
        context.wipe()
        super.tearDown()
    }

    private fun <T> main(block: () -> T): T = instrumentation.onMain(block)

    private val density get() = context.resources.displayMetrics.density

    private fun view(wDp: Int, hDp: Int): GameView = main {
        val w = (wDp * density).toInt(); val h = (hDp * density).toInt()
        val v = GameView(context)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        v
    }

    private fun capture(v: GameView, name: String, frames: Int = 36) {
        val bmp = main { Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888) }
        main { val c = Canvas(bmp); repeat(frames) { SystemClock.sleep(25); v.draw(c) } }
        File(dir, name).outputStream().use { assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        assertTrue("$name looks blank", px.toSet().size > 40)
    }

    private fun open(v: GameView, b: Board, mode: GameMode) = main {
        v.javaClass.getDeclaredField("inGame").apply { isAccessible = true }.setBoolean(v, true)
        val m = v.game.javaClass.getDeclaredMethod("attach", Board::class.java, String::class.java, GameMode::class.java, RushGame::class.java)
        m.isAccessible = true
        m.invoke(v.game, b, "", mode, null)
        v.game.javaClass.getDeclaredField("introT").apply { isAccessible = true }.setFloat(v.game, 99f)
        for (s in v.game.state.sprites) s.appear = 1f
    }

    fun testScreensAtManySizes() {
        // widths and heights in dp: a small phone, a normal one, a tall one, a small tablet, a tablet, a wide window
        val sizes = listOf("s320x568" to (320 to 568), "s360x740" to (360 to 740), "s412x915" to (412 to 915),
            "s600x960" to (600 to 960), "s800x1280" to (800 to 1280), "s1280x800" to (1280 to 800))
        for ((tag, wh) in sizes) {
            val (w, h) = wh
            // main menu, dark
            capture(view(w, h), "$tag-1-menu.png")
            // a shaped board in play
            run {
                val v = view(w, h)
                Board(12, 0).use { }       // build once to be sure level 12 exists
                open(v, Board(12, 0), GameMode.CLASSIC)
                main { repeat(4) { val hint = v.game.state.board!!.hint() ?: return@repeat; v.game.state.tap(hint.first, hint.second) } }
                capture(v, "$tag-2-game.png")
            }
            // a duel (two player panels and a clock)
            run {
                val v = view(w, h)
                open(v, Board(Levels.DUEL_LEVEL, Levels.modeSalt(GameMode.DUEL, 3)), GameMode.DUEL)
                capture(v, "$tag-3-duel.png")
            }
            // a popup and the result screen
            run {
                val v = view(w, h)
                main { v.showSettings(false) }
                capture(v, "$tag-4-settings.png", 50)
            }
            run {
                val v = view(w, h)
                Board(9, 0).use { b ->
                    val pic = v.renderer.recordPreview(List(b.arrowCount) { b.arrow(it) }, b.cols, b.rows)
                    main { v.showWin(WinInfo(9, 0, 20, true, pic, b.cols, b.rows)) }
                }
                capture(v, "$tag-5-win.png", 60)
            }
        }
    }
}
