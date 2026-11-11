package com.prabhat.djmix.audio

import sonic.Sonic
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/** One playback deck. Vars are set from the UI thread and read by the mixer thread. */
class Deck {
    @Volatile var track: PcmTrack? = null
        private set
    /** Read head (what is being heard), in frames of the track's own sample rate. UI moves it through [jump]. */
    @Volatile var pos = 0.0
        private set
    @Volatile var playing = false
    /** 1.0 = original speed. */
    @Volatile var tempo = 1.0
    /** Channel fader, 0..1. */
    @Volatile var volume = 1f
    @Volatile var cuePos = 0.0
        private set
    @Volatile private var seekReq = -1.0

    // Stems: a vocal and an instrumental track aligned with [track]. With both gains at 1 the original plays.
    @Volatile var vocTrack: PcmTrack? = null
        private set
    @Volatile var instTrack: PcmTrack? = null
        private set
    @Volatile var vocGain = 1f
    @Volatile var instGain = 1f
    val hasStems: Boolean get() = vocTrack != null

    // Beat grid: constant tempo, one anchor beat. ponytail: no drifting grids; add a beat list if live-drummed tracks matter.
    @Volatile var bpm = 0.0
    @Volatile var firstBeat = 0.0
    @Volatile var quantize = true
    /** Frame of each hot cue, -1 = unset. UI thread only. */
    val hotCues = DoubleArray(HOT_CUES) { -1.0 }
    @Volatile var loopIn = -1.0
        private set
    @Volatile var loopOut = -1.0
        private set
    @Volatile var loopOn = false
        private set
    /** Called on the UI thread after a hot cue or the grid changes, so the caller can persist them. */
    var onChanged: (() -> Unit)? = null

    // Jog wheel. A scratch/nudge value is trusted for FRESH_FRAMES of output; a hand that stopped moving sends nothing.
    @Volatile var scratching = false
        private set
    @Volatile private var scratchRate = 0.0
    @Volatile private var scratchFresh = 0
    @Volatile private var nudgeAmt = 0.0
    @Volatile private var nudgeFresh = 0
    @Volatile private var smoothReq = false

    /** Master tempo: tempo changes keep the pitch (Sonic time-stretch). */
    @Volatile var keylock = false
    /** Slip: scratching and loops do not move the true position; releasing them jumps back to it. */
    @Volatile var slip = false
    @Volatile var ghost = 0.0
        private set
    @Volatile var slipping = false
        private set

    // Audio-thread state.
    private var curRate = 0.0
    private var smoothLeft = 0
    private var wasSlipping = false
    private var sonic: Sonic? = null
    private var feedPos = 0.0
    private val feedShorts = ShortArray(FEED * 2)
    private val feedShorts2 = ShortArray(FEED * 2)
    private val feedFloats = FloatArray(FEED * 2)

    fun load(t: PcmTrack) {
        playing = false; track = t; pos = 0.0; cuePos = 0.0; seekReq = 0.0
        bpm = 0.0; firstBeat = 0.0; hotCues.fill(-1.0); loopIn = -1.0; loopOut = -1.0; loopOn = false
        scratching = false; ghost = 0.0
        vocTrack = null; instTrack = null; vocGain = 1f; instGain = 1f
    }

    fun loadStems(vocals: PcmTrack, inst: PcmTrack) { vocTrack = vocals; instTrack = inst }

    fun togglePlay() { if (track != null) playing = !playing }

    /** Pioneer-style cue: while playing, jump back to the cue and pause; while paused, set the cue here. */
    fun cue() {
        if (playing) { playing = false; jump(cuePos) } else cuePos = snap(pos)
    }

    /** Moves the read head. Applied by the mixer before its next block, so it never races a running render. */
    fun jump(frame: Double) { seekReq = frame }

    fun seekTo(fraction: Double) { val t = track ?: return; jump(fraction.coerceIn(0.0, 1.0) * (t.frames - 1)) }

    /** 0..1 */
    val position: Double get() = track?.let { if (it.frames > 1) pos / (it.frames - 1) else 0.0 } ?: 0.0

    // ---- jog wheel --------------------------------------------------------------------------------------

    fun scratchStart() { scratchRate = 0.0; scratchFresh = 0; scratching = true; smoothReq = true }
    /** [rate] relative to normal forward speed: 1 = forward at platter speed, negative = backwards. */
    fun scratch(rate: Double) { scratchRate = rate; scratchFresh = FRESH_FRAMES }
    fun scratchEnd() { scratching = false; smoothReq = true }
    /** Pitch bend: tempo is multiplied by (1 + amount) while the rim is being turned. 0 clears it. */
    fun nudge(amount: Double) { nudgeAmt = amount; nudgeFresh = if (amount == 0.0) 0 else FRESH_FRAMES }

    // ---- beat grid -------------------------------------------------------------------------------------

