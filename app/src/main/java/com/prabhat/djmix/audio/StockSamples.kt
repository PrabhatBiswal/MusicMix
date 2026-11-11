package com.prabhat.djmix.audio

import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The starter sampler bank, synthesised on the phone: four 16-beat drum loops at [BPM] (the console runs them at
 * master BPM / [BPM], so they stay in time) and four classic one-shots. No assets, no licence, about 6 MB of RAM.
 * ponytail: plain synthesis; put CC0 WAVs on a pad through its long-press menu when these sound too dry.
 */
object StockSamples {
    const val BPM = 128.0
    const val RATE = 48000
    const val LOOP_BEATS = 16
    val NAMES = listOf("KICK", "BREAK", "HATS", "PERC", "HORN", "SIREN", "LASER", "RISER")

    class Sound(val name: String, val loop: Boolean, val track: PcmTrack)

    fun pack(): List<Sound> = NAMES.indices.map { sound(it) }

    fun sound(i: Int): Sound {
        seed = 12345
        val name = NAMES[i]
        val mono = when (name) {
            "KICK" -> loop(kick() to intArrayOf(0, 4, 8, 12), hat(false, 0.35f) to intArrayOf(2, 6, 10, 14))
            "BREAK" -> loop(kick() to intArrayOf(0, 7, 10), snare() to intArrayOf(4, 12), hat(false, 0.45f) to intArrayOf(0, 2, 4, 6, 8, 10, 12, 14))
            "HATS" -> loop(hat(false, 0.7f) to intArrayOf(0, 2, 4, 6, 8, 10, 12), hat(false, 0.3f) to intArrayOf(1, 3, 5, 7, 9, 11, 13, 15), hat(true, 0.6f) to intArrayOf(14))
            "PERC" -> loop(conga(310.0) to intArrayOf(0, 6, 8), conga(420.0) to intArrayOf(3, 11, 14), hat(false, 0.25f) to intArrayOf(1, 3, 5, 7, 9, 11, 13, 15))
            "HORN" -> airhorn()
            "SIREN" -> siren()
            "LASER" -> laser()
            else -> riser()
        }
        return Sound(name, i < 4, toTrack(mono, "stock-${name.lowercase()}"))
    }

    private val framesPerBeat = 60.0 / BPM * RATE

    /** One bar of 16th-note layers (hit, steps) repeated four times. */
    private fun loop(vararg layers: Pair<FloatArray, IntArray>): FloatArray {
        val step = framesPerBeat / 4
        val out = FloatArray((framesPerBeat * LOOP_BEATS).roundToInt())
        for ((hit, steps) in layers) for (bar in 0 until 4) for (s in steps) {
            val at = ((bar * 16 + s) * step).roundToInt()
            for (k in hit.indices) { val o = at + k; if (o >= out.size) break; out[o] += hit[k] }
        }
        return out
    }

    private inline fun synth(seconds: Double, f: (t: Double) -> Double): FloatArray {
        val n = (seconds * RATE).toInt()
        return FloatArray(n) { i -> f(i.toDouble() / RATE).toFloat() }
    }

    private var seed = 12345
    private fun noise(): Double { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 32768.0 - 1.0 }

    /** Sine with a 150 → 50 Hz pitch drop. */
    private fun kick() = synth(0.35) { t -> sin(2 * PI * (50 * t + 100 / 30.0 * (1 - exp(-30 * t)))) * exp(-8 * t) }

    private fun snare() = synth(0.25) { t -> 0.5 * sin(2 * PI * 180 * t) * exp(-30 * t) + 0.7 * noise() * exp(-18 * t) }

    /** High-passed noise burst; open hats ring five times longer. */
    private fun hat(open: Boolean, gain: Float): FloatArray {
        var lp = 0.0
        val k = 1 - exp(-2 * PI * 6000 / RATE)
        return synth(if (open) 0.4 else 0.08) { t -> val x = noise(); lp += k * (x - lp); gain * (x - lp) * exp(-(if (open) 8 else 45) * t) }
    }

    private fun conga(f: Double) = synth(0.14) { t -> 0.8 * sin(2 * PI * f * t * (1 + 0.08 * exp(-60 * t))) * exp(-22 * t) }

    /** Three detuned saws with a short pitch bend up, driven into tanh. */
    private fun airhorn(): FloatArray {
        var p1 = 0.0; var p2 = 0.0; var p3 = 0.0
        return synth(1.2) { t ->
            val f = 440.0 * (0.92 + 0.08 * minOf(1.0, t / 0.08))
            p1 += f / RATE; p2 += f * 1.012 / RATE; p3 += f * 0.988 / RATE
            tanh(2.5 * (saw(p1) + saw(p2) + saw(p3)) / 3) * minOf(1.0, t / 0.01) * minOf(1.0, (1.2 - t) / 0.12)
        }
    }

    private fun saw(phase: Double) = 2 * (phase - floor(phase)) - 1

    /** 600–1200 Hz triangle sweep at 0.75 Hz, three seconds. */
    private fun siren(): FloatArray {
        var p = 0.0
        return synth(3.0) { t ->
            val tri = abs((t * 0.75 % 1.0) * 2 - 1)
            p += (600 + 600 * tri) / RATE
            tanh(1.6 * sin(2 * PI * p)) * minOf(1.0, (3.0 - t) / 0.3)
        }
    }

    /** 2.5 kHz → 80 Hz exponential drop in 300 ms. */
    private fun laser(): FloatArray {
        var p = 0.0
        return synth(0.3) { t -> p += 2500 * (80.0 / 2500).pow(t / 0.3) / RATE; tanh(3 * sin(2 * PI * p)) * exp(-5 * t) }
    }

    /** Two bars: noise through a low-pass sweeping 150 Hz → 9 kHz with a rising tone, ending at the drop. */
    private fun riser(): FloatArray {
        var lp = 0.0; var p = 0.0
        val len = framesPerBeat * 8 / RATE
        return synth(len) { t ->
            val x = t / len
            val k = 1 - exp(-2 * PI * 150 * 60.0.pow(x) / RATE)
            lp += k * (noise() - lp)
            p += 100 * 8.0.pow(x) / RATE
            (0.8 * lp + 0.25 * sin(2 * PI * p)) * x.pow(1.5)
        }
    }

    /** Normalises to 0.9 full scale and doubles mono to interleaved stereo 16-bit. */
    private fun toTrack(mono: FloatArray, key: String): PcmTrack {
        var peak = 1e-6f
        for (v in mono) if (abs(v) > peak) peak = abs(v)
        val g = 0.9f / peak
        val out = ShortArray(mono.size * 2)
        for (i in mono.indices) { val s = (mono[i] * g * 32767f).toInt().toShort(); out[i * 2] = s; out[i * 2 + 1] = s }
        return PcmTrack(RATE, ShortBuffer.wrap(out), key)
    }
}
