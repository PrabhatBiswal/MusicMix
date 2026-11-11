package com.prabhat.djmix.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * An unattended mashup on the two decks. Every song enters on its hook (hot cue 1) and drops it on a 16-beat phrase of
 * the song playing, plays [SEGMENT] beats, then hands over with one of the [Move]s. The timing runs on the mixer
 * thread: a cue fires before the block it affects, so a loop, cut or fader move lands within one block (~11 ms) of its
 * beat. [beat] counts the playing deck's beats from its tempo, not its read head, so loops on it never stall the clock.
 * The caller loads the songs: [start] with the first, then [handOver] each time the previous one is out, then [end].
 */
class Mashup(private val m: Mixer) {
    /**
     * BLEND and FILTER overlap the two songs; CUT switches on the 1 with one groove running on; HOOKS swaps the two
     * hooks every 16 beats before x carries on; these four play both songs at one tempo ([ONE_TEMPO]). STOP drops x after
     * a silence at its own tempo; ROLL and ECHO cut out of y at any tempo gap.
     */
    enum class Move { BLEND, ROLL, FILTER, ECHO, STOP, CUT, HOOKS }

    fun interface Action { fun run(progress: Double) }
    private class Cue(val from: Double, val to: Double, val action: Action)
    private val cues = ArrayList<Cue>()

    /** Beats of [clock] since the first song's hook. Written by the mixer thread, and by [start] while no clock runs. */
    @Volatile var beat = 0.0
        private set
    /** The deck whose tempo drives [beat]; null = no mashup. */
    @Volatile var clock: Deck? = null
        private set
    /** The next hand-over (or the end) and when, for the status line. */
    @Volatile var nextDrop = 0.0
        private set
    @Volatile var nextLabel = ""
        private set
    /** The sampler deck holding the riser that fills a STOP's silence; null = silence only. */
    var riser: Deck? = null
    // UI thread: the clock beat where the playing song dropped its hook, and the beat from which it is back at its own
    // tempo (no hand-over may start before that).
    private var drop = 0.0
    private var settled = 0.0

    /** Mixer thread, before the decks render [frames] frames at [rate] Hz. Actions must not call [stop]. */
    fun tick(frames: Int, rate: Int) {
        synchronized(cues) {
            var i = 0
            while (i < cues.size) {
                val c = cues[i]
                val b = beat
                if (b < c.from) { i++; continue }
                val p = if (b >= c.to) 1.0 else (b - c.from) / (c.to - c.from)
                c.action.run(p)
                if (p >= 1.0) cues.removeAt(i) else i++
            }
        }
        // Time runs on while the clock deck is stopped: an echo out stops it a beat before the drop that hands over.
        val d = clock ?: return
        if (d.bpm > 0) beat += frames * d.bpm * d.tempo / 60.0 / rate
    }

    /** [action] runs once, in the first block at or after [at]. */
    private fun at(at: Double, action: Action) = ramp(at, at, action)

    /** [action] runs every block from [from] to [to] with progress 0..1; the last call always gets exactly 1. */
    private fun ramp(from: Double, to: Double, action: Action) { synchronized(cues) { cues += Cue(from, to, action) } }

    fun stop() { synchronized(cues) { cues.clear() }; clock = null; nextLabel = "" }

    /** First song, on [d]: starts up to 16 beats before its hook with the high-pass opening, so the hook is the drop. */
    fun start(d: Deck) {
        stop()
        drop = 0.0; settled = 0.0
        m.crossfader = 0.5f; m.cueA = false; m.cueB = false
        val hook = hook(d)
        val lead = lead(d, hook, 16, 1.0)
        beat = -lead // the clock is stopped, so this cannot race; hand-overs planned before the first block see it
        at(Double.NEGATIVE_INFINITY) {
            reset(d); d.tempo = 1.0; d.volume = 1f; d.jump(hook - lead * d.framesPerBeat); d.playing = true
            clock = d; beat = -lead
        }
        ramp(-lead, 0.0) { p -> strip(d).filter = 0.6 * (1 - p) }
    }

