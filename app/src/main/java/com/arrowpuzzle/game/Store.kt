package com.arrowpuzzle.game

import android.content.Context

enum class Booster(val key: String, val title: String, val cost: Int, val unlockLevel: Int) {
    HINT("hint", "Hint", 20, 1),
    ERASER("eraser", "Eraser", 30, 20),
    WAND("wand", "Magic wand", 40, 30),
    GRID("grid", "Grid guide", 0, 3)
}

/** Persistent progress, statistics and settings. */
class Store(context: Context) {
    companion object {
        /** The preferences file. UI tests point this at their own file so the player's save is never touched. */
        @Volatile var fileName = "arrowpuzzle.v2"
    }

    private val prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = prefs.getInt(key, def)
    private fun putInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()
    private fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    var level: Int get() = int("level", 1); set(v) = putInt("level", v)
    var coins: Int get() = int("coins", 50); set(v) = putInt("coins", v)
    var sound: Boolean get() = bool("sound", true); set(v) = putBool("sound", v)
    var vibration: Boolean get() = bool("vibration", true); set(v) = putBool("vibration", v)
    var gridGuide: Boolean get() = bool("gridGuide", false); set(v) = putBool("gridGuide", v)
    /** ChaiUI is dark by default. */
    var darkTheme: Boolean get() = bool("darkTheme", true); set(v) = putBool("darkTheme", v)

    fun count(b: Booster): Int = int("b_" + b.key, if (b == Booster.HINT) 3 else 2)
    fun setCount(b: Booster, v: Int) = putInt("b_" + b.key, v)

    // ---- statistics -----------------------------------------------------------
    var levelsCompleted: Int get() = int("st_levels", 0); set(v) = putInt("st_levels", v)
    var perfectLevels: Int get() = int("st_perfect", 0); set(v) = putInt("st_perfect", v)
    var arrowsCleared: Int get() = int("st_arrows", 0); set(v) = putInt("st_arrows", v)
    var bestStreak: Int get() = int("st_best", 0); set(v) = putInt("st_best", v)
    var winStreak: Int get() = int("st_streak", 0); set(v) = putInt("st_streak", v)
    var hintsUsed: Int get() = int("st_hints", 0); set(v) = putInt("st_hints", v)

    // ---- daily challenges (date as yyyymmdd) -----------------------------------
    fun dailyDone(date: Int) = bool("dc_$date", false)
    fun setDailyDone(date: Int) = putBool("dc_$date", true)
    fun dailyCount(yyyymm: Int): Int = (1..31).count { dailyDone(yyyymm * 100 + it) }

    // ---- modes --------------------------------------------------------------------
    /** Duels started; each gets a fresh board. */
    var duelRounds: Int get() = int("duel_rounds", 0); set(v) = putInt("duel_rounds", v)
    var duelsPlayed: Int get() = int("duel_played", 0); set(v) = putInt("duel_played", v)
    /** Time rush runs started; each gets fresh boards. */
    var rushRuns: Int get() = int("rush_runs", 0); set(v) = putInt("rush_runs", v)
    var rushBest: Int get() = int("rush_best", 0); set(v) = putInt("rush_best", v)

    // ---- tutorials --------------------------------------------------------------
    fun seen(key: String) = bool("seen_$key", false)
    fun markSeen(key: String) = putBool("seen_$key", true)

    // ---- level in progress: replayable move log --------------------------------
    var savedLevel: Int get() = int("save_level", 0); set(v) = putInt("save_level", v)
    var savedSalt: Int get() = int("save_salt", 0); set(v) = putInt("save_salt", v)
    /** GameMode.key of the level in progress. */
    var savedMode: Int get() = int("save_mode", 0); set(v) = putInt("save_mode", v)
    var savedMoves: String get() = prefs.getString("save_moves", "") ?: ""; set(v) = prefs.edit().putString("save_moves", v).apply()

    fun clearSave() = prefs.edit().remove("save_level").remove("save_salt").remove("save_mode").remove("save_moves").apply()
}
