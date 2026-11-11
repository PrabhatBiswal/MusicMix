package com.prabhat.djmix.audio

import kotlin.math.sqrt

/**
 * The key of a track from its average note colour: the best match among the 24 Krumhansl-Kessler major and minor
 * profiles, as a Camelot code (neighbours on the wheel mix without clashing). ponytail: one key per song, no
 * modulation tracking; [Key.confidence] is the lead over the runner-up, small = treat as unknown.
 */
object KeyFinder {
    class Key(val camelot: String, val name: String, val confidence: Double)

    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    private val NOTES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    fun key(t: PcmTrack, bpm: Double, firstBeat: Double): Key? {
        if (bpm <= 0) return null
        val fpb = t.sampleRate * 60.0 / bpm
        val beats = ((t.frames - firstBeat) / fpb).toInt()
        if (beats < 16) return null
        val avg = DoubleArray(12)
        for (v in HookFinder.chroma(t, fpb, firstBeat, beats, centred = false)) for (p in 0..11) avg[p] += v[p].toDouble()
        return key(avg)
    }

    /** [chroma]: energy per note class, C = 0. */
    fun key(chroma: DoubleArray): Key {
        val score = DoubleArray(24) { k -> corr(chroma, if (k < 12) MAJOR else MINOR, k % 12) }
        val order = score.indices.sortedByDescending { score[it] }
        val k = order[0]
        val tonic = k % 12
        val minor = k >= 12
        val wheel = ((if (minor) tonic + 3 else tonic) * 7 + 7) % 12 + 1 // C major = 8B, A minor = 8A
        return Key("$wheel${if (minor) "A" else "B"}", NOTES[tonic] + if (minor) "m" else "", score[k] - score[order[1]])
    }

    /** Pearson correlation of [x] with [profile] rotated to start on [tonic]. */
    private fun corr(x: DoubleArray, profile: DoubleArray, tonic: Int): Double {
        val mx = x.average(); val mp = profile.average()
        var sxy = 0.0; var sxx = 0.0; var spp = 0.0
        for (i in 0..11) {
            val a = x[(tonic + i) % 12] - mx; val b = profile[i] - mp
            sxy += a * b; sxx += a * a; spp += b * b
        }
        return if (sxx == 0.0) 0.0 else sxy / sqrt(sxx * spp)
    }
}