    /**
     * From [y] (playing, on the clock) to [x] (loaded): x drops its hook on y's next phrase, [beats] after y's drop.
     * [wanted] is the move, null = BLEND. The moves that play the two songs at one tempo (BLEND, FILTER, CUT, HOOKS) meet
     * at [meetTempo]: y slides a third of the way (keylock on) over [RAMP] beats before x enters, x is stretched the
     * rest, and once alone x glides back to its own tempo. When the tempos are too far apart for that, they become a
     * STOP, which drops x at its own tempo after a silence, like a lane change in a reel mashup.
     * [done] runs on the mixer thread once y is free for the next song.
     */
    fun handOver(y: Deck, x: Deck, wanted: Move? = null, beats: Double = SEGMENT, done: () -> Unit) {
        val bpm = y.bpm // y is back at its own tempo by the time it has to move (see [settled])
        val meet = meetTempo(bpm, x.bpm)
        val move = when {
            wanted == null -> if (meet != null) Move.BLEND else Move.STOP
            wanted in ONE_TEMPO && meet == null -> Move.STOP
            else -> wanted
        }
        val synced = meet != null && move != Move.STOP
        val yTempo = if (synced) meet!! / bpm else 1.0
        val xTempo = if (synced) meet!! / (x.bpm * fold(bpm, x.bpm)) else 1.0
        val ratio = x.bpm * xTempo / (bpm * yTempo) // x's beats per clock beat while both run
        val hook = hook(x)
        val lead = lead(x, hook, if (move == Move.BLEND || move == Move.FILTER) 48 else 16, ratio)
        val slide = if (yTempo != 1.0) RAMP else 0.0
        val gap = if (move == Move.STOP) SILENCE / ratio else 0.0 // clock beats of silence: [SILENCE] of x's beats
        val turns = if (move == Move.HOOKS) 2 * HOOK_ROUNDS - 2 else 0 // hook swaps after x's first
        val stop = nextPhrase(y, maxOf(0.0, lead - gap) + slide, beats) // y's phrase where the hand-over starts
        val t = stop + gap // x's hook lands here
        val end = t + 16.0 * turns // from here x carries on alone
        val s = t - lead
        val sx = strip(x); val sy = strip(y)
        nextDrop = t; nextLabel = move.name
        if (slide > 0) {
            at(s - slide) { y.keylock = true }
            ramp(s - slide, s) { p -> y.tempo = 1 + (yTempo - 1) * p }
        }
        at(s) { // x starts in the headphones only, placed so its hook lands exactly on t
            reset(x); x.tempo = xTempo; x.keylock = true; x.volume = 0f
            x.jump(hook - (t - beat) * ratio * x.framesPerBeat); x.playing = true
            pfl(x, true)
        }
        val fadeIn = maxOf(s, t - 32)
        when (move) {
            Move.BLEND -> { // x joins on its highs, takes over the mids (the vocal) 16 beats out and the bass on the drop
                val mids = maxOf(fadeIn + 4, t - 16)
                at(s) { sx.lowDb = Strip.KILL_DB; sx.midDb = Strip.KILL_DB }
                ramp(fadeIn, fadeIn + 8) { p -> x.volume = sin(p * PI / 2).toFloat() }
                ramp(mids - 2, mids + 2) { p -> sy.midDb = Strip.KILL_DB * p; sx.midDb = Strip.KILL_DB * (1 - p) }
                ramp(t - 2, t) { p -> sy.lowDb = Strip.KILL_DB * p; sx.lowDb = Strip.KILL_DB * (1 - p) }
                ramp(t, t + 8) { p -> y.volume = cos(p * PI / 2).toFloat() }
                at(t + 8) { y.playing = false }
            }
            Move.FILTER -> { // x high-passed over y low-passed, then x opens up on the drop
                at(s) { sx.filter = 0.6 }
                ramp(fadeIn, fadeIn + 8) { p -> x.volume = sin(p * PI / 2).toFloat() }
                ramp(fadeIn, t) { p -> sx.filter = 0.6 - 0.35 * p; sy.filter = -0.5 * p }
                at(t) { cut(y); sx.filter = 0.0 }
            }
            Move.ROLL -> { // y's last bar repeats as 4, 2, then 1 beat while a high-pass rises, hard cut to x
                ramp(t - 8, t) { p -> sy.filter = 0.4 * p }
                at(t - 8) { y.beatLoop(4.0) } // plays through once, so each shorter loop starts back on bar t-8
                at(t - 4) { y.beatLoop(2.0) }
                at(t - 2) { y.beatLoop(1.0) }
                at(t) { cut(y) }
            }
            Move.ECHO -> echoOut(y, t)
            Move.STOP -> { // y stops dead on the 1, a riser fills the silence, x's hook drops on the next 1, louder
                at(stop) { cut(y) }
                riser?.track?.let { r -> at(t - r.frames.toDouble() / r.sampleRate * bpm / 60) { riser?.apply { tempo = 1.0; jump(0.0); playing = true } } }
                at(t) { sx.macroGain = BOOST }
                ramp(t + 6, t + 8) { p -> sx.macroGain = BOOST + (1 - BOOST) * p.toFloat() }
            }
            Move.CUT -> at(t) { cut(y) } // one groove carries on: the switch is on the 1, nothing overlaps
            Move.HOOKS -> for (k in 0..turns) { // the two hooks take turns, 16 beats each, starting and ending with x
                val at = t + 16.0 * k
                val on = if (k % 2 == 0) x else y
                val off = if (k % 2 == 0) y else x
                val onHook = if (on === x) hook else hook(y)
                val onRatio = if (on === x) ratio else 1.0
                if (k > 0) at(at - 2) { on.jump(onHook - (at - beat) * onRatio * on.framesPerBeat) } // still silent
                at(at) { on.volume = 1f; off.volume = 0f }
            }
        }
        at(t) { pfl(x, false) }
        at(end) { x.volume = 1f; clock = x; beat = end + x.beatAt(x.pos) - x.beatAt(hook) }
        val free = end + 8
        at(free) { y.playing = false; reset(y); done() }
        if (xTempo != 1.0) ramp(free, free + GLIDE) { p -> x.tempo = xTempo + (1 - xTempo) * p }
        drop = end; settled = if (xTempo != 1.0) free + GLIDE else free
    }

