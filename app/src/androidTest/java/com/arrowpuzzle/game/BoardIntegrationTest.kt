package com.arrowpuzzle.game

import android.test.InstrumentationTestCase
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlin.math.abs

/**
 * Integration tests on a device: the native generator, the JNI bridge and the Kotlin
 * [Board] wrapper together, with the game rules cross-checked by an independent Kotlin
 * implementation.
 */
@Suppress("DEPRECATION")
class BoardIntegrationTest : InstrumentationTestCase() {

    private fun daily() = Board(Levels.dailyLevel(20261024), 20261024)
    private fun duelBoard() = Board(Levels.DUEL_LEVEL, Levels.modeSalt(GameMode.DUEL, 7))
    private fun rushBoard() = Board(Levels.rushBoardLevel(5), Levels.rushSalt(3, 5))

    /** Every arrow decoded through JNI is a simple orthogonal path inside the mask, heading along its last segment. */
    private fun assertWellFormed(b: Board, name: String) {
        val seen = BooleanArray(b.cols * b.rows)
        for (id in 0 until b.arrowCount) {
            val a = b.arrow(id)
            assertTrue("$name arrow $id has ${a.pts.size} cells", a.pts.size >= 2)
            for ((i, p) in a.pts.withIndex()) {
                assertTrue("$name arrow $id leaves the mask at ${p.x},${p.y}", b.inMask(p.x, p.y))
                assertFalse("$name arrow $id overlaps another at ${p.x},${p.y}", seen[p.y * b.cols + p.x])
                seen[p.y * b.cols + p.x] = true
                if (i > 0) {
                    val q = a.pts[i - 1]
                    assertEquals("$name arrow $id is not contiguous", 1, abs(p.x - q.x) + abs(p.y - q.y))
                }
            }
            val n = a.pts.size
            assertEquals("$name arrow $id dx", a.pts[n - 1].x - a.pts[n - 2].x, a.dx)
            assertEquals("$name arrow $id dy", a.pts[n - 1].y - a.pts[n - 2].y, a.dy)
        }
    }

    fun testBoardsAreWellFormedAndDeterministic() {
        val boards = (1..80).map { "level $it" to { Board(it) } } +
            listOf("daily" to ::daily, "duel" to ::duelBoard, "rush" to ::rushBoard)
        for ((name, make) in boards) {
            make().use { a ->
                make().use { b ->
                    assertWellFormed(a, name)
                    assertEquals("$name arrow count differs between builds", a.arrowCount, b.arrowCount)
                    for (id in 0 until a.arrowCount) {
                        val pa = a.arrow(id).pts.map { it.x to it.y }
                        val pb = b.arrow(id).pts.map { it.x to it.y }
                        assertEquals("$name arrow $id differs between builds", pa, pb)
                    }
                }
            }
        }
    }

