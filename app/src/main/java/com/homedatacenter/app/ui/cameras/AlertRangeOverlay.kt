package com.homedatacenter.app.ui.cameras

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * v1.5.12: an overlay that paints a red bar at specific time ranges
 * on top of a SeekBar (or any horizontal bar). The bar aligns with
 * the SeekBar's drawable area so the alert ranges visually overlap
 * the progress track.
 *
 * Usage:
 *  1. Place [AlertRangeOverlay] ABOVE the [android.widget.SeekBar]
 *     in a FrameLayout (or as a sibling aligned to the SeekBar's
 *     top/bottom edges — they share the same horizontal extent).
 *  2. Call [setAlertRanges] with a list of (startMs, endMs) pairs,
 *     each representing an alert time interval within the 0..max
 *     range of the SeekBar.
 *  3. Call [setMax] with the same max as the SeekBar so the
 *     overlay can correctly map time ranges to pixels.
 *
 * The overlay is purely visual — it does NOT intercept touches,
 * so the user can drag the SeekBar through the alert regions.
 */
class AlertRangeOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935") // Material Red 600
        strokeWidth = 0f  // filled rects, no stroke
        isDither = true
    }

    private val alertRanges = mutableListOf<Pair<Long, Long>>()
    private var maxMs: Long = 1L

    /**
     * Sets the maximum value of the corresponding SeekBar. Used to
     * map time ranges to pixel positions. Must be called BEFORE
     * [setAlertRanges] for the mapping to be correct.
     */
    fun setMax(max: Long) {
        maxMs = if (max <= 0) 1L else max
        invalidate()
    }

    /**
     * Sets the alert time ranges to render. Each pair is
     * (startMs, endMs) in the same time scale as the SeekBar's
     * progress (0..max). Pass an empty list to clear the overlay.
     */
    fun setAlertRanges(ranges: List<Pair<Long, Long>>) {
        alertRanges.clear()
        alertRanges.addAll(ranges)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (alertRanges.isEmpty() || width <= 0 || height <= 0) return
        val h = height.toFloat()
        // Draw the bar height as a slim 4dp band at the top of the
        // overlay so it doesn't obscure the SeekBar's thumb.
        val barH = 4f * resources.displayMetrics.density
        for ((startMs, endMs) in alertRanges) {
            val startRatio = (startMs.toFloat() / maxMs).coerceIn(0f, 1f)
            val endRatio = (endMs.toFloat() / maxMs).coerceIn(0f, 1f)
            val left = startRatio * width
            val right = endRatio * width
            if (right - left < 1f) continue  // skip sub-pixel ranges
            canvas.drawRect(left, 0f, right, barH, alertPaint)
        }
    }
}
