package com.prabhat.djmix

import com.prabhat.djmix.audio.PcmTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.random.Random

class StitchTest {
    private val rate = 48000
    private val frames = rate * 12
    /** Music-like: two tones plus noise, different in every frame, so a misplaced seam cannot match. */
    private val pcm = Random(7).let { r -> ShortArray(frames * 2) { i -> val f = i / 2; (6000 * sin(f * 0.031) + 4000 * sin(f * 0.0071 + i % 2) + r.nextInt(-3000, 3000)).toInt().toShort() } }

    private fun part(dir: File, name: String, from: Int, to: Int) = File(dir, name).apply {
        val b = ByteBuffer.allocate((to - from) * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from * 2 until to * 2) b.putShort(pcm[i])
        writeBytes(b.array())
    }

    /** Slices start a second early and lose a few hundred samples, like an Opus decoder starting mid-stream. */
    @Test fun slicesJoinBitExact() {
        val dir = createTempDir()
        val cuts = longArrayOf(0, 4L * rate, 8L * rate, frames.toLong())
        val lost = intArrayOf(0, 312, 2112)
        val parts = (0..2).map { i ->
            val start = if (i == 0) 0 else (cuts[i] - rate).toInt() + lost[i]
            val end = if (i == 2) frames else (cuts[i + 1] + rate / 5).toInt()
            part(dir, "s$i", start, end)
        }
        val guesses = (0..2).map { i -> if (i == 0) 0L else cuts[i] - rate } // the timestamps ignore the lost samples
        val out = File(dir, "out")
        PcmTrack.stitch(out, parts, cuts, guesses, rate)
        val got = RandomAccessFile(out, "r").use { raf ->
            assertEquals(rate, raf.readInt()); raf.readInt()
            val bytes = ByteArray((raf.length() - 8).toInt()); raf.readFully(bytes)
            ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        }
        assertEquals(pcm.size, got.size)
        assertTrue("bit exact", pcm.contentEquals(got))
        dir.deleteRecursively()
    }

    @Test fun aSeamThatMatchesNowhereIsRefused() {
        val dir = createTempDir()
        val cuts = longArrayOf(0, 6L * rate, frames.toLong())
        val parts = listOf(part(dir, "s0", 0, 6 * rate + rate / 5), File(dir, "s1").apply { writeBytes(Random(9).nextBytes((5 * rate) * 4)) })
        val refused = try { PcmTrack.stitch(File(dir, "out"), parts, cuts, listOf(0L, 5L * rate), rate); false } catch (e: IOException) { true }
        assertTrue(refused)
        dir.deleteRecursively()
    }
}