    /** Most boards from level 6 on are shapes (heart, bow, butterfly...), and every one is a single piece. */
    fun testShapedBoardsAreCommonAndSinglePieces() {
        var shaped = 0
        val sizes = HashSet<Pair<Int, Int>>()
        for (level in 6..60) Board(level).use { b ->
            var area = 0
            for (y in 0 until b.rows) for (x in 0 until b.cols) if (b.inMask(x, y)) area++
            if (area < b.cols * b.rows) shaped++
            sizes.add(b.cols to b.rows)
            // flood fill from the first cell: one piece only
            val seen = HashSet<Int>()
            val stack = ArrayDeque<Int>()
            val first = (0 until b.cols * b.rows).first { b.inMask(it % b.cols, it / b.cols) }
            stack.add(first); seen.add(first)
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val x = c % b.cols + dx; val y = c / b.cols + dy
                    if (b.inMask(x, y) && seen.add(y * b.cols + x)) stack.add(y * b.cols + x)
                }
            }
            assertEquals("level $level is split in pieces", area, seen.size)
        }
        assertTrue("only $shaped of 55 levels have a shape", shaped >= 25)
        assertTrue("board sizes barely vary: $sizes", sizes.size >= 15)
        // a daily can be a shape too
        val dailyShapes = (20261001..20261030).count { d ->
            Board(Levels.dailyLevel(d), d).use { b -> (0 until b.rows).any { y -> (0 until b.cols).any { x -> !b.inMask(x, y) } } }
        }
        assertTrue("$dailyShapes of 30 dailies have a shape", dailyShapes in 8..26)
    }

    /** The new generator: full coverage, long arrows, few free at the start. */
    fun testDenseBoardsLookLikeTheRealGame() {
        var arrows = 0
        var cells = 0
        var stubs = 0
        var freeShare = 0.0
        var boards = 0
        val levels = (4..120).map { "level $it" to { Board(it) } } + listOf("daily" to ::daily, "duel" to ::duelBoard, "rush" to ::rushBoard)
        for ((name, make) in levels) {
            make().use { b ->
                var area = 0
                for (y in 0 until b.rows) for (x in 0 until b.cols) if (b.inMask(x, y)) area++
                var covered = 0
                for (id in 0 until b.arrowCount) {
                    val len = b.arrow(id).pts.size
                    covered += len
                    if (len <= 2) stubs++
                }
                val free = Geometry.freeAtStart(b).size
                assertTrue("$name covers $covered of $area cells", covered * 100 >= area * 98)
                assertTrue("$name has no free arrow at the start", free >= 1)
                assertTrue("$name has $free of ${b.arrowCount} arrows free at the start", free * 100 <= b.arrowCount * (if (b.arrowCount < 40) 25 else 20))
                arrows += b.arrowCount
                cells += covered
                freeShare += free.toDouble() / b.arrowCount
                boards++
            }
        }
        val avgLen = cells.toDouble() / arrows
        assertTrue("average arrow length $avgLen", avgLen >= 6.0)
        assertTrue("$stubs of $arrows arrows are 1-2 cells", stubs * 50 <= arrows)
        assertTrue("average free-at-start share ${freeShare / boards}", freeShare / boards <= 0.08)
    }

    /** Native tap results agree with the Kotlin exit rule for every arrow on the starting board. */
    fun testNativeTapAgreesWithExitRule() {
        for (level in listOf(4, 6, 9, 12, 15, 20, 25)) {
            Board(level).use { b ->
                val occ = Geometry.occupancy(b)
                for (id in 0 until b.arrowCount) {
                    val expected = Geometry.rayClear(b, occ, id)
                    b.copy().use { c ->
                        val r = c.tap(id, false)
                        assertEquals("level $level arrow $id", expected, r.success)
                        if (expected) {
                            assertEquals(Outcome.EXIT, r.moves.first().outcome)
                            assertEquals(b.arrowCount - 1, c.remaining())
                            assertFalse(c.arrow(id).alive)
                        } else {
                            assertEquals(Outcome.BLOCKED, r.moves.first().outcome)
                            assertTrue("level $level arrow $id blocker", r.moves.first().blocker >= 0)
                            assertEquals(b.arrowCount, c.remaining())
                        }
                    }
                }
            }
        }
    }

    fun testLevelsCanBeClearedByFollowingHints() {
        val boards = listOf(1, 2, 3, 4, 5, 6, 9, 10, 15, 20, 25, 40, 50).map { "level $it" to { Board(it) } } +
            listOf("daily" to ::daily, "duel" to ::duelBoard, "rush" to ::rushBoard)
        for ((name, make) in boards) {
            make().use { b ->
                val moves = Geometry.playByHints(b)
                assertEquals("$name could not be finished with hints", b.arrowCount, moves)
                assertEquals(0, b.remaining())
            }
        }
    }

    fun testCopyIsIndependent() {
        Board(20).use { b ->
            val free = Geometry.freeAtStart(b).first()
            b.copy().use { c ->
                assertTrue(c.tap(free, false).success)
                assertEquals(b.arrowCount - 1, c.remaining())
            }
            assertEquals(b.arrowCount, b.remaining())
            assertTrue(b.arrow(free).alive)
        }
    }
}
