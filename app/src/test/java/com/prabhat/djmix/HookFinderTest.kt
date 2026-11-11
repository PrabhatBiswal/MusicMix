package com.prabhat.djmix

import com.prabhat.djmix.audio.HookFinder
import com.prabhat.djmix.audio.PcmTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class HookFinderTest {
    @Test
    fun hookLandsWhereTheTrackGetsLoud() {
        val rate = 48000; val bpm = 120.0; val fpb = rate * 60 / bpm // 24000 frames
        val beats = 96
        val frames = (beats * fpb).toInt()
        // quiet verse for 40 beats, loud chorus from beat 40, quieter again from beat 72
        val pcm = ShortArray(frames * 2) { i ->
            val beat = (i / 2) / fpb
            val amp = if (beat < 40) 2000 else if (beat < 72) 20000 else 6000
            (if ((i / 2) % 50 < 25) amp else -amp).toShort()
        }
        val t = PcmTrack(rate, ShortBuffer.wrap(pcm))
        val hook = HookFinder.hookFrame(t, bpm, 0.0)
        assertNotNull(hook)
        assertEquals(40.0, hook!! / fpb, 0.01)
    }

    /**
     * Intro, verse, chorus, verse, chorus, a quiet breakdown, a loud one-off drop, chorus, outro. The loudness rise
     * picks the drop; the chorus finder must pick the first chorus.
     */
    @Test fun hookIsTheFirstChorusNotTheBiggestRise() = chorusSong(lead = 0)

    /** Two beats of silence first: the sections no longer start on whole bars of the grid. */
    @Test fun hookSnapsToTheChorusDownbeat() = chorusSong(lead = 2)

    private fun chorusSong(lead: Int) {
        val rate = 22050; val bpm = 120.0; val fpb = rate * 60 / bpm
        val verse = listOf(listOf(9, 0, 4), listOf(5, 9, 0), listOf(0, 4, 7), listOf(7, 11, 2)) // Am F C G
        val chorus = listOf(listOf(2, 6, 9), listOf(4, 8, 11), listOf(11, 3, 6), listOf(6, 9, 1)) // D E B F#m
        val drop = listOf(listOf(10, 2, 5), listOf(3, 7, 10), listOf(8, 0, 3), listOf(1, 5, 8))   // Bb Eb Ab Db
        // (chords, beats, amplitude)
        val form = listOf(Triple(verse, 16, 0.15), Triple(verse, 32, 0.3), Triple(chorus, 32, 0.6), Triple(verse, 32, 0.3),
            Triple(chorus, 32, 0.6), Triple(listOf(listOf(9)), 16, 0.05), Triple(drop, 32, 0.9), Triple(chorus, 32, 0.6), Triple(verse, 16, 0.2))
        val beats = lead + form.sumOf { it.second }
        val pcm = ShortArray((beats * fpb).toInt() * 2)
        var start = lead
        for ((chords, len, amp) in form) {
            for (f in (start * fpb).toInt() until ((start + len) * fpb).toInt()) {
                val beat = f / fpb - start
                val notes = chords[(beat / 8).toInt() % chords.size]
                val x = notes.sumOf { n -> sin(2 * PI * 220.0 * 2.0.pow((n - 9) / 12.0 + 1) * f / rate) } / notes.size
                pcm[f * 2] = (x * amp * 30000).toInt().toShort(); pcm[f * 2 + 1] = pcm[f * 2]
            }
            start += len
        }
        val t = PcmTrack(rate, ShortBuffer.wrap(pcm))
        if (lead == 0) assertEquals("the old pick is the drop", 160.0, HookFinder.riseFrame(t, bpm, 0.0)!! / fpb, 0.01)
        assertEquals(48.0 + lead, HookFinder.hookFrame(t, bpm, 0.0)!! / fpb, 0.01)
    }
}
