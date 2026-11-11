package com.prabhat.djmix

import com.prabhat.djmix.audio.Deck
import com.prabhat.djmix.audio.PcmTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.sin

class JogKeylockSlipTest {
    private val rate = 48000

    private fun sine(hz: Double, seconds: Int) =
        PcmTrack(rate, ShortBuffer.wrap(ShortArray(seconds * rate * 2) { i -> (16000 * sin(2 * PI * hz * (i / 2) / rate)).toInt().toShort() }))

    /** Renders [frames] in mixer-sized blocks (at least one call) and returns the interleaved output. [each] runs before every block, like jog events would. */
    private fun render(d: Deck, frames: Int, each: () -> Unit = {}): FloatArray {
        val out = FloatArray(frames * 2)
        val block = FloatArray(512 * 2)
        var done = 0
        do {
            val n = minOf(512, frames - done)
            block.fill(0f)
            each()
            d.render(block, n, rate)
            System.arraycopy(block, 0, out, done * 2, n * 2)
            done += n
        } while (done < frames)
        return out
    }

    /** Zero crossings per second of the left channel over the last second of [out]. */
    private fun crossingsPerSecond(out: FloatArray): Int {
        val frames = out.size / 2
        var c = 0
        for (f in frames - rate + 1 until frames) if ((out[f * 2] >= 0) != (out[(f - 1) * 2] >= 0)) c++
        return c / 2
    }

    @Test fun keylockKeepsPitchWhileVarispeedRaisesIt() {
        val d = Deck().apply { load(sine(440.0, 4)); playing = true; tempo = 1.1 }
        val plain = render(d, 2 * rate)
        assertEquals(484.0, crossingsPerSecond(plain).toDouble(), 15.0)          // 440 Hz * 1.1
        assertEquals(2.0 * rate * 1.1, d.pos, 1.0)

        d.jump(0.0); d.keylock = true
        val locked = render(d, 2 * rate)
        assertEquals(440.0, crossingsPerSecond(locked).toDouble(), 20.0)         // pitch held
        assertEquals(2.0 * rate * 1.1, d.pos, 1.0)                // but time still runs 10 % faster
        assertTrue(locked.drop(rate).maxOrNull()!! > 0.2f)        // and it is not silence
    }

    @Test fun scratchMovesBackwardsThenReleaseResumes() {
        val d = Deck().apply { load(sine(440.0, 10)); playing = true }
        d.jump(3.0 * rate); render(d, 0)
        d.scratchStart()
        render(d, rate) { d.scratch(-1.0) }                        // the finger keeps moving backwards for a second
        assertTrue("pos=${d.pos}", d.pos > 2.0 * rate && d.pos < 2.1 * rate) // one second back, minus the ramp
        assertTrue(d.scratching)
        d.scratchEnd()
        val before = d.pos
        render(d, rate)
        assertTrue(d.playing)
        assertTrue("pos=${d.pos}", d.pos - before > 0.9 * rate)
    }

    @Test fun stoppedHandHoldsThePlatter() {
        val d = Deck().apply { load(sine(440.0, 10)); playing = true }
        d.jump(3.0 * rate); render(d, 0)
        d.scratchStart(); d.scratch(1.0)
        render(d, Deck.FRESH_FRAMES + 8000)                       // the last jog event ages out, the rate ramps to zero
        val held = d.pos
        render(d, rate)
        assertEquals(held, d.pos, 1.0)
    }

    @Test fun slipLoopReleasesToTheTruePosition() {
        val d = Deck().apply { load(sine(440.0, 10)); bpm = 120.0; firstBeat = 0.0; playing = true; slip = true }
        d.beatLoop(1.0)                                            // 0 .. 24000
        render(d, 2 * rate)
        assertTrue("slipping=${d.slipping}", d.slipping)
        assertTrue("pos=${d.pos} loopOn=${d.loopOn} in=${d.loopIn} out=${d.loopOut}", d.pos < 24000)
        assertEquals(2.0 * rate, d.ghost, 1.0)
        d.toggleLoop()
        render(d, 10)
        assertFalse(d.slipping)
        assertEquals(2.0 * rate + 10, d.pos, 1.0)
    }

    @Test fun slipScratchWhilePausedReturnsToStart() {
        val d = Deck().apply { load(sine(440.0, 10)); slip = true }
        d.jump(1.0 * rate); render(d, 0)
        d.scratchStart(); d.scratch(2.0)
        render(d, 1000)
        assertTrue(d.pos > 1.0 * rate + 500)
        d.scratchEnd()
        render(d, 0)
        assertEquals(1.0 * rate, d.pos, 0.0)
        assertFalse(d.playing)
    }
}
