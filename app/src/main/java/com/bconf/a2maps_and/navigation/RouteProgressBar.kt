package com.bconf.a2maps_and.navigation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.bconf.a2maps_and.R

/**
 * Route progress in the bottom bar: the travelled part muted, the rest in the route colour and a
 * car cursor at the position. One colour on purpose — offline there is no traffic to show.
 */
class RouteProgressBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** Travelled share of the route, 0..1. */
    var fraction = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) { field = clamped; invalidate() }
        }

    private val density = resources.displayMetrics.density
    private val passed = paint(R.color.nav_track)
    private val remaining = paint(R.color.nav_accent)
    private val cursorFill = paint(R.color.nav_vehicle)
    private val cursorOutline = paint(R.color.nav_vehicle_outline).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; strokeJoin = Paint.Join.ROUND
    }
    private val cursor = Path()

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ContextCompat.getColor(context, color) }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val half = 3f * density
        // Inset by the cursor so it stays whole at both ends.
        val inset = 8f * density
        val start = paddingLeft + inset
        val end = width - paddingRight - inset
        if (end <= start) return
        val x = start + (end - start) * fraction
        canvas.drawRoundRect(start, cy - half, end, cy + half, half, half, passed)
        canvas.drawRoundRect(x.coerceAtMost(end - 2 * half), cy - half, end, cy + half, half, half, remaining)
        val h = (height / 2f - 1.5f * density).coerceAtMost(7f * density)
        cursor.rewind()
        cursor.moveTo(x + h, cy)
        cursor.lineTo(x - h, cy - h)
        cursor.lineTo(x - h * 0.45f, cy)
        cursor.lineTo(x - h, cy + h)
        cursor.close()
        canvas.drawPath(cursor, cursorFill)
        canvas.drawPath(cursor, cursorOutline)
    }
}
