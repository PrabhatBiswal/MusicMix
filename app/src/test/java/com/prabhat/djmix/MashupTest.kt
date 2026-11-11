package com.prabhat.djmix

import com.prabhat.djmix.audio.Deck
import com.prabhat.djmix.audio.Mashup
import com.prabhat.djmix.audio.Mixer
import com.prabhat.djmix.audio.PcmTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import kotlin.math.pow

class MashupTest {
    private val rate = 44100
    private val buf = FloatArray(Mixer.BLOCK * 2)

    /** A silent 3-minute track with a grid at [bpm] and its hook on beat [hookBeat]. */
    private fun Deck.song(bpm: Double, hookBeat: Int) {
        load(PcmTrack(rate, ShortBuffer.wrap(ShortArray(rate * 180 * 2))))
        this.bpm = bpm; firstBeat = 1000.0; hotCues[0] = frameOfBeat(hookBeat.toDouble())
    }

    /** The mixer loop without the AudioTrack: mashup cues, then both decks render one block. */
    private fun Mixer.runUntil(stop: () -> Boolean) {
        var n = 0
        while (!stop()) {
            mashup.tick(Mixer.BLOCK, Mixer.RATE)
            buf.fill(0f); a.render(buf, Mixer.BLOCK, Mixer.RATE)
            buf.fill(0f); b.render(buf, Mixer.BLOCK, Mixer.RATE)
            assertTrue("ran away", ++n < 200_000)
        }
    }

    /** Just before the drop, x's beats to its hook must equal the clock's beats to the drop, scaled by the tempo ratio. */
    private fun handOver(bpmA: Double, bpmB: Double, expected: Mashup.Move, wanted: Mashup.Move? = null) {
        val m = Mixer()
        m.a.song(bpmA, 32); m.b.song(bpmB, 48)
        val hookB = m.b.hotCues[0]
        var done = false
        m.mashup.start(m.a)
        m.mashup.handOver(m.a, m.b, wanted) { done = true }
        assertEquals(expected.name, m.mashup.nextLabel)
        val drop = m.mashup.nextDrop
        // One segment after the first hook, on the phrase; a STOP drops 4 of the new song's beats of silence later.
        val stop = expected == Mashup.Move.STOP
        assertEquals(Mashup.SEGMENT + if (stop) Mashup.SILENCE * bpmA / bpmB else 0.0, drop, 1e-9)
        m.runUntil { m.mashup.beat >= drop - Mixer.BLOCK * bpmA * m.a.tempo / 60.0 / Mixer.RATE }
        val ratio = m.b.bpm * m.b.tempo / (bpmA * m.a.tempo)
        assertEquals((drop - m.mashup.beat) * ratio, m.b.beatAt(hookB) - m.b.beatAt(m.b.pos), 1e-6)
        assertTrue("playing, in the headphones", m.b.playing && m.cueB)
        if (stop) assertTrue("silence before the drop", !m.a.playing && m.b.volume == 0f)
        m.runUntil { m.mashup.clock === m.b }
        assertTrue(m.b.volume == 1f && !m.cueB)
        if (stop) assertEquals("the drop is louder", Mashup.BOOST, m.stripB.macroGain)
        m.runUntil { done }
        assertFalse(m.a.playing)
        assertEquals(0.0, m.stripA.filter, 0.0)
        m.runUntil { m.mashup.beat > drop + 8 + Mashup.GLIDE + 0.5 } // the last glide step fires in the block after
        assertEquals("back at its own tempo", 1.0, m.b.tempo, 1e-9)
    }

    @Test fun blendLandsTheHookOnThePhrase() = handOver(120.0, 124.0, Mashup.Move.BLEND)

    @Test fun farTempoStopsAndDropsAtItsOwnTempo() = handOver(128.0, 90.0, Mashup.Move.STOP)

    @Test fun cutSwitchesOnTheOne() = handOver(120.0, 124.0, Mashup.Move.CUT, Mashup.Move.CUT)

