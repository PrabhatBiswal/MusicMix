package com.prabhat.djmix

import com.prabhat.djmix.audio.Deck
import com.prabhat.djmix.audio.PcmTrack
import com.prabhat.djmix.library.Stems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

class StemsTest {
    private fun flat(frames: Int, value: Int) = PcmTrack(48000, ShortBuffer.wrap(ShortArray(frames * 2) { value.toShort() }))

    @Test
    fun stemGainsPickVocalOrInstrumentalAndUnityPlaysTheOriginal() {
        val d = Deck().apply { load(flat(4000, 5000)); loadStems(flat(4000, 3000), flat(4000, 2000)); playing = true }
        val out = FloatArray(20)
        d.render(out, 10, 48000); assertEquals(5000 / 32768f, out[6], 1e-6f)
        d.vocGain = 0f; d.render(out, 10, 48000); assertEquals(2000 / 32768f, out[6], 1e-6f)
        d.vocGain = 1f; d.instGain = 0f; d.render(out, 10, 48000); assertEquals(3000 / 32768f, out[6], 1e-6f)
        d.vocGain = 0.5f; d.instGain = 1f; d.render(out, 10, 48000); assertEquals(3500 / 32768f, out[7], 1e-6f)
        d.keylock = true; d.tempo = 1.1; d.instGain = 0f
        val big = FloatArray(4096)
        repeat(3) { d.render(big, 2048, 48000) }
        var peak = 0f
        for (v in big) if (v > peak) peak = v
        assertTrue("keylock path renders the vocal stem", peak > 1000 / 32768f && peak < 2000 / 32768f)
        d.load(flat(100, 1)); assertTrue(!d.hasStems)
    }

    @Test
    fun wavHeaderIsCanonical() {
        val h = Stems.wavHeader(48000, 1_000_000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4)); assertEquals("WAVE", String(h, 8, 4)); assertEquals("data", String(h, 36, 4))
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, b.getShort(22).toInt()); assertEquals(48000, b.getInt(24)); assertEquals(16, b.getShort(34).toInt()); assertEquals(1_000_000, b.getInt(40))
    }
}
