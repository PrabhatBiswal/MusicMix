package com.prabhat.djmix.audio

import java.nio.ShortBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Constant-tempo BPM and beat-anchor estimate: onset envelope -> autocorrelation with a tempo prior ->
 * comb-filter refinement of period and phase. Same family as Mixxx's SoundTouch fallback analyzer.
 * ponytail: tracks with strong off-beats can come back doubled or halved; the UI has a x2 / half fix.
 */
object BpmAnalyzer {
    class Result(val bpm: Double, val firstBeat: Double)

    const val HOP = 256
    private const val MIN_BPM = 70.0
    private const val MAX_BPM = 180.0

    fun analyze(t: PcmTrack) = analyze(t.pcm, t.frames, t.sampleRate)

    fun analyze(pcm: ShortBuffer, frames: Int, rate: Int): Result {
        val hops = frames / HOP
        if (hops < 64) return Result(0.0, 0.0)

        // 1. Onset envelope: half-wave rectified rise in log energy per hop.
        // Bulk reads: per-element get() on a memory-mapped buffer is ~10x slower on a phone.
        val env = FloatArray(hops)
        val chunk = ShortArray(HOP * 2)
        val src = pcm.duplicate().apply { position(0) }
        var prev = 0.0
        for (h in 0 until hops) {
            src.get(chunk)
            var e = 0.0
            for (v in chunk) { val d = v.toDouble(); e += d * d }
            val le = ln(1 + e / HOP)
            env[h] = max(0.0, le - prev).toFloat()
            prev = le
        }

        // 2. Autocorrelation over the tempo range, weighted by a log-normal prior around 120 BPM.
        val hps = rate.toDouble() / HOP
        val minLag = (hps * 60 / MAX_BPM).toInt()
        val maxLag = (hps * 60 / MIN_BPM).toInt() + 1
        var bestLag = minLag
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (h in lag until hops) s += env[h] * env[h - lag]
            s /= hops - lag
            val w = exp(-0.5 * (log2(hps * 60 / lag / 120.0) / 0.9).pow(2))
            if (s * w > bestScore) { bestScore = s * w; bestLag = lag }
        }

        // 3. Refine: the (fractional period, phase) whose comb collects the most onset energy.
        // The autocorrelation peak is broad (onsets spread over a few hops), so search +-4 % coarsely, then fine.
        var bestP = bestLag.toDouble()
        var bestPhi = 0
        var best = -1.0
        fun comb(from: Double, to: Double, steps: Int) {
            for (k in 0..steps) {
                val p = from + (to - from) * k / steps
                for (phi in 0 until p.toInt()) {
                    var s = 0.0
                    var x = phi.toDouble()
                    var n = 0
                    while (x < hops) { s += env[x.roundToInt().coerceAtMost(hops - 1)]; x += p; n++ }
                    s /= n
                    if (s > best) { best = s; bestP = p; bestPhi = phi }
                }
            }
        }
        val wide = bestLag * 0.04
        comb(bestLag - wide, bestLag + wide, (wide * 20).toInt().coerceAtLeast(20))   // 0.1-hop steps
        val centre = bestP
        comb(centre - 0.1, centre + 0.1, 40)                                           // 0.005-hop steps
        return Result(hps * 60 / bestP, bestPhi.toDouble() * HOP)
    }
}
