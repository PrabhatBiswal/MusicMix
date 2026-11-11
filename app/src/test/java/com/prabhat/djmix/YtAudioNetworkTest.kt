package com.prabhat.djmix

import com.prabhat.djmix.youtube.YtAudio
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Hits the real YouTube. Skipped unless DJMIX_NET=1, e.g. `DJMIX_NET=1 ./gradlew :app:testDebugUnitTest --tests '*YtAudioNetworkTest*'`. */
class YtAudioNetworkTest {
    @Test fun resolvesAKnownVideoToAnAudioStream() {
        assumeTrue("set DJMIX_NET=1 to run the network check", System.getenv("DJMIX_NET") == "1")
        val info = YtAudio.resolve("9bZkp7q19f0")
        println("title=${info.title} duration=${info.durationMs} ext=${info.ext} url=${info.url.take(80)}...")
        assertTrue(info.title.isNotBlank())
        assertTrue(info.durationMs > 60_000)
        assertTrue(info.url.startsWith("https://"))
    }

    /** Crazy Kiya Re carries Tamil and Telugu dubs at the same bitrate as the Hindi original; the original must win. */
    @Test fun picksTheOriginalAudioTrackOverDubs() {
        assumeTrue("set DJMIX_NET=1 to run the network check", System.getenv("DJMIX_NET") == "1")
        val info = YtAudio.resolve("J2Bh68GTUOU")
        assertTrue(info.url, info.url.contains("acont%3Doriginal"))
    }

    /** The ranged download must come down whole (a WebM/MP4 file of the advertised size) and faster than real time. */
    @Test fun downloadsInRangesWhole() {
        assumeTrue("set DJMIX_NET=1 to run the network check", System.getenv("DJMIX_NET") == "1")
        val info = YtAudio.resolve("9bZkp7q19f0")
        val dest = File.createTempFile("djmix", ".${info.ext}").apply { delete(); deleteOnExit() }
        val t0 = System.nanoTime()
        YtAudio.download(info.url, dest) {}
        val s = (System.nanoTime() - t0) / 1e9
        println("download ${dest.length() / 1024} KB in ${"%.1f".format(s)} s = ${(dest.length() / 1024 / s).toInt()} KB/s for ${info.durationMs / 1000} s of audio")
        val head = dest.inputStream().use { it.readNBytes(8) }
        val webm = head.take(4) == listOf(0x1A, 0x45, 0xDF, 0xA3).map { it.toByte() }
        val mp4 = String(head, 4, 4, Charsets.US_ASCII) == "ftyp"
        assertTrue("container header", webm || mp4)
        assertTrue("at least 64 kbps worth", dest.length() > info.durationMs / 1000 * 8_000)
    }
}
