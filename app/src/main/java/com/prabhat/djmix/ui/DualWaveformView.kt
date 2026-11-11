package com.prabhat.djmix.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.prabhat.djmix.R
import com.prabhat.djmix.audio.Deck
import com.prabhat.djmix.audio.PcmTrack
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Both decks in one panel: two lanes sharing an 8-second window under a fixed centre playhead, each with its beat
 * grid, loop, hot cues and slip ghost, and a thin overview strip (tap to seek) under each lane.
 * Dragging a lane is the jog wheel: it nudges a playing deck and scratches a paused one.
 */
class DualWaveformView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var deckA: Deck? = null
    var deckB: Deck? = null
    var windowSeconds = 8.0

    private val d = resources.displayMetrics.density
    private val colA = context.getColor(R.color.deck_a); private val dimA = context.getColor(R.color.deck_a_dim)
    private val colB = context.getColor(R.color.deck_b); private val dimB = context.getColor(R.color.deck_b_dim)
    private val bg = Paint().apply { color = context.getColor(R.color.surface) }
    private val ov = Paint().apply { color = context.getColor(R.color.control) }
    private val wave = Paint().apply { strokeWidth = 2 * d }
    private val grid = Paint().apply { color = 0x24FFFFFF; strokeWidth = d }
    private val bar = Paint().apply { color = 0x61FFFFFF; strokeWidth = d }
    private val head = Paint().apply { color = context.getColor(R.color.white); strokeWidth = 2 * d }
    private val loopFill = Paint().apply { color = 0x2945D483 }
    private val loopLine = Paint().apply { color = context.getColor(R.color.loop); strokeWidth = d }
    private val ghost = Paint().apply { color = 0x80FF4D4D.toInt(); strokeWidth = 2 * d }
    private val flag = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flagText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8 * d; isFakeBoldText = true }
    private val marker = Paint().apply { color = context.getColor(R.color.white) }
    private val path = Path()
    private var lines = FloatArray(0)

    private val ovH get() = 6 * d
    private val gap get() = 2 * d
    private val laneH get() = (height - 2 * ovH - 3 * gap) / 2

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val lh = laneH
        drawLane(c, deckA, gap, lh, colA, dimA)
        drawOverview(c, deckA, gap + lh, colA)
        drawLane(c, deckB, 2 * gap + lh + ovH, lh, colB, dimB)
        drawOverview(c, deckB, 2 * gap + 2 * lh + ovH, colB)
        c.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), head)
    }

    private fun drawLane(c: Canvas, dk: Deck?, top: Float, lh: Float, col: Int, dim: Int) {
        val dck = dk ?: return
        val t = dck.track ?: return
        val peaks = t.peaks
        val n = peaks.size
        val w = width
        if (w == 0 || n == 0) return
        val pf = PcmTrack.PEAK_FRAMES
        val span = windowSeconds * t.sampleRate / pf
        val start = dck.pos / pf - span / 2
        fun fx(frame: Double) = ((frame / pf - start) / span * w).toFloat()
        val mid = top + lh / 2
        if (lines.size < w * 4) lines = FloatArray(w * 4)
        var k = 0
        for (x in 0 until w) {
            val i0 = maxOf((start + x * span / w).toInt(), 0)
            val i1 = minOf((start + (x + 1) * span / w).toInt(), n - 1)
            var p = 0f
            if (i0 <= i1) for (i in i0..i1) if (peaks[i] > p) p = peaks[i]
            val y = p * (lh / 2 - d)
            lines[k++] = x.toFloat(); lines[k++] = mid - y; lines[k++] = x.toFloat(); lines[k++] = mid + y
        }
        val split = (w / 2) * 4
        wave.color = dim; c.drawLines(lines, 0, split, wave)
        wave.color = col; c.drawLines(lines, split, w * 4 - split, wave)
        val fpb = dck.framesPerBeat
        if (fpb > 0) {
            val first = ceil((start * pf - dck.firstBeat) / fpb).toLong()
            val last = floor(((start + span) * pf - dck.firstBeat) / fpb).toLong()
            for (b in first..last) { val x = fx(dck.firstBeat + b * fpb); c.drawLine(x, top, x, top + lh, if (b % 4 == 0L) bar else grid) }
        }
        if (dck.loopOn && dck.loopOut > dck.loopIn) {
            val x0 = fx(dck.loopIn); val x1 = fx(dck.loopOut)
            c.drawRect(x0, top, x1, top + lh, loopFill)
            c.drawLine(x0, top, x0, top + lh, loopLine); c.drawLine(x1, top, x1, top + lh, loopLine)
        }
        flag.color = col; flagText.color = col
        for (i in dck.hotCues.indices) {
            val f = dck.hotCues[i]
            if (f < 0) continue
            val x = fx(f)
            if (x < -10 * d || x > w + 10 * d) continue
            path.reset(); path.moveTo(x - 5 * d, top); path.lineTo(x + 5 * d, top); path.lineTo(x, top + 7 * d); path.close()
            c.drawPath(path, flag)
            c.drawText("${i + 1}", x + 7 * d, top + 8 * d, flagText)
        }
        if (dck.slipping) { val gx = fx(dck.ghost); if (gx >= 0 && gx <= w) c.drawLine(gx, top, gx, top + lh, ghost) }
    }

    private fun drawOverview(c: Canvas, dk: Deck?, top: Float, col: Int) {
        c.drawRect(0f, top, width.toFloat(), top + ovH, ov)
        val dck = dk ?: return
        if (dck.track == null) return
        val x = (dck.position * width).toFloat()
        wave.color = col
        c.drawRect(0f, top, x, top + ovH, wave.apply { alpha = 140 })
        wave.alpha = 255
        c.drawRect(x - d, top - d, x + d, top + ovH + d, marker)
    }

    private var seekDeck: Deck? = null
    private var jogDeck: Deck? = null
    private var scratching = false
    private var lastX = 0f
    private var lastT = 0L

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val lh = laneH
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val y = e.y
                seekDeck = null; jogDeck = null
                when {
                    y >= gap + lh - 8 * d && y <= gap + lh + ovH + 8 * d -> seekDeck = deckA
                    y >= 2 * gap + 2 * lh + ovH - 8 * d -> seekDeck = deckB
                    y < gap + lh -> jogDeck = deckA
                    y > 2 * gap + lh + ovH -> jogDeck = deckB
                }
                val j = jogDeck
                if (seekDeck == null && j == null) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                if (j != null) { scratching = !j.playing; if (scratching) j.scratchStart(); lastX = e.x; lastT = e.eventTime }
                seekDeck?.seekTo((e.x / width).toDouble())
            }
            MotionEvent.ACTION_MOVE -> {
                seekDeck?.seekTo((e.x / width).toDouble())
                jogDeck?.let { j ->
                    val dt = (e.eventTime - lastT) / 1000.0
                    if (dt > 0) {
                        val rate = -(e.x - lastX) / width * windowSeconds / dt // audio seconds per second; drag right = rewind
                        if (scratching) j.scratch(rate.coerceIn(-8.0, 8.0)) else j.nudge((rate * NUDGE).coerceIn(-0.2, 0.2))
                    }
                    lastX = e.x; lastT = e.eventTime
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                jogDeck?.let { if (scratching) it.scratchEnd() else it.nudge(0.0) }
                jogDeck = null; seekDeck = null
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private companion object {
        /** Dragging one window-width per second (8 s of audio) bends the tempo by this much times 8. */
        const val NUDGE = 0.06
    }
}
