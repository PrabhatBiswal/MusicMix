package com.prabhat.djmix.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.prabhat.djmix.R

/** Vertical fader: 0 at the bottom, 1 at the top. [detent] draws a centre mark (tempo). */
class VFaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var value = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var detent = false
    var thumbColor = context.getColor(R.color.text)
        set(v) { field = v; invalidate() }
    var onChange: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.control_dim) }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.muted); strokeWidth = d }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val thumbH get() = 22 * d

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val travel = height - thumbH
                value = 1f - (e.y - thumbH / 2) / travel
                onChange?.invoke(value)
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val w = 8 * d
        rect.set(cx - w / 2, thumbH / 2, cx + w / 2, height - thumbH / 2)
        c.drawRoundRect(rect, w / 2, w / 2, track)
        if (detent) c.drawLine(cx - 8 * d, height / 2f, cx + 8 * d, height / 2f, mark)
        val y = thumbH / 2 + (1f - value) * (height - thumbH)
        thumb.color = thumbColor
        val tw = width / 2f - 2 * d
        rect.set(cx - tw, y - thumbH / 2, cx + tw, y + thumbH / 2)
        c.drawRoundRect(rect, 4 * d, 4 * d, thumb)
    }
}
