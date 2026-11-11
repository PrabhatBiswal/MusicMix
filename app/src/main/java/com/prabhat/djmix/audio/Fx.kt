package com.prabhat.djmix.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * One beat-synced effect unit (echo / roll / flanger) on a post-fader channel, so an "echo out" keeps ringing
 * after the fader is down. [beatSeconds] comes from the deck's effective BPM; the UI keeps it current.
 */
class Fx(private val rate: Int) {
    enum class Type { ECHO, ROLL, FLANGER }

    @Volatile var type = Type.ECHO
    /** 0.25, 0.5, 1, 2 or 4 beats. */
    @Volatile var beats = 1.0
    /** Level / depth knob, 0..1: wet mix. */
    @Volatile var depth = 0.5f
    @Volatile var beatSeconds = 0.5
    @Volatile var on = false

    private val ringFrames = rate * 4 // 4 s of stereo history / delay line
    private val ring = FloatArray(ringFrames * 2)
    private var w = 0
    private var wasOn = false
    private var rollStart = 0
    private var rollPos = 0
    private var phase = 0.0

    fun process(buf: FloatArray, frames: Int) {
        val isOn = on
        val t = type
        val len = (beats * beatSeconds * rate).toInt().coerceIn(1, ringFrames - 1)
        if (isOn && !wasOn) {
            if (t == Type.ROLL) { rollStart = ((w - len) % ringFrames + ringFrames) % ringFrames; rollPos = 0 }
            else ring.fill(0f) // the first repeat comes one beat after ON, not from what played before
        }
        wasOn = isOn
        val wet = depth
        when {
            !isOn -> record(buf, frames)                     // bypass, but keep history for a future roll
            t == Type.ECHO -> echo(buf, frames, len, wet)
            t == Type.ROLL -> roll(buf, frames, len, wet)    // history frozen while rolling
            else -> flanger(buf, frames, len, wet)
        }
    }

    private fun record(buf: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            ring[w * 2] = buf[f * 2]; ring[w * 2 + 1] = buf[f * 2 + 1]
            w = (w + 1) % ringFrames
        }
    }

    private fun echo(buf: FloatArray, frames: Int, len: Int, wet: Float) {
        for (f in 0 until frames) {
            val r = ((w - len) % ringFrames + ringFrames) % ringFrames
            for (ch in 0..1) {
                val x = buf[f * 2 + ch]
                val d = ring[r * 2 + ch]
                ring[w * 2 + ch] = x + d * FEEDBACK
                buf[f * 2 + ch] = x + d * wet
            }
            w = (w + 1) % ringFrames
        }
    }

    private fun roll(buf: FloatArray, frames: Int, len: Int, wet: Float) {
        for (f in 0 until frames) {
            val r = (rollStart + rollPos) % ringFrames
            for (ch in 0..1) buf[f * 2 + ch] = buf[f * 2 + ch] * (1 - wet) + ring[r * 2 + ch] * wet
            rollPos = (rollPos + 1) % len
        }
    }

    private fun flanger(buf: FloatArray, frames: Int, len: Int, wet: Float) {
        val minD = 0.0005 * rate
        val modD = 0.004 * rate
        val step = 2 * PI / len // one LFO cycle per [beats]
        for (f in 0 until frames) {
            val d = minD + modD * (1 + sin(phase)) / 2
            phase += step
            if (phase > 2 * PI) phase -= 2 * PI
            val di = d.toInt()
            val fr = (d - di).toFloat()
            val r0 = ((w - di) % ringFrames + ringFrames) % ringFrames
            val r1 = (r0 - 1 + ringFrames) % ringFrames
            for (ch in 0..1) {
                val x = buf[f * 2 + ch]
                val dl = ring[r0 * 2 + ch] + (ring[r1 * 2 + ch] - ring[r0 * 2 + ch]) * fr
                ring[w * 2 + ch] = x + dl * FEEDBACK
                buf[f * 2 + ch] = x + dl * wet
            }
            w = (w + 1) % ringFrames
        }
    }

    companion object {
        const val FEEDBACK = 0.55f
        val BEATS = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0)
        fun beatLabel(b: Double) = when (b) { 0.25 -> "¼"; 0.5 -> "½"; else -> b.toInt().toString() }
    }
}
