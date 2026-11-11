package com.prabhat.djmix

import com.prabhat.djmix.audio.Fx
import com.prabhat.djmix.audio.Recorder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class FxRecorderTest {
    private val rate = 48000

    @Test fun echoRepeatsOneBeatLaterAtDepth() {
        val fx = Fx(rate).apply { type = Fx.Type.ECHO; beats = 1.0; beatSeconds = 0.5; depth = 0.5f; on = true }
        val buf = FloatArray(rate * 2); buf[0] = 1f; buf[1] = 1f       // impulse at frame 0
        fx.process(buf, rate)
        assertEquals(1f, buf[0], 1e-6f)                                 // dry
        assertEquals(0f, buf[1000 * 2], 1e-6f)                          // nothing in between
        assertEquals(0.5f, buf[24000 * 2], 1e-6f)                       // first repeat after one beat
        assertEquals(0.5f, buf[24000 * 2 + 1], 1e-6f)
    }

    @Test fun rollLoopsTheLastQuarterBeat() {
        val fx = Fx(rate).apply { type = Fx.Type.ROLL; beats = 0.25; beatSeconds = 0.5; depth = 1f }
        val history = FloatArray(12000 * 2) { (it / 2) / 48000f }       // ramp, off: just recorded
        fx.process(history, 12000)
        assertEquals(11999 / 48000f, history[11999 * 2], 1e-7f)         // bypass leaves it untouched
        fx.on = true
        val out = FloatArray(12000 * 2)
        fx.process(out, 12000)
        assertEquals(6000 / 48000f, out[0], 1e-7f)                      // starts 6000 frames back
        assertEquals(8000 / 48000f, out[2000 * 2], 1e-7f)
        assertEquals(6000 / 48000f, out[6000 * 2], 1e-7f)               // and repeats
    }

    @Test fun flangerIsWetAndBounded() {
        val fx = Fx(rate).apply { type = Fx.Type.FLANGER; beats = 1.0; beatSeconds = 0.5; depth = 0.5f; on = true }
        val input = FloatArray(rate * 2) { 0.5f * sin(2 * PI * 440 * (it / 2) / rate).toFloat() }
        val buf = input.copyOf()
        fx.process(buf, rate)
        var diff = 0f; var peak = 0f
        for (i in rate until buf.size) { diff = maxOf(diff, kotlin.math.abs(buf[i] - input[i])); peak = maxOf(peak, kotlin.math.abs(buf[i])) }
        assertTrue("diff=$diff", diff > 0.05f)
        assertTrue("peak=$peak", peak < 1.5f)
    }

    @Test fun offIsBypass() {
        val fx = Fx(rate).apply { type = Fx.Type.ECHO; on = false }
        val input = FloatArray(4096) { it / 4096f }
        val buf = input.copyOf()
        fx.process(buf, 2048)
        assertArrayEquals(input, buf, 0f)
    }

    @Test fun recorderWritesAValidWav() {
        val f = File.createTempFile("djmix", ".wav").apply { deleteOnExit() }
        val r = Recorder(rate, f)
        val block = FloatArray(512 * 2) { if (it % 2 == 0) 0.5f else -0.5f }
        r.push(block, 512); r.push(block, 512)
        r.stop()
        assertEquals(44L + 2 * 512 * 2 * 2, f.length())
        val bytes = f.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("WAVE", String(bytes, 8, 4)); assertEquals("data", String(bytes, 36, 4))
        val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, h.getShort(22).toInt()); assertEquals(rate, h.getInt(24)); assertEquals(16, h.getShort(34).toInt())
        assertEquals(2 * 512 * 2 * 2, h.getInt(40))
        assertEquals(16383, h.getShort(44).toInt()); assertEquals(-16383, h.getShort(46).toInt())
    }
}
