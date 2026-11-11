package com.prabhat.djmix.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.prabhat.djmix.R

/** Vertical level meter: green to 70 %, amber to 90 %, red above. */
class MeterView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val d = resources.displayMetrics.density
    private val bg = Paint().apply { color = context.getColor(R.color.control_dim) }
    private val ok = Paint().apply { color = context.getColor(R.color.meter_ok) }
    private val hot = Paint().apply { color = context.getColor(R.color.meter_hot) }
    private val clip = Paint().apply { color = context.getColor(R.color.meter_clip) }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, 3 * d, 3 * d, bg)
        val top = h * (1 - level)
        c.save()
        c.clipRect(0f, top, w, h)
        c.drawRect(0f, h * 0.3f, w, h, ok)
        c.drawRect(0f, h * 0.1f, w, h * 0.3f, hot)
        c.drawRect(0f, 0f, w, h * 0.1f, clip)
        c.restore()
    }
}
