package com.arrowpuzzle.game

class Pt(val x: Int, val y: Int, val jump: Boolean)

class ArrowInfo(
    val alive: Boolean,
    val dx: Int, val dy: Int,
    val tdx: Int, val tdy: Int,
    val twoWay: Boolean,
    val link: Int,
    val keyZone: Int,
    val zone: Int,
    val pts: List<Pt>
)

class Portal(val ax: Int, val ay: Int, val bx: Int, val by: Int, val color: Int)
class StopPoint(val x: Int, val y: Int, var alive: Boolean)
class Redirect(val x: Int, val y: Int, val dx: Int, val dy: Int)
class Zone(val x0: Int, val y0: Int, val x1: Int, val y1: Int, var locked: Boolean)

class Features(val portals: List<Portal>, val stops: List<StopPoint>, val redirects: List<Redirect>, val zones: List<Zone>)

enum class Outcome { EXIT, BLOCKED, STOPPED, NOT_TAPPABLE, LOOP }

class Move(
    val arrow: Int, val reverse: Boolean, val outcome: Outcome,
    val dx: Int, val dy: Int, val blocker: Int, val stop: Int, val track: List<Pt>
)

class TapResult(val success: Boolean, val unlocked: IntArray, val moves: List<Move>)

/** Mechanics in the order the C++ core numbers them. */
enum class Mechanic(val title: String, val description: String, val keyword: String) {
    PORTAL("Portal", "An arrow enters one portal and comes out of the other", "portal"),
    STOP("Stop point", "A stop point stops an arrow that bumps into it", "stop point"),
    REDIRECT("Turn marker", "A turn marker changes an arrow's direction", "turn marker"),
    ZONE("Key and lock", "An arrow with a key unlocks the locked zone", "key"),
    TWO_WAY("Two-way arrow", "A two-way arrow moves in the direction of the tapped side", "two-way arrow"),
    LINK("Magnetic arrows", "Magnetic arrows move at the same time, each in its own direction", "Magnetic arrows");

    companion object {
        val unlockLevels = intArrayOf(15, 25, 35, 45, 55, 65)

        /** Mirrors kMechanicsEnabled in puzzle_core.cpp: special mechanics are switched off for now. */
        const val ENABLED = false
    }
}

/** Owning wrapper around a native puzzle::Board. Not thread-safe: use [copyHandle] for background work. */
class Board private constructor(private var handle: Long, val level: Int, val salt: Int) : AutoCloseable {
    constructor(level: Int, salt: Int = 0) : this(Native.create(level, salt), level, salt)

    val cols: Int
    val rows: Int
    val arrowCount: Int
    val introduces: Int
    val difficulty: Int
    private val mask: BooleanArray

    init {
        val i = Native.info(handle)
        cols = i[0]; rows = i[1]; arrowCount = i[2]; introduces = i[3]; difficulty = i[5]
        mask = BooleanArray(cols * rows) { i[6 + it] == 1 }
    }

    fun inMask(x: Int, y: Int) = x in 0 until cols && y in 0 until rows && mask[y * cols + x]

    fun arrow(id: Int): ArrowInfo {
        val a = Native.arrow(handle, id)
        val n = a[9]
        val pts = List(n) { Pt(a[10 + it * 3], a[11 + it * 3], a[12 + it * 3] == 1) }
        return ArrowInfo(a[0] == 1, a[1], a[2], a[3], a[4], a[5] == 1, a[6], a[7], a[8], pts)
    }

    fun features(): Features {
        val f = Native.features(handle)
        var p = 0
        val portals = List(f[p++]) { Portal(f[p++], f[p++], f[p++], f[p++], f[p++]) }
        val stops = List(f[p++]) { StopPoint(f[p++], f[p++], f[p++] == 1) }
        val redirects = List(f[p++]) { Redirect(f[p++], f[p++], f[p++], f[p++]) }
        val zones = List(f[p++]) { Zone(f[p++], f[p++], f[p++], f[p++], f[p++] == 1) }
        return Features(portals, stops, redirects, zones)
    }

    fun tap(id: Int, reverse: Boolean): TapResult {
        val r = Native.tap(handle, id, reverse)
        var p = 0
        val success = r[p++] == 1
        val unlocked = IntArray(r[p++]) { r[p++] }
        val moves = List(r[p++]) {
            val arrow = r[p++]; val rev = r[p++] == 1; val outcome = Outcome.values()[r[p++]]
            val dx = r[p++]; val dy = r[p++]; val blocker = r[p++]; val stop = r[p++]
            val track = List(r[p++]) { Pt(r[p++], r[p++], r[p++] == 1) }
            Move(arrow, rev, outcome, dx, dy, blocker, stop, track)
        }
        return TapResult(success, unlocked, moves)
    }

    fun erase(id: Int): IntArray = Native.erase(handle, id)
    fun tappable(id: Int): Boolean = Native.tappable(handle, id)
    fun remaining(): Int = Native.remaining(handle)

    /** A detached copy for background work (hints); the caller must close it. */
    fun copy(): Board = Board(Native.copy(handle), level, salt)

    /** Arrow id and direction of a move that keeps the board solvable, or null. */
    fun hint(): Pair<Int, Boolean>? {
        val h = Native.hint(handle)
        return if (h[0] < 0) null else h[0] to (h[1] == 1)
    }

    override fun close() {
        if (handle != 0L) {
            Native.destroy(handle)
            handle = 0L
        }
    }
}

object Levels {
    val names = arrayOf("Normal", "Hard", "Expert", "Master")

    /** Same rule as difficultyOf() in puzzle_core.cpp. */
    fun difficultyIndex(level: Int): Int = when {
        level < 6 -> 0
        level % 25 == 0 -> 3
        level % 10 == 0 -> 2
        level % 5 == 0 -> 1
        else -> 0
    }

    fun difficulty(level: Int): String = names[difficultyIndex(level)]

    /** Daily challenge level number for a date written as yyyymmdd (the date is the salt). */
    fun dailyLevel(date: Int) = 30 + (date % 9973) % 45

    /** Mode boards: kVariantFlag in puzzle_core.h, sized like the level, a layout per mode. */
    const val VARIANT_FLAG = 0x40000000

    /** A mode board's salt; each [round] gets a fresh layout. */
    fun modeSalt(mode: GameMode, round: Int = 0) = VARIANT_FLAG or ((round and 0x0FFFFFFF) shl 2) or mode.key
    fun isModeBoard(salt: Int) = salt and VARIANT_FLAG != 0
    fun isDaily(salt: Int) = salt != 0 && !isModeBoard(salt)

    /** Duels are played on one mid-sized board without mechanics, so a round lasts a few minutes. */
    const val DUEL_LEVEL = 9

    /** Time rush grows from small boards (index 0) to level 14, below the first mechanic. */
    fun rushBoardLevel(index: Int) = 4 + 2 * index.coerceIn(0, 5)

    /** Board [index] of rush [run]: every run, and every board in it, has its own layout. */
    fun rushSalt(run: Int, index: Int) = modeSalt(GameMode.RUSH, run * 64 + (index and 63))
}

/** Ways to play. CLASSIC is the main game and the daily challenge. Keys are saved; 1 is retired. */
enum class GameMode(val key: Int) { CLASSIC(0), DUEL(2), RUSH(3) }