    val framesPerBeat: Double get() = track?.let { if (bpm > 0) it.sampleRate * 60.0 / bpm else 0.0 } ?: 0.0
    fun beatAt(frame: Double) = framesPerBeat.let { if (it > 0) (frame - firstBeat) / it else 0.0 }
    fun frameOfBeat(beat: Double) = firstBeat + beat * framesPerBeat
    private fun snap(frame: Double) = if (quantize && framesPerBeat > 0) frameOfBeat(round(beatAt(frame))) else frame

    /** Unset: store (quantized) here. Set: jump there and play. */
    fun hotCue(i: Int) {
        val c = hotCues[i]
        if (c < 0) { hotCues[i] = snap(pos); onChanged?.invoke() } else { jump(c); playing = true }
    }

    fun clearHotCue(i: Int) { hotCues[i] = -1.0; onChanged?.invoke() }

    fun beatJump(beats: Int) { if (framesPerBeat > 0) jump(pos + beats * framesPerBeat) }

    /** Auto loop of [beats] beats from the current (quantized) position. */
    fun beatLoop(beats: Double) {
        val fpb = framesPerBeat
        if (fpb <= 0) return
        val start = if (quantize) frameOfBeat(floor(beatAt(pos) + 1e-6)) else pos
        setLoop(start, start + beats * fpb)
    }

    fun loopInHere() { loopIn = snap(pos); loopOut = -1.0; loopOn = false }

    fun loopOutHere() { val o = snap(pos); if (loopIn >= 0 && o > loopIn) setLoop(loopIn, o) }

    /** Reloop / exit. Re-entering from outside the loop jumps to its start. */
    fun toggleLoop() {
        if (loopIn < 0 || loopOut <= loopIn) return
        loopOn = !loopOn
        if (loopOn && (pos < loopIn || pos >= loopOut)) jump(loopIn)
    }

    fun loopHalve() { if (loopOut > loopIn && loopOut - loopIn > 64) loopOut = loopIn + (loopOut - loopIn) / 2 }
    fun loopDouble() { if (loopOut > loopIn) setLoop(loopIn, loopIn + (loopOut - loopIn) * 2) }

    fun setLoop(a: Double, b: Double) {
        val last = ((track?.frames ?: 1) - 1).toDouble()
        loopIn = a.coerceIn(0.0, last); loopOut = b.coerceIn(0.0, last); loopOn = loopOut > loopIn
    }

    /** Match tempo to [other] and land on the same point within the beat (one-shot beat sync). */
    fun syncTo(other: Deck) {
        if (bpm <= 0 || other.bpm <= 0) return
        tempo = (other.bpm * other.tempo / bpm).coerceIn(1 - MAX_SYNC, 1 + MAX_SYNC)
        val phase = other.beatAt(other.pos).let { it - floor(it) }
        val mine = beatAt(pos)
        val base = floor(mine) + phase
        val target = listOf(base - 1, base, base + 1).minByOrNull { abs(it - mine) }!!
        jump(frameOfBeat(target))
    }

    // ---- audio -----------------------------------------------------------------------------------------

    /**
     * Writes [frames] stereo frames into [out] (which must be zeroed) at [outRate] Hz. The source sample rate folds
     * into the read rate, so no separate resampler. Loops are sample-accurate; playback stops at either end.
     */
    fun render(out: FloatArray, frames: Int, outRate: Int) {
        val t = track ?: return
        val last = (t.frames - 1).toDouble()
        val s = seekReq
        if (s >= 0) { pos = s.coerceIn(0.0, last); seekReq = -1.0; sonic = null }
        if (smoothReq) { smoothReq = false; smoothLeft = SMOOTH_FRAMES }
        val scr = scratching
        val lIn = loopIn; val lOut = loopOut
        val looping = loopOn && lOut > lIn
        // Slip: the ghost keeps the true position while scratching or looping; releasing jumps back to it.
        val slipNow = slip && (scr || looping)
        if (wasSlipping && !slipNow && slip) { pos = ghost.coerceIn(0.0, last); sonic = null }
        wasSlipping = slipNow
        slipping = slipNow
        if (!playing && !scr) { ghost = pos; sonic = null; return }

        val base = t.sampleRate.toDouble() / outRate
        val nf = nudgeFresh
        val speed = tempo * (1 + if (nf > 0) nudgeAmt else 0.0)
        if (nf > 0) nudgeFresh = nf - frames
        val tempoRate = speed * base
        val target = if (scr) {
            val f = scratchFresh
            if (f > 0) { scratchFresh = f - frames; scratchRate * base } else 0.0
        } else tempoRate
        if (slipNow && playing) ghost = (ghost + frames * tempoRate).coerceIn(0.0, last)

        val useSonic = keylock && !scr && smoothLeft <= 0 && abs(speed - 1) > 0.001
        if (useSonic) {
            pos = renderKeylock(out, frames, t, base, speed, looping, lIn, lOut, last)
        } else {
            sonic = null
            pos = renderVarispeed(out, frames, t, target, scr, looping, lIn, lOut, last)
        }
        if (!slipNow) ghost = pos
    }

