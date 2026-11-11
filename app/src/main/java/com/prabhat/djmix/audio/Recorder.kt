package com.prabhat.djmix.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue

/** Records the master mix as 16-bit stereo WAV on its own thread. [push] is called from the audio thread and never blocks. */
class Recorder(private val rate: Int, val file: File) {
    private val queue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var running = true
    val startedAt = System.currentTimeMillis()
    private val thread = Thread(::writer, "recorder").apply { start() }

    fun push(out: FloatArray, frames: Int) {
        if (!running) return
        queue.offer(ShortArray(frames * 2) { (out[it] * 32767f).toInt().toShort() })
    }

    /** Stops, flushes, and writes the final WAV header. Blocks until everything queued is on disk. */
    fun stop(): File {
        running = false
        queue.offer(ShortArray(0))
        thread.join()
        return file
    }

    private fun writer() {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(44))
            var bytes = 0L
            var bb = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            while (true) {
                val s = queue.take()
                if (s.isEmpty()) break
                if (bb.capacity() < s.size * 2) bb = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                bb.clear()
                for (v in s) bb.putShort(v)
                bb.flip()
                while (bb.hasRemaining()) raf.channel.write(bb)
                bytes += s.size * 2L
            }
            raf.seek(0)
            raf.write(wavHeader(rate, bytes))
        }
    }

    companion object {
        fun wavHeader(rate: Int, dataBytes: Long): ByteArray =
            ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
                .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
                .putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
                .put("data".toByteArray()).putInt(dataBytes.toInt())
                .array()

        /** 16-bit stereo WAV -> AAC-LC .m4a at 192 kbps. Blocking; run on a background thread. */
        fun encodeToM4a(wav: File, m4a: File, rate: Int) {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val muxer = MediaMuxer(m4a.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            val info = MediaCodec.BufferInfo()
            RandomAccessFile(wav, "r").use { raf ->
                raf.seek(44)
                var inputDone = false
                var fed = 0L
                val tmp = ByteArray(64 * 1024)
                // Feed every free input buffer, drain every ready output, wait only when both are empty: one 10 ms
                // wait per 21 ms AAC frame made a ten-minute mix take twenty minutes to encode.
                var done = false
                while (!done) {
                    var worked = false
                    if (!inputDone) {
                        var i = codec.dequeueInputBuffer(0)
                        while (i >= 0 && !inputDone) {
                            val b = codec.getInputBuffer(i)!!
                            val n = raf.read(tmp, 0, minOf(tmp.size, b.capacity()))
                            if (n <= 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                b.clear(); b.put(tmp, 0, n)
                                codec.queueInputBuffer(i, 0, n, fed * 1_000_000L / (rate * 4L), 0)
                                fed += n
                                worked = true
                                i = codec.dequeueInputBuffer(0)
                            }
                        }
                    }
                    var o = codec.dequeueOutputBuffer(info, if (worked) 0 else 2_000)
                    while (!done && (o >= 0 || o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)) {
                        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(codec.outputFormat); muxer.start()
                        } else {
                            val b = codec.getOutputBuffer(o)!!
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && track >= 0) muxer.writeSampleData(track, b, info)
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                        }
                        if (!done) o = codec.dequeueOutputBuffer(info, 0)
                    }
                }
            }
            codec.stop(); codec.release()
            muxer.stop(); muxer.release()
        }

        /** Copies [m4a] into the phone's Music/DJMix folder (Android 10+). Returns where it went, or null. */
        fun publish(ctx: Context, m4a: File): String? {
            if (Build.VERSION.SDK_INT < 29) return null
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, m4a.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DJMix")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)!!.use { out -> m4a.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Music/DJMix/${m4a.name}"
        }
    }
}
