package com.prabhat.djmix.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * YouTube audio on the phone: NewPipeExtractor resolves a progressive audio-only stream URL, OkHttp downloads it.
 * Pure JVM (no Android classes) so it can be checked from a unit test. Everything here blocks: call off the main thread.
 */
object YtAudio {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private val client by lazy { OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build() }

    /** NewPipe's own DownloaderImpl, trimmed: same User-Agent on every call, HTTP 429 reported as the bot wall. */
    private val downloader = object : Downloader() {
        override fun execute(request: Request): Response {
            val b = okhttp3.Request.Builder().url(request.url())
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .header("User-Agent", UA)
            request.headers().forEach { (k, vs) -> b.removeHeader(k); vs.forEach { b.addHeader(k, it) } }
            client.newCall(b.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }
    private val ready by lazy { NewPipe.init(downloader); true }

    class Info(val videoId: String, val title: String, val durationMs: Long, val url: String, val ext: String, val kbps: Int)

    /**
     * Best progressive audio-only stream (AAC in m4a or Opus in webm, highest bitrate) of the original audio track.
     * Songs can carry YouTube's dubbed tracks at the same bitrate (Crazy Kiya Re: Tamil, Telugu, Hindi original),
     * so the original wins first; a single-track video reports no type.
     */
    fun resolve(videoId: String): Info {
        ready
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val s = info.audioStreams
            .filter { it.isUrl && (it.format == MediaFormat.M4A || it.format == MediaFormat.WEBMA_OPUS) }
            .minWithOrNull(compareBy({ trackRank(it.audioTrackType) }, { -it.averageBitrate }))
            ?: throw IOException("no progressive audio stream for $videoId")
        return Info(videoId, info.name, info.duration * 1000, s.content, if (s.format == MediaFormat.M4A) "m4a" else "webm", s.averageBitrate)
    }

    /** Original first, then unknown (single-track videos), then dubbed / descriptive / secondary. */
    private fun trackRank(t: AudioTrackType?) = when (t) { AudioTrackType.ORIGINAL -> 0; null -> 1; else -> 2 }

    /**
     * Downloads [url] to [dest], reporting 0..100. The URL expires within hours and is bound to this IP, so do it at once.
     * YouTube paces one long request at about 1.7x real time (34 KB/s for a 160 kbps stream, measured 2026-11-02) but
     * starts every request fast, so the file comes down as [CHUNK] byte ranges over [WORKERS] connections. A server that
     * ignores ranges gets one plain GET.
     */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val tmp = File(dest.path + ".part")
        val total = rangeTotal(url)
        if (total == null) single(url, tmp, onProgress) else ranged(url, tmp, total, onProgress)
        if (!tmp.renameTo(dest)) throw IOException("could not move ${tmp.name} into place")
    }

    private const val WORKERS = 4
    private const val CHUNK = 512 * 1024L

    private fun get(url: String, range: String? = null) =
        client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", UA).apply { range?.let { header("Range", "bytes=$it") } }.build()).execute()

    /** The file size from a one-byte range request, or null when the server sends the whole body instead. */
    private fun rangeTotal(url: String): Long? = get(url, "0-0").use { r ->
        if (r.code != 206) { if (!r.isSuccessful) throw IOException("HTTP ${r.code} fetching the stream"); null }
        else r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
    }

    private fun ranged(url: String, tmp: File, total: Long, onProgress: (Int) -> Unit) {
        RandomAccessFile(tmp, "rw").use { it.setLength(total) }
        val next = AtomicLong(); val done = AtomicLong(); val last = AtomicInteger(-1)
        val error = AtomicReference<Throwable>()
        val workers = List(WORKERS) {
            thread(name = "download-$it") {
                try {
                    RandomAccessFile(tmp, "rw").use { raf ->
                        val buf = ByteArray(64 * 1024)
                        while (error.get() == null) {
                            val from = next.getAndAdd(CHUNK)
                            if (from >= total) break
                            val to = minOf(total, from + CHUNK) - 1
                            get(url, "$from-$to").use { r ->
                                if (r.code != 206) throw IOException("HTTP ${r.code} fetching bytes $from-$to")
                                raf.seek(from)
                                var at = from
                                r.body!!.byteStream().use { input ->
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        raf.write(buf, 0, n); at += n
                                        val p = (done.addAndGet(n.toLong()) * 100 / total).toInt()
                                        if (last.getAndSet(p) != p) onProgress(p)
                                    }
                                }
                                if (at != to + 1) throw IOException("bytes $from-$to ended at $at")
                            }
                        }
                    }
                } catch (e: Throwable) { error.compareAndSet(null, e) }
            }
        }
        workers.forEach { it.join() }
        error.get()?.let { throw it as? IOException ?: IOException(it) }
    }

    private fun single(url: String, tmp: File, onProgress: (Int) -> Unit) {
        get(url).use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} fetching the stream")
            val body = r.body ?: throw IOException("empty stream body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                    }
                }
            }
        }
    }
}
