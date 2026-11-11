package com.prabhat.djmix

import com.prabhat.djmix.audio.Loudness
import com.prabhat.djmix.audio.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class VoxLoudnessTest {
    private val fs = 48000.0
    private val frames = 48000

    /** One second of a 1 kHz sine, centred (L = R) or side-only (L = -R). */
    private fun sine(side: Boolean) = FloatArray(frames * 2) { i ->
        val v = sin(2 * PI * 1000 * (i / 2) / fs).toFloat()
        if (side && i % 2 == 1) -v else v
    }

    private fun run(buf: FloatArray, vox: Double): FloatArray {
        val strip = Strip(fs).apply { this.vox = vox }
        val out = buf.copyOf()
        var i = 0
        while (i < frames) { val n = minOf(512, frames - i); val block = out.copyOfRange(i * 2, (i + n) * 2); strip.process(block, n); block.copyInto(out, i * 2); i += n }
        return out
    }

    /** RMS of the second half, relative to a full-scale sine, in dB. */
    private fun levelDb(buf: FloatArray): Double {
        var sum = 0.0
        for (i in frames until frames * 2) sum += buf[i].toDouble() * buf[i]
        return 20 * log10(sqrt(sum / frames) / (1 / sqrt(2.0)) + 1e-12)
    }

    @Test
    fun voxCentreIsIdentityAndEndsSplitCentreFromSide() {
        val centre = sine(false); val side = sine(true)
        val same = run(centre, 0.0)
        for (i in same.indices) assertEquals(centre[i], same[i], 1e-5f)
        assertTrue("ducked vocal", levelDb(run(centre, -1.0)) < -30)
        assertTrue("side survives ducking", levelDb(run(side, -1.0)) > -0.5)
        assertTrue("vocal only keeps centre", levelDb(run(centre, 1.0)) > -1.5)
        assertTrue("vocal only drops side", levelDb(run(side, 1.0)) < -30)
    }

    @Test
    fun loudnessOfAQuietSineAndItsGain() {
        val n = fs.toInt() * 3
        val pcm = ShortArray(n * 2) { i -> (0.1 * 32767 * sin(2 * PI * 997 * (i / 2) / fs)).toInt().toShort() }
        val r = Loudness.measure(ShortBuffer.wrap(pcm), n, fs.toInt())
        assertEquals(-20.0, r.lufs, 0.5)
        assertEquals(0.1, r.peak, 0.01)
        assertEquals(6.0, Loudness.gainDb(r), 0.5)
        val silent = Loudness.measure(ShortBuffer.wrap(ShortArray(n * 2)), n, fs.toInt())
        assertTrue(silent.lufs.isNaN())
        assertEquals(0.0, Loudness.gainDb(silent), 0.0)
    }
}