    /** Last song: a low-pass sweep over its last phrase, then an echo out. [done] runs on the mixer thread. */
    fun end(y: Deck, beats: Double = SEGMENT, done: () -> Unit) {
        val t = nextPhrase(y, 16.0, beats)
        nextDrop = t; nextLabel = "END"
        ramp(t - 16, t) { p -> strip(y).filter = -0.7 * p }
        echoOut(y, t)
        at(t + 8) { reset(y); clock = null; nextLabel = ""; done() }
    }

    /** Echo engaged a beat before the fader cut, so the last beat repeats into [t]. */
    private fun echoOut(y: Deck, t: Double) {
        at(t - 2) { echo(y, 0.8f) }
        at(t - 1) { y.volume = 0f; y.playing = false }
    }

    private fun echo(d: Deck, depth: Float) { fx(d).apply { type = Fx.Type.ECHO; beats = 1.0; this.depth = depth; on = true } }

    private fun cut(d: Deck) { d.volume = 0f; d.playing = false; if (d.loopOn) d.toggleLoop(); strip(d).filter = 0.0 }

    /** Flat channel, no FX, no loop. */
    private fun reset(d: Deck) {
        strip(d).apply { lowDb = 0.0; midDb = 0.0; highDb = 0.0; killLow = false; killMid = false; killHigh = false; filter = 0.0; vox = 0.0; macroGain = 1f }
        d.vocGain = 1f; d.instGain = 1f
        fx(d).on = false
        if (d.loopOn) d.toggleLoop()
        pfl(d, false)
    }

    /** [beats] after the playing song's drop, fewer if [y] runs out first, later if [lead] beats no longer fit. */
    private fun nextPhrase(y: Deck, lead: Double, beats: Double): Double {
        val left = y.track?.let { y.beatAt(it.frames - 1.0) - y.beatAt(hook(y)) } ?: 0.0 // beats after its hook
        val room = floor((left - 8) / 16) * 16
        var t = drop + room.coerceIn(16.0, maxOf(16.0, beats))
        while (t - lead < maxOf(beat + 2, settled)) t += 16
        return t
    }

    private fun hook(d: Deck) = d.hotCues[0].takeIf { it >= 0 } ?: d.frameOfBeat(16.0)

    /** Clock beats [x] can play before its hook: up to [pre], never from before the track's first beat. */
    private fun lead(x: Deck, hook: Double, pre: Int, ratio: Double) = minOf(pre.toDouble(), floor(x.beatAt(hook) / ratio)).coerceAtLeast(0.0)

    private fun strip(d: Deck) = if (d === m.a) m.stripA else m.stripB
    private fun fx(d: Deck) = if (d === m.a) m.fxA else m.fxB
    private fun pfl(d: Deck, on: Boolean) { if (d === m.a) m.cueA = on else m.cueB = on }

    companion object {
        val ONE_TEMPO = setOf(Move.BLEND, Move.FILTER, Move.CUT, Move.HOOKS)
        /** Beats of silence (at the incoming tempo) in a STOP. */
        const val SILENCE = 4.0
        /** Gain on the song a STOP drops, for its first 8 beats: +2.5 dB, the reel's loudest moment. */
        const val BOOST = 1.33f
        /** Times the incoming hook plays in a HOOKS swap (with the outgoing hook between). */
        const val HOOK_ROUNDS = 3
        /** Beats from one song's drop to the next: 32 bars, about a minute. */
        const val SEGMENT = 128.0
        /** Beats a stretched song takes to glide back to its own tempo once it plays alone. */
        const val GLIDE = 32.0
        /** Within this the incoming song simply takes the playing one's tempo. */
        const val MATCH = 0.06
        /** Widest tempo gap two songs still overlap across; beyond it the hand-over cuts. */
        const val GAP = 0.16
        /** Beats the playing song takes to slide its third of the way. */
        const val RAMP = 32.0

        /** The half-, single- or double-time reading of [xBpm] closest to [bpm]. */
        fun fold(bpm: Double, xBpm: Double) = doubleArrayOf(0.5, 1.0, 2.0).minBy { abs(bpm / (xBpm * it) - 1) }

        /**
         * The tempo, in [bpm]'s beats, at which a song at [xBpm] can overlap one at [bpm]: [bpm] itself within [MATCH];
         * up to [GAP] apart, a third of the way from [bpm] (the room is dancing to that song, so it moves least);
         * null when they are further apart.
         */
        fun meetTempo(bpm: Double, xBpm: Double): Double? {
            val xb = xBpm * fold(bpm, xBpm)
            return when {
                abs(bpm / xb - 1) <= MATCH -> bpm
                abs(ln(bpm / xb)) <= ln(1 + GAP) -> bpm * (xb / bpm).pow(1.0 / 3)
                else -> null
            }
        }
    }
}
