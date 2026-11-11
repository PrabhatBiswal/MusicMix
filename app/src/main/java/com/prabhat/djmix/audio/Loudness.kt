package com.prabhat.djmix.audio

import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.log10

/**
 * Integrated loudness after ITU-R BS.1770: K-weighting (high shelf + RLB high-pass), 400 ms blocks with 75 %
 * overlap, -70 LUFS absolute and -10 LU relative gating. [gainDb] is the trim that brings a track to [TARGET_LUFS]
 * without pushing its peak past full scale, so every track lands at the same level and nobody needs to boost.
 */
object Loudness {
    const val TARGET_LUFS = -14.0
    const val MAX_BOOST_DB = 12.0
    const val MAX_CUT_DB = -20.0

    /** [lufs] is NaN for silence. [peak] is the raw sample peak, 0..1. */
    class Result(val lufs: Double, val peak: Double)

    fun gainDb(t: PcmTrack): Double = gainDb(measure(t))

    fun gainDb(r: Result): Double {
        if (r.lufs.isNaN() || r.peak <= 0) return 0.0
        val wanted = (TARGET_LUFS - r.lufs).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        val ceiling = 20 * log10(0.98 / r.peak)
        return minOf(wanted, ceiling)
    }

    fun measure(t: PcmTrack) = measure(t.pcm, t.frames, t.sampleRate)

    fun measure(pcm: ShortBuffer, frames: Int, rate: Int): Result {
        val fs = rate.toDouble()
        val shelf = Biquad().apply { highShelf(fs, 1681.97, 3.9998) }
        val rlb = Biquad().apply { highPass(fs, 38.135, 0.5003) }
        val hop = rate / 10 // 100 ms; a block is four hops
        val src = pcm.duplicate().apply { position(0) }
        val shorts = ShortArray(hop * 2)
        val buf = FloatArray(hop * 2)
        val hops = ArrayList<Double>() // energy per hop, both channels summed
        var peak = 0
        var left = frames
        while (left >= hop) {
            src.get(shorts, 0, hop * 2)
            for (i in 0 until hop * 2) { val v = shorts[i].toInt(); if (abs(v) > peak) peak = abs(v); buf[i] = v / 32768f }
            shelf.process(buf, hop); rlb.process(buf, hop)
            var e = 0.0
            for (i in 0 until hop * 2) e += buf[i].toDouble() * buf[i]
            hops += e
            left -= hop
        }
        val blocks = ArrayList<Double>() // loudness per 400 ms block
        for (i in 3 until hops.size) {
            val z = (hops[i - 3] + hops[i - 2] + hops[i - 1] + hops[i]) / (4.0 * hop)
            if (z > 0) blocks += -0.691 + 10 * log10(z)
        }
        val absGated = blocks.filter { it > -70 }
        if (absGated.isEmpty()) return Result(Double.NaN, peak / 32768.0)
        val relGate = loudnessOf(absGated) - 10
        val gated = absGated.filter { it > relGate }
        return Result(loudnessOf(gated.ifEmpty { absGated }), peak / 32768.0)
    }

    /** Mean of block loudness values in the power domain. */
    private fun loudnessOf(blocks: List<Double>): Double {
        var sum = 0.0
        for (b in blocks) sum += Math.pow(10.0, (b + 0.691) / 10)
        return -0.691 + 10 * log10(sum / blocks.size)
    }
}
