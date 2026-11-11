package com.prabhat.djmix

import com.prabhat.djmix.audio.BpmAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class BpmAnalyzerTest {
    private val rate = 48000

    /** [seconds] of silence with a 5 ms decaying 1 kHz click on every beat, the first at [offsetSec]. */
    private fun clicks(bpm: Double, offsetSec: Double, seconds: Int = 60): ShortBuffer {
        val frames = seconds * rate
        val pcm = ShortArray(frames * 2)
        val period = 60.0 / bpm * rate
        var beat = offsetSec * rate
        while (beat < frames) {
            val start = beat.toInt()
            for (i in 0 until 240) {
                val f = start + i
                if (f >= frames) break
                val v = (16000 * exp(-i / 60.0) * sin(2 * PI * 1000 * i / rate)).toInt().toShort()
                pcm[f * 2] = v; pcm[f * 2 + 1] = v
            }
            beat += period
        }
        return ShortBuffer.wrap(pcm)
    }

    @Test fun finds128BpmAndTheFirstBeat() {
        val r = BpmAnalyzer.analyze(clicks(128.0, 0.3), 60 * rate, rate)
        assertEquals(128.0, r.bpm, 0.2)
        assertEquals(0.3 * rate, r.firstBeat, 2.0 * BpmAnalyzer.HOP)
    }

    @Test fun finds85BpmWithoutDoubling() {
        val r = BpmAnalyzer.analyze(clicks(85.0, 1.0), 60 * rate, rate)
        assertEquals(85.0, r.bpm, 0.2)
    }

    /** The on-device test track: kick on every beat, hat on the off-beat, a continuous bass note, at 44.1 kHz. */
    private fun music(bpm: Double, seconds: Int = 60, rate: Int = 44100): ShortBuffer {
        val frames = seconds * rate
        val pcm = ShortArray(frames * 2)
        val spb = 60.0 / bpm
        val rnd = java.util.Random(1)
        val notes = doubleArrayOf(1.0, 1.189, 1.335, 1.498)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val tb = t % spb
            val beat = (t / spb).toInt()
            val kick = 0.8 * exp(-tb * 18) * sin(2 * PI * (50 + 120 * exp(-tb * 40)) * tb)
            val hat = 0.25 * exp(-((t + spb / 2) % spb) * 60) * (rnd.nextDouble() * 2 - 1)
            val bass = 0.35 * sin(2 * PI * 55.0 * notes[(beat / 8) % 4] * t) * (if (tb > 0.02) 1.0 else tb / 0.02)
            val v = ((kick + hat + bass).coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
            pcm[i * 2] = v; pcm[i * 2 + 1] = v
        }
        return ShortBuffer.wrap(pcm)
    }

    @Test fun finds128BpmInMusicWithBassAndHats() {
        val r = BpmAnalyzer.analyze(music(128.0), 60 * 44100, 44100)
        assertEquals(128.0, r.bpm, 0.3)
    }

    @Test fun finds100BpmInMusicWithBassAndHats() {
        val r = BpmAnalyzer.analyze(music(100.0), 60 * 44100, 44100)
        assertEquals(100.0, r.bpm, 0.3)
    }

    @Test fun tooShortGivesZero() {
        assertEquals(0.0, BpmAnalyzer.analyze(ShortBuffer.wrap(ShortArray(2000)), 1000, rate).bpm, 0.0)
    }
}
