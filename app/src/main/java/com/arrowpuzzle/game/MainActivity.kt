package com.arrowpuzzle.game

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log

class MainActivity : Activity() {
    private lateinit var view: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        val age0 = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        super.onCreate(savedInstanceState)
        val t0 = SystemClock.elapsedRealtime()
        view = GameView(this)
        setContentView(view)
        Log.i("PathPuzzle", "start-up: process age at onCreate $age0 ms, GameView built in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!view.handleBack()) super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        view.onPauseSave()
    }
}
