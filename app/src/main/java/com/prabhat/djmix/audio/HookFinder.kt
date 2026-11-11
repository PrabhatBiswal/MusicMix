package com.prabhat.djmix.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the hook of a track: the first loud occurrence of its chorus. The chorus is the 8-bar section whose harmony
 * (a 12-note chroma per beat) comes back most often elsewhere in the song, weighted by loudness; its earliest loud
 * repeat is the entry point. Without a clear repeat it falls back to the biggest loudness rise ([riseFrame]).
 * ponytail: one FFT per beat on a 4x decimated mono mix, constant beat grid; no vocal detection.
 */
object HookFinder {
    const val WINDOW = 16
    /** Beats compared as one section: 8 bars. */
    const val SECTION = 32
    private const val FFT = 2048
    private const val DECIMATE = 4

    /** Frame of the hook, on a bar of the grid, or null without a grid or for a track under 48 beats. Off the main thread. */
    fun hookFrame(t: PcmTrack, bpm: Double, firstBeat: Double): Double? {
        if (bpm <= 0 || t.frames == 0) return null
        val fpb = t.sampleRate * 60.0 / bpm
        val beats = ((t.frames - firstBeat) / fpb).toInt()
        if (beats < 3 * WINDOW) return null
        val energy = energy(t, fpb, firstBeat, beats)
        val b = chorusBeat(chroma(t, fpb, firstBeat, beats), energy) ?: riseBeat(energy)
        return if (b < 0) null else firstBeat + b * fpb
    }

    /**
     * The older pick: the bar where the next 16 beats get loudest over the previous 16. Kept so a hot cue it placed
     * can be recognised and moved to the chorus.
     */
    fun riseFrame(t: PcmTrack, bpm: Double, firstBeat: Double): Double? {
        if (bpm <= 0 || t.frames == 0) return null
        val fpb = t.sampleRate * 60.0 / bpm
        val beats = ((t.frames - firstBeat) / fpb).toInt()
        if (beats < 3 * WINDOW) return null
        val b = riseBeat(energy(t, fpb, firstBeat, beats))
        return if (b < 0) null else firstBeat + b * fpb
    }

    /** Mean square of the peaks per beat. */
    private fun energy(t: PcmTrack, fpb: Double, firstBeat: Double, beats: Int): DoubleArray {
        val peaks = t.peaks
        val pf = PcmTrack.PEAK_FRAMES
        return DoubleArray(beats) { b ->
            val i0 = ((firstBeat + b * fpb) / pf).toInt().coerceIn(0, peaks.size - 1)
            val i1 = ((firstBeat + (b + 1) * fpb) / pf).toInt().coerceIn(i0 + 1, peaks.size)
            var sum = 0.0
            for (i in i0 until i1) sum += peaks[i].toDouble() * peaks[i]
            sum / (i1 - i0)
        }
    }

    private fun riseBeat(energy: DoubleArray): Int {
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        var b = WINDOW
        while (b + WINDOW <= energy.size) {
            var before = 0.0; var after = 0.0
            for (k in 0 until WINDOW) { before += energy[b - WINDOW + k]; after += energy[b + k] }
            val score = (after - before) / WINDOW + 0.25 * after / WINDOW
            if (score > bestScore) { bestScore = score; best = b }
            b += 4
        }
        return best
    }

