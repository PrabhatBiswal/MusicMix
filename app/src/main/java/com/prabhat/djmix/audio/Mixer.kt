package com.prabhat.djmix.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Sums both decks on its own thread into one AudioTrack (the master). In DUAL cue mode a second AudioTrack carries
 * the headphone bus to another device, so the room hears the master on a Bluetooth speaker while the DJ pre-listens
 * on wired or USB earphones.
 */
class Mixer {
    /** OFF: master only. SPLIT: left ear master mono, right ear cue mono (splitter cable). DUAL: two outputs. */
    enum class CueMode { OFF, SPLIT, DUAL }

    val a = Deck()
    val b = Deck()
    val stripA = Strip(RATE.toDouble())
    val stripB = Strip(RATE.toDouble())
    val fxA = Fx(RATE)
    val fxB = Fx(RATE)
    /** Sampler slots: plain decks, one-shot, mixed into the master. */
    val samples = Array(SAMPLE_SLOTS) { Deck() }
    /** Beat-timed automation for an unattended mashup; ticks before every block. */
    val mashup = Mashup(this)
    /** Set to record the master output; the mixer pushes every block into it. */
    @Volatile var recorder: Recorder? = null
    /** Headphone cue (PFL): the deck's pre-fader signal goes to the cue bus. */
    @Volatile var cueA = false
    @Volatile var cueB = false
    @Volatile var cueMode = CueMode.OFF
    /** Headphone blend in DUAL mode: 0 = cue bus only, 1 = master only. */
    @Volatile var cueMix = 0.5f
    /** Headphone level: gain on the whole phones feed in DUAL, on the cue ear in SPLIT. */
    @Volatile var phonesGain = 1f
    /** DUAL mode outputs; the UI picks them from AudioManager. null = the system default. */
    @Volatile var masterDevice: AudioDeviceInfo? = null
    @Volatile var cueDevice: AudioDeviceInfo? = null
    /** DUAL routing check: null = not known yet, false = the phone put both streams on one device. */
    @Volatile var dualOk: Boolean? = null
    /** 0 = deck A only, 1 = deck B only. */
    @Volatile var crossfader = 0.5f
    /** false = constant-power blend, true = sharp "cut" curve for scratching. */
    @Volatile var sharp = false
    @Volatile var master = 1f
    /** Peak of the last output block, 0..1 (hard-clipped). Written by the audio thread. */
    @Volatile var masterPeak = 0f

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread(::loop, "mixer").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join()
        thread = null
    }

    private fun newTrack(): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            // ponytail: 120 ms of buffer absorbs GC and scheduling stalls on budget phones at the cost of jog latency;
            // the FAST (low-latency) path underran >20 times a second on a Galaxy A04s from Kotlin.
            .setBufferSizeInBytes(maxOf(minBytes, RATE * BUFFER_MS / 1000 * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply { if (Build.VERSION.SDK_INT >= 26) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE) }
            .build()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val track = newTrack()
        var phones: AudioTrack? = null
        val bufA = FloatArray(BLOCK * 2)
        val bufB = FloatArray(BLOCK * 2)
        val bufS = FloatArray(BLOCK * 2)
        val cue = FloatArray(BLOCK * 2)
        val cueMono = FloatArray(BLOCK)
        val ph = FloatArray(BLOCK * 2)
        val out = FloatArray(BLOCK * 2)
        Log.i(TAG, "AudioTrack state=${track.state} rate=${track.sampleRate} bufFrames=${track.bufferSizeInFrames}")
        track.play()
        var blocks = 0L
        var underruns = 0
        var routeCheck = 0
        while (running) {
            val mode = cueMode
            if (mode == CueMode.DUAL) {
                if (phones == null) {
                    track.setPreferredDevice(masterDevice)
                    phones = newTrack().apply { setPreferredDevice(cueDevice); play() }
                    dualOk = null; routeCheck = 0
                    Log.i(TAG, "DUAL: master -> ${masterDevice?.productName}, cue -> ${cueDevice?.productName}")
                }
            } else if (phones != null) {
                phones.stop(); phones.release(); phones = null
                track.setPreferredDevice(null); dualOk = null
            }
            mashup.tick(BLOCK, RATE)
            bufA.fill(0f); bufB.fill(0f)
            a.render(bufA, BLOCK, RATE)
            b.render(bufB, BLOCK, RATE)
            stripA.process(bufA, BLOCK)
            stripB.process(bufB, BLOCK)
            cue.fill(0f) // pre-fader, post-EQ
            if (cueA) for (i in cue.indices) cue[i] += bufA[i]
            if (cueB) for (i in cue.indices) cue[i] += bufB[i]
            val x = crossfader
            val ga = gainA(x, sharp) * a.volume
            val gb = gainB(x, sharp) * b.volume
            for (i in out.indices) { bufA[i] *= ga; bufB[i] *= gb } // faders, then post-fader FX
            fxA.process(bufA, BLOCK)
            fxB.process(bufB, BLOCK)
            for (i in out.indices) out[i] = bufA[i] + bufB[i]
            for (s in samples) if (s.playing) {
                bufS.fill(0f)
                s.render(bufS, BLOCK, RATE)
                for (i in out.indices) out[i] += bufS[i] * SAMPLE_GAIN
            }
            val m = master
            var peak = 0f
            for (i in out.indices) {
                val v = softClip(out[i] * m)
                out[i] = v
                if (v > peak) peak = v else if (-v > peak) peak = -v
            }
            masterPeak = peak
            recorder?.push(out, BLOCK)
            when (mode) {
                CueMode.SPLIT -> { cueMono.fill(0f); addMono(cueMono, cue, BLOCK); splitCue(out, cueMono, BLOCK, phonesGain) }
                CueMode.DUAL -> phonesMix(out, cue, ph, BLOCK, cueMix, phonesGain)
                CueMode.OFF -> {}
            }
            val n = track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
            if (n != out.size) Log.e(TAG, "write returned $n (state=${track.state} playState=${track.playState})")
            phones?.let { p ->
                // ponytail: non-blocking so the room never waits for the earphones; the two devices' clocks drift, so
                // the cue side drops or repeats a block every few minutes instead of the master glitching.
                p.write(ph, 0, ph.size, AudioTrack.WRITE_NON_BLOCKING)
                if (dualOk == null && ++routeCheck >= 25) {
                    val md = track.routedDevice; val cd = p.routedDevice
                    if (md != null && cd != null) { dualOk = md.id != cd.id; Log.i(TAG, "DUAL routed: master=${md.productName} cue=${cd.productName} ok=$dualOk") }
                    else if (routeCheck >= 300) dualOk = false
                }
            }
            if (++blocks % 500 == 0L) {
                val u = track.underrunCount
                if (u != underruns) { Log.w(TAG, "AudioTrack underruns: $u (+${u - underruns})"); underruns = u }
            }
        }
        phones?.let { it.stop(); it.release() }
        track.stop()
        track.release()
    }

    companion object {
        private const val TAG = "Mixer"
        const val RATE = 48000
        const val BLOCK = 512
        const val SAMPLE_SLOTS = 8
        const val BUFFER_MS = 120
        private const val KNEE = 0.8f
        const val SAMPLE_GAIN = 0.8f

        /**
         * Master limiter: exact below ±0.8, then a smooth tanh knee that never reaches ±1. Two full-scale decks
         * at the crossfader centre sum to 1.41, which a hard clip turned into distortion.
         */
        fun softClip(x: Float): Float {
            val a = abs(x)
            if (a <= KNEE) return x
            val y = KNEE + (1 - KNEE) * tanh((a - KNEE) / (1 - KNEE))
            return if (x < 0) -y else y
        }

        /** Adds the mono mix of interleaved [src] into [dst]. */
        fun addMono(dst: FloatArray, src: FloatArray, frames: Int) {
            for (f in 0 until frames) dst[f] += (src[f * 2] + src[f * 2 + 1]) * 0.5f
        }

        /** In place: left = master mono, right = cue mono (both clipped), the cue scaled by [level]. */
        fun splitCue(out: FloatArray, cue: FloatArray, frames: Int, level: Float = 1f) {
            for (f in 0 until frames) {
                out[f * 2] = (out[f * 2] + out[f * 2 + 1]) * 0.5f
                out[f * 2 + 1] = cue[f].coerceIn(-1f, 1f) * level
            }
        }

        /** Headphone feed: [cue] blended with [master] by [mix] (0 = cue only, 1 = master only), limited, then [level]. */
        fun phonesMix(master: FloatArray, cue: FloatArray, out: FloatArray, frames: Int, mix: Float, level: Float = 1f) {
            val c = 1 - mix
            for (i in 0 until frames * 2) out[i] = softClip(cue[i] * c + master[i] * mix) * level
        }

        /** Crossfader gain of deck A for position [x] in 0..1. Sharp: full level except in the last 5 % of travel. */
        fun gainA(x: Float, sharp: Boolean): Float = if (sharp) minOf(1f, (1 - x) / 0.05f) else cos(x * PI / 2).toFloat()
        fun gainB(x: Float, sharp: Boolean): Float = if (sharp) minOf(1f, x / 0.05f) else sin(x * PI / 2).toFloat()
        /** Both gains, for tests. */
        fun xfade(x: Float, sharp: Boolean): Pair<Float, Float> = gainA(x, sharp) to gainB(x, sharp)
    }
}