    @Test fun hooksTakeTurnsThenTheNewSongCarriesOn() {
        val m = Mixer()
        m.a.song(120.0, 32); m.b.song(120.0, 48)
        val hookA = m.a.hotCues[0]; val hookB = m.b.hotCues[0]
        var done = false
        m.mashup.start(m.a)
        m.mashup.handOver(m.a, m.b, Mashup.Move.HOOKS) { done = true }
        val t = m.mashup.nextDrop
        m.runUntil { m.mashup.beat >= t + 8 }
        assertTrue("the new hook", m.b.volume == 1f && m.a.volume == 0f)
        m.runUntil { m.mashup.beat >= t + 24 }
        assertTrue("the old hook again", m.a.volume == 1f && m.b.volume == 0f)
        assertEquals(m.mashup.beat - (t + 16), m.a.beatAt(m.a.pos) - m.a.beatAt(hookA), 1e-6)
        val end = t + 16.0 * (2 * Mashup.HOOK_ROUNDS - 2)
        m.runUntil { m.mashup.clock === m.b }
        assertEquals(end, m.mashup.beat, 0.1)
        assertEquals(m.mashup.beat - end, m.b.beatAt(m.b.pos) - m.b.beatAt(hookB), 1e-6)
        m.runUntil { done }
        assertFalse(m.a.playing)
    }

    /** 110 and 126 BPM are 14.5 % apart: they meet a third of the way, blend beat-matched, and the new one ends at its own tempo. */
    @Test fun aTempoGapIsBridged() {
        handOver(110.0, 126.0, Mashup.Move.BLEND)
    }

    @Test fun filterHandsOver() = handOver(120.0, 124.0, Mashup.Move.FILTER, Mashup.Move.FILTER)

    @Test fun farTempoTurnsABlendIntoAStop() = handOver(128.0, 90.0, Mashup.Move.STOP, Mashup.Move.BLEND)

    /** The echo out stops the clock deck a beat before the drop; the drop must still come. */
    @Test fun echoHandsOver() = handOver(128.0, 90.0, Mashup.Move.ECHO, Mashup.Move.ECHO)

    @Test fun theEndStopsTheClock() {
        val m = Mixer()
        m.a.song(124.0, 32)
        var done = false
        m.mashup.start(m.a)
        m.mashup.end(m.a) { done = true }
        m.runUntil { done }
        assertFalse(m.a.playing)
        assertNull(m.mashup.clock)
    }

    @Test fun rollLoopsDoNotStallTheClock() {
        val m = Mixer()
        m.a.song(128.0, 32); m.b.song(128.0, 32)
        m.mashup.start(m.a)
        m.mashup.handOver(m.a, m.b, Mashup.Move.ROLL) {}
        m.runUntil { m.mashup.beat >= Mashup.SEGMENT - 0.1 }
        assertTrue(m.a.loopOn)
        assertEquals(m.a.framesPerBeat, m.a.loopOut - m.a.loopIn, 1e-6)
        var blocks = 0
        m.runUntil { blocks++; m.mashup.clock === m.b }
        assertTrue("the drop came on time", blocks <= 8) // 0.1 beat is ~4.4 blocks at 128 BPM
        assertFalse(m.a.playing)
    }

    @Test fun meetingTempoFoldsAndBridges() {
        assertEquals(128.0, Mashup.meetTempo(128.0, 64.0)!!, 1e-9)      // half time
        assertEquals(120.0, Mashup.meetTempo(120.0, 124.0)!!, 1e-9)     // close: the new song follows
        assertEquals(110.0 * (126.0 / 110.0).pow(1.0 / 3), Mashup.meetTempo(110.0, 126.0)!!, 1e-9) // bridged, a third on the playing song
        assertNull(Mashup.meetTempo(128.0, 90.0))
        assertNull(Mashup.meetTempo(106.0, 140.2))
    }

    @Test fun firstSongStartsBeforeItsHookAndTheClockFollowsIt() {
        val m = Mixer()
        m.a.song(100.0, 32)
        m.mashup.start(m.a)
        m.runUntil { m.mashup.beat >= 0.0 }
        assertSame(m.a, m.mashup.clock)
        assertEquals(m.a.beatAt(m.a.hotCues[0]) + m.mashup.beat, m.a.beatAt(m.a.pos), 1e-6)
        assertEquals(0.0, m.stripA.filter, 0.01)
    }
}
