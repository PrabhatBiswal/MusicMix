package com.prabhat.djmix

import com.prabhat.djmix.audio.Biquad
import com.prabhat.djmix.audio.Mixer
import com.prabhat.djmix.audio.PcmTrack
import com.prabhat.djmix.audio.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {
    private val fs = 48000.0
    private val frames = 48000

    private fun sine(hz: Double) = FloatArray(frames * 2) { i -> sin(2 * PI * hz * (i / 2) / fs).toFloat() }

    /** Gain in dB over the second half of a one-second sine (after the filter has settled). */
    private fun gainDb(buf: FloatArray): Double {
        var sum = 0.0
        for (i in frames until frames * 2) sum += buf[i].toDouble().pow(2)
        return 20 * log10(sqrt(sum / frames) / (1 / sqrt(2.0)))
    }

    private fun gainDb(hz: Double, setup: Biquad.() -> Unit): Double {
        val buf = sine(hz)
        Biquad().apply(setup).process(buf, frames)
        return gainDb(buf)
    }

    @Test fun lowPassPassesBelowAndCutsAbove() {
        assertEquals(0.0, gainDb(100.0) { lowPass(fs, 1000.0, 0.707) }, 0.3)
        assertTrue(gainDb(10000.0) { lowPass(fs, 1000.0, 0.707) } < -35)
    }

    @Test fun highPassPassesAboveAndCutsBelow() {
        assertEquals(0.0, gainDb(10000.0) { highPass(fs, 1000.0, 0.707) }, 0.3)
        assertTrue(gainDb(100.0) { highPass(fs, 1000.0, 0.707) } < -35)
    }

    @Test fun peakingBoostsAtCentre() {
        assertEquals(12.0, gainDb(1000.0) { peaking(fs, 1000.0, 12.0, 0.7) }, 0.3)
    }

    @Test fun lowShelfKillIsMinus26dBBelowAndFlatAbove() {
        assertEquals(-26.0, gainDb(40.0) { lowShelf(fs, 200.0, -26.0) }, 1.0)
        assertEquals(0.0, gainDb(5000.0) { lowShelf(fs, 200.0, -26.0) }, 0.3)
    }

    @Test fun highShelfKillIsMinus26dBAboveAndFlatBelow() {
        assertEquals(-26.0, gainDb(16000.0) { highShelf(fs, 4000.0, -26.0) }, 1.0)
        assertEquals(0.0, gainDb(200.0) { highShelf(fs, 4000.0, -26.0) }, 0.3)
    }

    @Test fun zeroGainBypasses() {
        val b = Biquad(); b.peaking(fs, 1000.0, 0.0, 0.7)
        assertTrue(b.bypass)
    }

    @Test fun stripKillOverridesKnob() {
        val s = Strip(fs).apply { lowDb = 12.0; killLow = true }
        val buf = sine(40.0)
        s.process(buf, frames)
        assertEquals(-26.0, gainDb(buf), 1.0)
        assertTrue(s.peak > 0f)
    }

    @Test fun crossfaderCurves() {
        val (a, b) = Mixer.xfade(0.5f, sharp = false)
        assertEquals(0.7071f, a, 1e-3f); assertEquals(0.7071f, b, 1e-3f)
        assertEquals(1f, Mixer.xfade(0f, sharp = false).first, 1e-6f)
        assertEquals(1f to 1f, Mixer.xfade(0.5f, sharp = true))
        assertEquals(0f to 1f, Mixer.xfade(1f, sharp = true))
    }

    @Test fun peaksCoverWholeTrack() {
        val n = PcmTrack.PEAK_FRAMES
        val t = PcmTrack(48000, ShortBuffer.wrap(ShortArray((3 * n + 1) * 2) { if (it == 3 * n * 2) 16384 else 0 }))
        assertEquals(4, t.peaks.size)
        assertEquals(0.5f, t.peaks[3], 1e-6f)
        assertEquals(0f, t.peaks[0], 0f)
    }
}

class SplitCueTest {
    @Test fun leftIsMasterMonoRightIsCue() {
        val out = floatArrayOf(0.8f, 0.2f, -0.4f, 0.0f)    // 2 frames stereo master
        val cue = FloatArray(2)
        Mixer.addMono(cue, floatArrayOf(0.6f, 0.2f, 2.0f, 2.0f), 2) // a cued deck, pre-fader
        Mixer.splitCue(out, cue, 2)
        assertEquals(0.5f, out[0], 1e-6f)   // (0.8 + 0.2) / 2
        assertEquals(0.4f, out[1], 1e-6f)   // (0.6 + 0.2) / 2
        assertEquals(-0.2f, out[2], 1e-6f)
        assertEquals(1.0f, out[3], 1e-6f)   // cue is clipped
    }
}

class SoftClipTest {
    @Test fun exactBelowKneeSmoothAboveNeverOverOne() {
        assertEquals(0.5f, Mixer.softClip(0.5f), 0f)
        assertEquals(-0.8f, Mixer.softClip(-0.8f), 0f)
        val y1 = Mixer.softClip(1.0f); val y2 = Mixer.softClip(1.41f); val y3 = Mixer.softClip(3f)
        assertTrue(y1 > 0.9f && y1 < 1f)
        assertTrue(y2 > y1 && y2 < 1f)
        assertTrue(y3 >= y2 && y3 <= 1f)                      // extreme input lands on exactly 1.0, never above
        assertEquals(-y2, Mixer.softClip(-1.41f), 1e-6f)
    }
}
