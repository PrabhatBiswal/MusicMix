package com.prabhat.djmix

import com.prabhat.djmix.audio.Deck
import com.prabhat.djmix.audio.PcmTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer

class DeckTest {
    /** Ramp track: every sample equals its frame index, so output values reveal the read position. */
    private fun ramp(frames: Int, rate: Int) =
        PcmTrack(rate, ShortBuffer.wrap(ShortArray(frames * 2) { (it / 2).toShort() }))

    private fun deckWith(frames: Int, rate: Int = 48000) = Deck().apply { load(ramp(frames, rate)); playing = true }

    @Test fun identityAtTempo1() {
        val d = deckWith(1000); val out = FloatArray(20)
        d.render(out, 10, 48000)
        assertEquals(3 / 32768f, out[6], 1e-6f) // output frame 3, left
        assertEquals(3 / 32768f, out[7], 1e-6f) // output frame 3, right
        assertEquals(10.0, d.pos, 0.0)
    }

    @Test fun tempo2ReadsTwiceAsFast() {
        val d = deckWith(1000).apply { tempo = 2.0 }; val out = FloatArray(20)
        d.render(out, 10, 48000)
        assertEquals(6 / 32768f, out[6], 1e-6f) // output frame 3 = source frame 6
        assertEquals(20.0, d.pos, 0.0)
    }

    @Test fun sourceRateFoldsIntoSpeed() {
        val d = deckWith(1000, rate = 24000); val out = FloatArray(20)
        d.render(out, 10, 48000) // 24 kHz source at 48 kHz out: half a source frame per output frame
        assertEquals(1.5f / 32768f, out[6], 1e-6f) // interpolated between frames 1 and 2
        assertEquals(5.0, d.pos, 0.0)
    }

    @Test fun stopsAtEnd() {
        val d = deckWith(5); val out = FloatArray(20)
        d.render(out, 10, 48000)
        assertFalse(d.playing)
        assertEquals(4.0, d.pos, 0.0)
    }

    @Test fun jumpAppliesWhilePausedAndCueReturns() {
        val d = deckWith(1000).apply { playing = false }
        d.jump(100.0); d.render(FloatArray(20), 10, 48000)
        assertEquals(100.0, d.pos, 0.0)
        d.cue() // paused: set cue here
        d.playing = true; d.render(FloatArray(20), 10, 48000)
        assertEquals(110.0, d.pos, 0.0)
        d.cue() // playing: back to cue and pause
        d.render(FloatArray(20), 10, 48000)
        assertFalse(d.playing)
        assertEquals(100.0, d.pos, 0.0)
    }
}

class DeckGridTest {
    private fun ramp(frames: Int) = PcmTrack(48000, ShortBuffer.wrap(ShortArray(frames * 2) { (it / 2).toShort() }))

    /** 120 BPM at 48 kHz = 24000 frames per beat, first beat at frame 0. */
    private fun deck(frames: Int = 48000 * 20) = Deck().apply { load(ramp(frames)); bpm = 120.0; firstBeat = 0.0; playing = true }

    private fun render(d: Deck, frames: Int): FloatArray { val out = FloatArray(frames * 2); d.render(out, frames, 48000); return out }

    @Test fun loopWrapsSeamlessly() {
        val d = deck()
        d.quantize = false
        d.jump(100.0); render(d, 0)
        d.loopInHere(); d.jump(110.0); render(d, 0); d.loopOutHere()
        assertEquals(100.0, d.loopIn, 0.0); assertEquals(110.0, d.loopOut, 0.0); assertTrue(d.loopOn)
        d.jump(100.0)
        val out = render(d, 25)
        assertEquals(100f / 32768f, out[0], 1e-6f)   // frame 100
        assertEquals(100f / 32768f, out[20], 1e-6f)  // output frame 10 wrapped back to 100
        assertEquals(105.0, d.pos, 0.0)              // 25 frames through a 10-frame loop
    }

    @Test fun beatLoopIsQuantizedToTheBeatBefore() {
        val d = deck()
        d.jump(30000.0); render(d, 0)            // 1.25 beats in
        d.beatLoop(4.0)
        assertEquals(24000.0, d.loopIn, 0.0)
        assertEquals(24000.0 + 4 * 24000, d.loopOut, 0.0)
        d.loopHalve(); assertEquals(24000.0 + 2 * 24000, d.loopOut, 0.0)
        d.loopDouble(); assertEquals(24000.0 + 4 * 24000, d.loopOut, 0.0)
    }

    @Test fun hotCueSetsThenJumps() {
        val d = deck()
        d.jump(25000.0); render(d, 0)
        d.hotCue(0)                               // quantized to the nearest beat
        assertEquals(24000.0, d.hotCues[0], 0.0)
        d.jump(0.0); render(d, 0); d.playing = false
        d.hotCue(0); render(d, 0)
        assertEquals(24000.0, d.pos, 0.0); assertTrue(d.playing)
    }

    @Test fun beatJumpMovesWholeBeats() {
        val d = deck()
        d.jump(24000.0); render(d, 0)
        d.beatJump(2); render(d, 0)
        assertEquals(72000.0, d.pos, 0.0)
        d.beatJump(-1); render(d, 0)
        assertEquals(48000.0, d.pos, 0.0)
    }

    @Test fun syncMatchesTempoAndPhase() {
        val leader = deck().apply { bpm = 128.0 }
        val follower = deck().apply { bpm = 120.0 }
        leader.jump(leader.frameOfBeat(1.25)); render(leader, 0)
        follower.jump(follower.frameOfBeat(0.6)); render(follower, 0)
        follower.syncTo(leader); render(follower, 0)
        assertEquals(128.0 / 120.0, follower.tempo, 1e-9)
        assertEquals(0.25, follower.beatAt(follower.pos), 1e-9)   // nearest point with the leader's phase
    }

    @Test fun syncIsClampedToTheFaderRange() {
        val leader = deck().apply { bpm = 170.0 }
        val follower = deck().apply { bpm = 120.0 }
        follower.syncTo(leader)
        assertEquals(1 + Deck.MAX_SYNC, follower.tempo, 1e-9)
    }
}