    /** Linear-interpolated read at [target] frames per output frame (ramped after jog transitions). */
    private fun renderVarispeed(
        out: FloatArray, frames: Int, t: PcmTrack, target: Double, scr: Boolean,
        looping: Boolean, lIn: Double, lOut: Double, last: Double,
    ): Double {
        val pcm = t.pcm
        val v = vocTrack; val ins = instTrack
        val gv = vocGain; val gi = instGain
        val stems = v != null && ins != null && (gv != 1f || gi != 1f)
        val lastS = if (stems) minOf(v!!.frames, ins!!.frames) - 1 else 0
        val vp = v?.pcm; val ip = ins?.pcm
        val read: (Int, Int) -> Float = if (stems) { i, ch -> val k = (if (i > lastS) lastS else i) * 2 + ch; vp!![k] * gv + ip!![k] * gi }
        else { i, ch -> pcm[i * 2 + ch].toFloat() }
        var p = pos
        for (f in 0 until frames) {
            // One-pole rate ramp: always while scratching (zipper-free), and for SMOOTH_FRAMES after a jog transition.
            val rate = if (scr || smoothLeft > 0) { curRate += (target - curRate) * SMOOTH; if (smoothLeft > 0) smoothLeft--; curRate } else { curRate = target; target }
            if (looping) {
                if (rate > 0 && p >= lOut) p -= lOut - lIn
                else if (rate < 0 && p < lIn) p += lOut - lIn
            }
            if (p < 0.0 || p >= last) { if (!scr) playing = false; p = p.coerceIn(0.0, last); break }
            val i = p.toInt()
            val fr = (p - i).toFloat()
            // 4-point Catmull-Rom: exact on linear ramps, far less aliasing than linear at non-unity rates.
            val i0 = if (i > 0) i - 1 else 0
            val i3 = if (i + 2 < t.frames) i + 2 else t.frames - 1
            for (ch in 0..1) {
                val y0 = read(i0, ch); val y1 = read(i, ch)
                val y2 = read(i + 1, ch); val y3 = read(i3, ch)
                val v = if (fr == 0f) y1 else y1 + 0.5f * fr * (y2 - y0 + fr * (2 * y0 - 5 * y1 + 4 * y2 - y3 + fr * (3 * (y1 - y2) + y3 - y0)))
                out[f * 2 + ch] = v / 32768f
            }
            p += rate
        }
        // Normalise once more so a block never ends with the head parked exactly on the loop boundary.
        if (looping) {
            if (curRate > 0 && p >= lOut) p -= lOut - lIn
            else if (curRate < 0 && p < lIn) p += lOut - lIn
        }
        return p
    }

    /**
     * Sonic time-stretch at [speed] with the pitch held; Sonic also resamples to the output rate. Audio is fed from
     * [feedPos], which runs ahead of [pos] by Sonic's internal latency; [pos] advances exactly like varispeed would.
     */
    private fun renderKeylock(
        out: FloatArray, frames: Int, t: PcmTrack, base: Double, speed: Double,
        looping: Boolean, lIn: Double, lOut: Double, last: Double,
    ): Double {
        val s = sonic ?: Sonic(t.sampleRate, 2).also {
            it.setRate(base.toFloat()); it.setPitch(1f); it.setQuality(1); sonic = it; feedPos = pos
        }
        s.setSpeed(speed.toFloat())
        while (s.samplesAvailable() < frames) {
            if (looping && feedPos >= lOut) feedPos -= lOut - lIn
            val v = vocTrack; val ins = instTrack
            val gv = vocGain; val gi = instGain
            val stems = v != null && ins != null && (gv != 1f || gi != 1f)
            val end = if (stems) minOf(last, minOf(v!!.frames, ins!!.frames) - 1.0) else last
            val n = minOf(FEED, (end - feedPos).toInt())
            if (n <= 0) { s.flushStream(); break }
            val at = feedPos.toInt() * 2
            if (stems) {
                v!!.pcm.duplicate().apply { position(at) }.get(feedShorts, 0, n * 2)
                ins!!.pcm.duplicate().apply { position(at) }.get(feedShorts2, 0, n * 2)
                for (i in 0 until n * 2) feedFloats[i] = (feedShorts[i] * gv + feedShorts2[i] * gi) / 32768f
            } else {
                t.pcm.duplicate().apply { position(at) }.get(feedShorts, 0, n * 2)
                for (i in 0 until n * 2) feedFloats[i] = feedShorts[i] / 32768f
            }
            s.writeFloatToStream(feedFloats, n)
            feedPos += n
        }
        s.readFloatFromStream(out, frames) // short reads leave the zeroed tail
        var p = pos + frames * speed * base
        if (looping && p >= lOut) p -= lOut - lIn
        if (p >= last) { playing = false; p = last }
        return p
    }

    companion object {
        const val HOT_CUES = 8
        /** Sync may push the tempo this far from the original (the tempo fader's own range). */
        const val MAX_SYNC = 0.16
        /** Output frames a jog value stays valid without a new touch event (~60 ms at 48 kHz). */
        const val FRESH_FRAMES = 3000
        /** Rate ramp after touching or releasing the platter (~150 ms at 48 kHz). */
        const val SMOOTH_FRAMES = 7200
        private const val SMOOTH = 0.001
        private const val FEED = 1024
    }
}
