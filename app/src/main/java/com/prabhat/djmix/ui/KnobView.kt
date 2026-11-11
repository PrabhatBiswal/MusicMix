package com.prabhat.djmix.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.prabhat.djmix.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Rotary knob, 270° of travel: drag up or down, double-tap resets, long-press is the caller's (EQ kill). [label] sits in the bottom gap. */
class KnobView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** 0..1; 0.5 is the centre of a bipolar knob. */
    var value = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var default = 0.5f
    /** Bipolar knobs fill from the top; unipolar from the start of travel. */
    var bipolar = true
    var accent = context.getColor(R.color.deck_a)
        set(v) { field = v; invalidate() }
    var killed = false
        set(v) { field = v; invalidate() }
    var onChange: ((Float) -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var label = ""
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.control) }
    private val bgKill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.kill_tint) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d; color = context.getColor(R.color.border) }
    private val borderKill = Paint(border).apply { color = context.getColor(R.color.kill) }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND; color = context.getColor(R.color.border) }
    private val arc = Paint(track)
    private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND; color = context.getColor(R.color.text) }
    private val pointerKill = Paint(pointer).apply { color = context.getColor(R.color.kill) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8 * d; isFakeBoldText = true; textAlign = Paint.Align.CENTER; color = context.getColor(R.color.muted) }
    private val rect = RectF()

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            value += distanceY / (140 * d)
            onChange?.invoke(value)
            return true
        }
        override fun onLongPress(e: MotionEvent) { onLongPress?.invoke() }
        override fun onDoubleTap(e: MotionEvent): Boolean { value = default; onChange?.invoke(value); return true }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        detector.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - d
        c.drawCircle(cx, cy, r, if (killed) bgKill else bg)
        c.drawCircle(cx, cy, r, if (killed) borderKill else border)
        val rr = r - 2.5f * d
        rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
        c.drawArc(rect, 135f, 270f, false, track)
        val ang = 135f + 270f * value
        if (!killed) {
            arc.color = accent
            if (bipolar) c.drawArc(rect, 270f, ang - 270f, false, arc) else c.drawArc(rect, 135f, ang - 135f, false, arc)
        }
        val rad = Math.toRadians(ang.toDouble())
        c.drawLine(cx, cy, cx + (rr * 0.72f) * cos(rad).toFloat(), cy + (rr * 0.72f) * sin(rad).toFloat(), if (killed) pointerKill else pointer)
        if (label.isNotEmpty()) c.drawText(label, cx, cy + r - 2 * d, text)
    }
}
