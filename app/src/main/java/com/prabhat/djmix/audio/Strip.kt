package com.prabhat.djmix.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * One mixer channel: trim -> VOX centre-cut -> 3-band EQ (kill = -26 dB, like Mixxx / a Pioneer DJM in EQ mode)
 * -> sweep filter. The UI writes the knob values; coefficients are recomputed on the audio thread only when a value
 * changes.
 */
class Strip(private val fs: Double) {
    @Volatile var trimDb = 0.0
    @Volatile var lowDb = 0.0
    @Volatile var midDb = 0.0
    @Volatile var highDb = 0.0
    @Volatile var killLow = false
    @Volatile var killMid = false
    @Volatile var killHigh = false
    /** -1 = low-pass fully closed, 0 = off, +1 = high-pass fully closed. */
    @Volatile var filter = 0.0
    /** Extra gain applied with the trim; the CFX macro fades the channel out at the knob's ends. */
    @Volatile var macroGain = 1f
    /**
     * Centre-cut "stem": the lead vocal sits in the middle of the stereo image between [VOX_LO_HZ] and [VOX_HI_HZ].
     * -1 = that band ducked out (instrumental-ish), 0 = untouched, +1 = only that band left (acapella-ish).
     * ponytail: mid/side band split, not source separation; kick and snare share the centre, reverb leaks through.
     */
    @Volatile var vox = 0.0
    /** Post-EQ, pre-fader peak of the last block, 0..1+. Written by the audio thread. */
    @Volatile var peak = 0f

    private val low = Biquad(); private val mid = Biquad(); private val high = Biquad(); private val flt = Biquad()
    private var lastLow = 0.0; private var lastMid = 0.0; private var lastHigh = 0.0; private var lastFilter = 0.0
    private val voxHp = Biquad().apply { highPass(fs, VOX_LO_HZ, 0.707) }
    private val voxLp = Biquad().apply { lowPass(fs, VOX_HI_HZ, 0.707) }
    private var mono = FloatArray(0)
    private var gainVox = 1f; private var gainRest = 1f

    fun process(buf: FloatArray, frames: Int) {
        val l = if (killLow) KILL_DB else lowDb
        val m = if (killMid) KILL_DB else midDb
        val h = if (killHigh) KILL_DB else highDb
        val f = filter
        if (l != lastLow) { low.lowShelf(fs, LOW_HZ, l); lastLow = l }
        if (m != lastMid) { mid.peaking(fs, MID_HZ, m, MID_Q); lastMid = m }
        if (h != lastHigh) { high.highShelf(fs, HIGH_HZ, h); lastHigh = h }
        if (f != lastFilter) {
            when {
                abs(f) < 0.02 -> flt.off()
                f < 0 -> flt.lowPass(fs, 20000.0 * 200.0.pow(f), FILTER_Q)   // 20 kHz -> 100 Hz
                else -> flt.highPass(fs, 20.0 * 500.0.pow(f), FILTER_Q)      // 20 Hz -> 10 kHz
            }
            lastFilter = f
        }
        val g = 10.0.pow(trimDb / 20).toFloat() * macroGain
        if (g != 1f) for (i in 0 until frames * 2) buf[i] *= g
        centreCut(buf, frames)
        low.process(buf, frames); mid.process(buf, frames); high.process(buf, frames); flt.process(buf, frames)
        var p = 0f
        for (i in 0 until frames * 2) p = max(p, abs(buf[i]))
        peak = p
    }

    /** mid = (L+R)/2 carries the vocal band v; everything else (the rest of mid, and side) is the instrumental. */
    private fun centreCut(buf: FloatArray, frames: Int) {
        val x = vox
        val targetVox = if (x < 0) (1 + x).toFloat() else 1f
        val targetRest = if (x > 0) (1 - x).toFloat() else 1f
        if (mono.size < frames) mono = FloatArray(frames)
        for (f in 0 until frames) mono[f] = (buf[f * 2] + buf[f * 2 + 1]) * 0.5f
        voxHp.processMono(mono, frames); voxLp.processMono(mono, frames)
        if (targetVox == 1f && targetRest == 1f && gainVox == 1f && gainRest == 1f) return // identity: skip the mix
        val dv = (targetVox - gainVox) / frames; val dr = (targetRest - gainRest) / frames
        var gv = gainVox; var gr = gainRest
        for (f in 0 until frames) {
            gv += dv; gr += dr
            val l = buf[f * 2]; val r = buf[f * 2 + 1]
            val m = (l + r) * 0.5f; val s = (l - r) * 0.5f
            val v = mono[f]; val rest = m - v
            buf[f * 2] = gv * v + gr * (rest + s)
            buf[f * 2 + 1] = gv * v + gr * (rest - s)
        }
        gainVox = targetVox; gainRest = targetRest
    }

    companion object {
        const val KILL_DB = -26.0
        const val VOX_LO_HZ = 180.0
        const val VOX_HI_HZ = 6000.0
        const val BOOST_DB = 12.0
        const val LOW_HZ = 200.0
        const val MID_HZ = 1000.0
        const val MID_Q = 0.7
        const val HIGH_HZ = 4000.0
        const val FILTER_Q = 1.4
    }
}
