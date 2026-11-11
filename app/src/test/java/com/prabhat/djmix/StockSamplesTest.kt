package com.prabhat.djmix

import com.prabhat.djmix.audio.StockSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StockSamplesTest {
    @Test
    fun bankHasEightNormalisedSoundsAndLoopsAreSixteenBeats() {
        val pack = StockSamples.pack()
        assertEquals(StockSamples.NAMES, pack.map { it.name })
        val loopFrames = Math.round(60.0 / StockSamples.BPM * StockSamples.RATE * StockSamples.LOOP_BEATS)
        for (s in pack) {
            val t = s.track
            assertTrue(s.name, t.frames > 0)
            if (s.loop) assertEquals(s.name, loopFrames, t.frames.toLong())
            var peak = 0
            for (i in 0 until t.frames * 2) { val v = abs(t.pcm.get(i).toInt()); if (v > peak) peak = v }
            assertTrue("${s.name} peak $peak", peak in 28000..32767)
        }
    }
}
