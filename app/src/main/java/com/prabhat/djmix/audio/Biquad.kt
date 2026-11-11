package com.prabhat.djmix.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ cookbook biquad over interleaved stereo. Coefficients are only changed from the audio thread. */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private val x1 = DoubleArray(2); private val x2 = DoubleArray(2)
    private val y1 = DoubleArray(2); private val y2 = DoubleArray(2)
    var bypass = true
        private set

    fun lowPass(fs: Double, f0: Double, q: Double) {
        val w = 2 * PI * f0 / fs; val c = cos(w); val al = sin(w) / (2 * q)
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
    }

    fun highPass(fs: Double, f0: Double, q: Double) {
        val w = 2 * PI * f0 / fs; val c = cos(w); val al = sin(w) / (2 * q)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
    }

    fun peaking(fs: Double, f0: Double, gainDb: Double, q: Double) {
        if (abs(gainDb) < 0.01) { bypass = true; return }
        val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f0 / fs; val c = cos(w); val al = sin(w) / (2 * q)
        set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
    }

    /** Shelf slope S = 1 (the steepest without ripple). */
    fun lowShelf(fs: Double, f0: Double, gainDb: Double) {
        if (abs(gainDb) < 0.01) { bypass = true; return }
        val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f0 / fs; val c = cos(w)
        val beta = 2 * sqrt(a) * sin(w) / sqrt(2.0)
        set(
            a * ((a + 1) - (a - 1) * c + beta), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - beta),
            (a + 1) + (a - 1) * c + beta, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - beta
        )
    }

    fun highShelf(fs: Double, f0: Double, gainDb: Double) {
        if (abs(gainDb) < 0.01) { bypass = true; return }
        val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f0 / fs; val c = cos(w)
        val beta = 2 * sqrt(a) * sin(w) / sqrt(2.0)
        set(
            a * ((a + 1) + (a - 1) * c + beta), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - beta),
            (a + 1) - (a - 1) * c + beta, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - beta
        )
    }

    fun off() { bypass = true }

    private fun set(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0; this.a1 = a1 / a0; this.a2 = a2 / a0
        bypass = false
    }

    /** Mono variant over a plain array, running on channel 0's state. */
    fun processMono(buf: FloatArray, n: Int) {
        if (bypass) return
        var px1 = x1[0]; var px2 = x2[0]; var py1 = y1[0]; var py2 = y2[0]
        for (i in 0 until n) {
            val x = buf[i].toDouble()
            val y = b0 * x + b1 * px1 + b2 * px2 - a1 * py1 - a2 * py2
            px2 = px1; px1 = x; py2 = py1; py1 = y
            buf[i] = y.toFloat()
        }
        x1[0] = px1; x2[0] = px2; y1[0] = py1; y2[0] = py2
    }

    fun process(buf: FloatArray, frames: Int) {
        if (bypass) return
        for (ch in 0..1) {
            var px1 = x1[ch]; var px2 = x2[ch]; var py1 = y1[ch]; var py2 = y2[ch]
            var i = ch
            repeat(frames) {
                val x = buf[i].toDouble()
                val y = b0 * x + b1 * px1 + b2 * px2 - a1 * py1 - a2 * py2
                px2 = px1; px1 = x; py2 = py1; py1 = y
                buf[i] = y.toFloat()
                i += 2
            }
            x1[ch] = px1; x2[ch] = px2; y1[ch] = py1; y2[ch] = py2
        }
    }
}
