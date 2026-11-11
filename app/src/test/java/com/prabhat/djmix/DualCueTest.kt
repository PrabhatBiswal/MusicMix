package com.prabhat.djmix

import com.prabhat.djmix.audio.Mixer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualCueTest {
    @Test
    fun phonesMixBlendsCueAndMasterAndLimits() {
        val master = floatArrayOf(0.4f, -0.4f, 0.2f, 0.2f)
        val cue = floatArrayOf(0.1f, 0.1f, -0.6f, 0.6f)
        val out = FloatArray(4)
        Mixer.phonesMix(master, cue, out, 2, 0f); assertArrayEquals(cue, out, 1e-6f)
        Mixer.phonesMix(master, cue, out, 2, 1f); assertArrayEquals(master, out, 1e-6f)
        Mixer.phonesMix(master, cue, out, 2, 0.5f); assertEquals(0.25f, out[0], 1e-6f); assertEquals(0.4f, out[3], 1e-6f)
        Mixer.phonesMix(floatArrayOf(1f, 1f), floatArrayOf(1f, 1f), out, 1, 0.5f)
        assertTrue("limited", out[0] > 0.8f && out[0] < 1f)
        Mixer.phonesMix(master, cue, out, 2, 0f, 0.5f); assertEquals(0.05f, out[0], 1e-6f); assertEquals(0.3f, out[3], 1e-6f)
        val split = floatArrayOf(0.4f, 0.2f)
        Mixer.splitCue(split, floatArrayOf(0.8f), 1, 0.25f); assertEquals(0.3f, split[0], 1e-6f); assertEquals(0.2f, split[1], 1e-6f)
    }
}