    /** First beat of the chorus's earliest loud occurrence, or null when nothing repeats clearly. */
    fun chorusBeat(chroma: Array<FloatArray>, energy: DoubleArray): Int? {
        val n = (energy.size - SECTION) / 4 + 1 // candidate sections, one per bar
        if (n < 3) return null
        val loud = DoubleArray(n) { i -> (0 until SECTION).sumOf { energy[i * 4 + it] } / SECTION }
        val maxLoud = loud.max()
        if (maxLoud <= 0) return null
        val sim = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + SECTION / 4 until n) {
            var s = 0.0
            for (k in 0 until SECTION) { val a = chroma[i * 4 + k]; val b = chroma[j * 4 + k]; for (p in 0..11) s += a[p] * b[p] }
            sim[i][j] = s / SECTION; sim[j][i] = sim[i][j]
        }
        // A section's repetition: its two best matches, each at least a section away from it and from each other.
        val best1 = DoubleArray(n)
        val score = DoubleArray(n) { i ->
            val far = (0 until n).filter { abs(it - i) >= SECTION / 4 }
            val j1 = far.maxByOrNull { sim[i][it] } ?: return@DoubleArray 0.0
            val j2 = far.filter { abs(it - j1) >= SECTION / 4 }.maxByOrNull { sim[i][it] }
            best1[i] = sim[i][j1]
            (sim[i][j1] + (j2?.let { sim[i][it] } ?: 0.0)) / 2 * sqrt(loud[i] / maxLoud)
        }
        val c = score.indices.maxBy { score[it] }
        if (score[c] < MIN_REPEAT) return null
        // Its occurrences: sections nearly as close to it as its best match, aligned on a local peak, and loud.
        val first = (0 until n).firstOrNull { j ->
            j == c || (abs(j - c) >= SECTION / 4 && sim[c][j] >= 0.8 * best1[c] && loud[j] >= 0.7 * loud[c] &&
                sim[c][j] >= sim[c].getOrElse(j - 1) { 0.0 } && sim[c][j] >= sim[c].getOrElse(j + 1) { 0.0 })
        } ?: c
        // The candidates sit on whole bars from the first beat, which need not be a downbeat: snap to the beat within
        // ±3 where the music gets loudest, the chorus's own first beat.
        val at = first * 4
        return (maxOf(4, at - 3)..minOf(energy.size - 4, at + 3)).maxBy { b -> (0 until 4).sumOf { energy[b + it] - energy[b - 4 + it] } }
    }

    /**
     * Per beat: note-class energy 100 Hz-4 kHz (C = 0), summing to 1. [centred]: minus the song's average colour, unit
     * length, so similarity measures what differs from the key's usual colour.
     */
    internal fun chroma(t: PcmTrack, fpb: Double, firstBeat: Double, beats: Int, centred: Boolean = true): Array<FloatArray> {
        val rate = t.sampleRate.toDouble() / DECIMATE
        val noteOf = IntArray(FFT / 2) { k ->
            val f = k * rate / FFT
            if (f < 100 || f > 4000) -1 else ((12 * log2(f / 440.0)).roundToInt() + 69) % 12
        }
        val win = FloatArray(FFT) { (0.5 - 0.5 * cos(2 * PI * it / (FFT - 1))).toFloat() }
        val re = FloatArray(FFT); val im = FloatArray(FFT)
        val raw = ShortArray(FFT * DECIMATE * 2)
        val src = t.pcm.duplicate()
        val out = Array(beats) { FloatArray(12) }
        for (b in 0 until beats) {
            val at = (firstBeat + b * fpb).toInt()
            if (at + FFT * DECIMATE > t.frames) break
            src.position(at * 2); src.get(raw)
            for (i in 0 until FFT) {
                var s = 0
                for (k in i * DECIMATE * 2 until (i + 1) * DECIMATE * 2) s += raw[k]
                re[i] = s * win[i]; im[i] = 0f
            }
            fft(re, im)
            val v = out[b]
            for (k in 1 until FFT / 2) { val p = noteOf[k]; if (p >= 0) v[p] += sqrt(re[k] * re[k] + im[k] * im[k]) }
            val sum = v.sum()
            if (sum > 0) for (p in 0..11) v[p] /= sum
        }
        if (!centred) return out
        val mean = FloatArray(12)
        for (v in out) for (p in 0..11) mean[p] += v[p] / beats
        for (v in out) {
            if (v.sum() == 0f) continue // silence or past the end: no colour, similarity 0
            for (p in 0..11) v[p] -= mean[p]
            val norm = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
            if (norm > 1e-6f) for (p in 0..11) v[p] /= norm
        }
        return out
    }

    /** In-place radix-2 complex FFT; [re].size must be a power of two. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var x = re[i]; re[i] = re[j]; re[j] = x; x = im[i]; im[i] = im[j]; im[j] = x }
        }
        var len = 2
        while (len <= n) {
            val wr = cos(-2 * PI / len); val wi = sin(-2 * PI / len)
            for (i in 0 until n step len) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = (re[b] * cr - im[b] * ci).toFloat(); val ti = (re[b] * ci + im[b] * cr).toFloat()
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
            }
            len = len shl 1
        }
    }

    /** Below this repetition score (mean similarity of the two best repeats, loudness-weighted) nothing repeats clearly. */
    private const val MIN_REPEAT = 0.25
}
