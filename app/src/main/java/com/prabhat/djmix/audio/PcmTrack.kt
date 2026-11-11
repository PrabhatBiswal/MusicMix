package com.prabhat.djmix.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs

/** A decoded track: 16-bit interleaved stereo PCM at its own [sampleRate]. */
/** [key] names the cached file and the track's saved metadata. */
class PcmTrack(val sampleRate: Int, val pcm: ShortBuffer, val key: String = "") {
    val frames: Int = pcm.limit() / 2
    val durationMs: Long get() = frames * 1000L / sampleRate

    /** One peak (0..1) per [PEAK_FRAMES] frames, for drawing. Computed on first use; [load] touches it off the main thread. */
    val peaks: FloatArray by lazy {
        val chunk = ShortArray(PEAK_FRAMES * 2)
        val src = pcm.duplicate().apply { position(0) }
        FloatArray((frames + PEAK_FRAMES - 1) / PEAK_FRAMES) { i ->
            val n = minOf(chunk.size, src.remaining())
            src.get(chunk, 0, n)
            var p = 0
            for (k in 0 until n) { val v = chunk[k].toInt(); if (v > p) p = v else if (-v > p) p = -v }
            p / 32768f
        }
    }

    companion object {
        const val PEAK_FRAMES = 1024
        private const val HEADER = 8L // int sampleRate, int reserved (big-endian via RandomAccessFile)

        /** Decodes [uri] once to cacheDir/<hash>.pcm, then memory-maps it. Blocking: call off the main thread. */
        fun load(context: Context, uri: Uri, key: String = uri.toString().hashCode().toUInt().toString()): PcmTrack {
            // ponytail: cache never evicted; add LRU when cacheDir grows past a few GB
            val file = File(context.cacheDir, "$key.pcm")
            if (file.length() <= HEADER) decode(context, uri, file)
            RandomAccessFile(file, "r").use { raf ->
                val rate = raf.readInt()
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, HEADER, file.length() - HEADER)
                return PcmTrack(rate, map.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(), key).also { it.peaks }
            }
        }

