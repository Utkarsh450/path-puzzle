package com.arrowpuzzle.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

enum class Sound { TAP, SLIDE, BUMP, WIN, LOSE, PICK, UNLOCK, STOP, COIN, ERASE }

/** Tiny synth: every effect is a few decaying tones rendered to PCM once and replayed. */
class Sfx(private val store: Store) {
    private enum class Wave { SINE, TRI, SQUARE, SAW }
    private class Note(
        val freq: Float, val dur: Float, val wave: Wave = Wave.SINE,
        val vol: Float = .28f, val slide: Float = 0f, val delay: Float = 0f
    )

    private val rate = 22050
    private val exec = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val cache = HashMap<Sound, ShortArray>()

    private fun notesFor(s: Sound): List<Note> = when (s) {
        Sound.TAP -> listOf(Note(660f, .05f, Wave.SINE, .12f))
        Sound.SLIDE -> listOf(Note(480f, .2f, Wave.TRI, .22f, slide = 620f), Note(960f, .12f, Wave.SINE, .08f, delay = .04f))
        Sound.BUMP -> listOf(Note(170f, .18f, Wave.SQUARE, .16f, slide = -90f))
        Sound.PICK -> listOf(Note(880f, .12f, Wave.SINE, .22f), Note(1320f, .1f, Wave.SINE, .1f, delay = .05f))
        Sound.UNLOCK -> listOf(Note(700f, .1f, Wave.TRI, .2f), Note(1050f, .22f, Wave.TRI, .2f, delay = .08f))
        Sound.STOP -> listOf(Note(300f, .14f, Wave.TRI, .2f, slide = -120f))
        Sound.COIN -> listOf(Note(1200f, .08f, Wave.SINE, .16f), Note(1600f, .14f, Wave.SINE, .14f, delay = .06f))
        Sound.ERASE -> listOf(Note(900f, .25f, Wave.SINE, .14f, slide = -500f))
        Sound.WIN -> listOf(523f, 659f, 784f, 1047f).mapIndexed { i, f -> Note(f, .42f, Wave.TRI, .24f, delay = i * .09f) }
        Sound.LOSE -> listOf(330f, 277f, 220f).mapIndexed { i, f -> Note(f, .35f, Wave.SAW, .12f, delay = i * .14f) }
    }

    private fun wave(w: Wave, phase: Double): Double {
        val x = phase - Math.floor(phase)
        return when (w) {
            Wave.SINE -> sin(2 * PI * x)
            Wave.TRI -> 4 * abs(x - .5) - 1
            Wave.SQUARE -> if (x < .5) 1.0 else -1.0
            Wave.SAW -> 2 * x - 1
        }
    }

    private fun render(notes: List<Note>): ShortArray {
        val total = notes.maxOf { it.delay + it.dur }
        val out = ShortArray((total * rate).toInt() + 1)
        for (n in notes) {
            val start = (n.delay * rate).toInt()
            val len = (n.dur * rate).toInt()
            var phase = 0.0
            for (i in 0 until len) {
                val t = i.toFloat() / len
                phase += (n.freq + n.slide * t) / rate
                val attack = (i / (.004f * rate)).coerceAtMost(1f)
                val env = attack * exp(-5.0 * t).toFloat()
                val idx = start + i
                if (idx < out.size) {
                    val mixed = out[idx] + (wave(n.wave, phase) * env * n.vol * Short.MAX_VALUE).toInt()
                    out[idx] = mixed.coerceIn(-32768, 32767).toShort()
                }
            }
        }
        return out
    }

    fun play(s: Sound) {
        if (!store.sound) return
        exec.execute {
            try {
                val pcm = cache.getOrPut(s) { render(notesFor(s)) }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                timer.schedule({ track.release() }, (pcm.size * 1000L / rate) + 150, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                // Audio is optional; never let it crash the game.
            }
        }
    }
}

class Haptics(context: Context, private val store: Store) {
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    fun tick(ms: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        val v = vibrator ?: return
        if (!store.vibration || !v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(ms, amplitude))
    }
}