        /**
         * Decodes [uri] to [out]: the header, then 16-bit stereo. A MediaCodec round trip per 20 ms packet caps one
         * decoder at ~4x real time (Redmi and A04s alike, whatever the thread priority), so a track over two minutes is
         * cut into [SLICES] time slices decoded side by side and stitched; if that fails, one decoder does it all.
         */
        private fun decode(context: Context, uri: Uri, out: File) {
            val t0 = SystemClock.elapsedRealtime()
            val tmp = File(out.path + "." + System.nanoTime() + ".tmp") // two decoders of one track never share a temp file
            val durUs = MediaExtractor().run {
                setDataSource(context, uri, null)
                val f = getTrackFormat(audioTrack(this)); release()
                if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            }
            val sliced = durUs >= 120_000_000L && runCatching { decodeSliced(context, uri, tmp, durUs) }
                .onFailure { Log.w(TAG, "sliced decode failed, decoding in one piece", it) }.isSuccess
            if (!sliced) RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0); raf.writeInt(0); raf.writeInt(0)
                val d = decodeRange(context, uri, 0, Long.MAX_VALUE, raf)
                raf.seek(0); raf.writeInt(d.rate)
            }
            Log.i(TAG, "decode ${out.name}: ${if (sliced) SLICES else 1} slices, ${SystemClock.elapsedRealtime() - t0} ms for ${durUs / 1_000_000} s")
            if (!tmp.renameTo(out)) throw IOException("could not move ${tmp.name} into place")
        }

        private fun audioTrack(e: MediaExtractor) = (0 until e.trackCount).first { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }

        private class Decoded(val rate: Int, val firstUs: Long)

        /**
         * Decodes from the sync point at or before [fromUs] until the first packet past [untilUs], appending 16-bit stereo
         * at [raf]'s position. Returns the output rate and the timestamp of the first sample out.
         */
        private fun decodeRange(context: Context, uri: Uri, fromUs: Long, untilUs: Long, raf: RandomAccessFile): Decoded {
            val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            val trackIndex = audioTrack(extractor)
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var firstUs = -1L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            // Feed every free input buffer, then drain every ready output; wait only when both are empty.
            // One 10 ms wait per 20 ms Opus frame made this run at barely 5x real time.
            while (!outputDone) {
                var fed = false
                if (!inputDone) {
                    var i = codec.dequeueInputBuffer(0)
                    while (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || extractor.sampleTime > untilUs) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                            break
                        }
                        codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                        fed = true
                        i = codec.dequeueInputBuffer(0)
                    }
                }
                var o = codec.dequeueOutputBuffer(info, if (fed) 0 else 2_000)
                while (!outputDone && (o >= 0 || o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)) {
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else {
                        if (firstUs < 0 && info.size > 0) firstUs = info.presentationTimeUs
                        val b = codec.getOutputBuffer(o)!!
                        b.position(info.offset); b.limit(info.offset + info.size)
                        raf.channel.write(toStereo(b, channels))
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    if (!outputDone) o = codec.dequeueOutputBuffer(info, 0)
                }
            }
            codec.stop(); codec.release(); extractor.release()
            return Decoded(sampleRate, firstUs)
        }

        /** [SLICES] decoders on threads at the caller's priority, each slice starting [PREROLL_US] early, then [stitch]. */
        private fun decodeSliced(context: Context, uri: Uri, tmp: File, durUs: Long) {
            val cuts = LongArray(SLICES + 1) { durUs * it / SLICES }
            val parts = List(SLICES) { File(tmp.path + ".s$it") }
            val results = arrayOfNulls<Decoded>(SLICES)
            val error = AtomicReference<Throwable>()
            val priority = Process.getThreadPriority(Process.myTid())
            try {
                List(SLICES) { i ->
                    thread(name = "decode-$i") {
                        try {
                            Process.setThreadPriority(priority)
                            RandomAccessFile(parts[i], "rw").use { raf ->
                                raf.setLength(0)
                                val until = if (i == SLICES - 1) Long.MAX_VALUE else cuts[i + 1] + TAIL_US
                                results[i] = decodeRange(context, uri, maxOf(0L, cuts[i] - PREROLL_US), until, raf)
                            }
                        } catch (e: Throwable) { error.compareAndSet(null, e) }
                    }
                }.forEach { it.join() }
                error.get()?.let { throw it }
                val rate = results[0]!!.rate
                if (results.any { it!!.rate != rate || it.firstUs < 0 }) throw IOException("slices disagree on the rate or came out empty")
                stitch(tmp, parts, LongArray(SLICES + 1) { cuts[it] * rate / 1_000_000 }, results.map { it!!.firstUs * rate / 1_000_000 }, rate)
            } finally { parts.forEach { it.delete() } }
        }

        /**
         * Joins the slices into [tmp] at the frames [cuts]. Slice 0 starts at frame 0. A later slice began a second early
         * and its decoder dropped some samples while starting (Opus pre-skip, AAC priming), so its timestamp [firstFrame]
         * is only a guess: its frame 0 is placed where its lead-in matches what is already written, within ±[SEARCH]
         * frames. A lead-in that matches nowhere throws, so a bad seam never reaches the cache.
         */
        internal fun stitch(tmp: File, parts: List<File>, cuts: LongArray, firstFrame: List<Long>, rate: Int) {
            RandomAccessFile(tmp, "rw").use { out ->
                out.setLength(0); out.writeInt(rate); out.writeInt(0)
                var end = 0L // frames written
                for (i in parts.indices) RandomAccessFile(parts[i], "r").use { src ->
                    val frames = src.length() / 4
                    val at = if (i == 0) 0L else align(out, end, src, firstFrame[i]) // absolute frame of src frame 0
                    val from = end - at
                    val to = if (i == parts.size - 1) frames else minOf(frames, cuts[i + 1] - at)
                    if (from < 0 || to <= from) throw IOException("slice $i does not cover its part")
                    src.seek(from * 4); out.seek(HEADER + end * 4)
                    val buf = ByteArray(256 * 1024)
                    var left = (to - from) * 4
                    while (left > 0) { val n = src.read(buf, 0, minOf(left, buf.size.toLong()).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
                    end += to - from
                }
            }
        }

        /** The absolute frame of [src]'s frame 0: its lead-in, [WINDOW] frames just before [end], matched against [out]. */
        private fun align(out: RandomAccessFile, end: Long, src: RandomAccessFile, guess: Long): Long {
            val local = end - guess - WINDOW - SEARCH // a window inside the lead-in, wholly before [end] for any shift
            if (local < MIN_LEAD) throw IOException("lead-in too short to align ($local frames)")
            val a = mono(src, frame = local, frames = WINDOW)
            val b = mono(out, HEADER, guess + local - SEARCH, WINDOW + 2 * SEARCH)
            var best = Long.MAX_VALUE; var bestD = 0
            for (d in -SEARCH..SEARCH) {
                var sad = 0L
                for (k in 0 until WINDOW) sad += abs(a[k] - b[k + d + SEARCH])
                if (sad < best || (sad == best && abs(d) < abs(bestD))) { best = sad; bestD = d }
            }
            val level = a.sumOf { abs(it).toLong() }
            if (best > level / 20 + WINDOW) throw IOException("seam does not match (difference $best against level $level)")
            return guess + bestD
        }

        /** [frames] frames from [frame] on, as L+R sums. */
        private fun mono(f: RandomAccessFile, offset: Long = 0, frame: Long, frames: Int): IntArray {
            val bytes = ByteArray(frames * 4)
            f.seek(offset + frame * 4); f.readFully(bytes)
            val s = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return IntArray(frames) { s.get(it * 2) + s.get(it * 2 + 1) }
        }

        private const val TAG = "PcmTrack"
        private const val SLICES = 3
        private const val PREROLL_US = 1_000_000L
        private const val TAIL_US = 200_000L
        private const val WINDOW = 4096
        private const val SEARCH = 4096
        private const val MIN_LEAD = 12_000L

        /** Mono is doubled, more than two channels keep the first two, stereo passes through. */
        private fun toStereo(b: ByteBuffer, channels: Int): ByteBuffer {
            if (channels == 2) return b
            val src = b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer() // MediaCodec PCM is native (little) endian
            val frames = src.remaining() / channels
            val dst = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until frames) {
                val l = src.get(f * channels)
                val r = if (channels > 1) src.get(f * channels + 1) else l
                dst.putShort(l); dst.putShort(r)
            }
            dst.flip()
            return dst
        }
    }
}
