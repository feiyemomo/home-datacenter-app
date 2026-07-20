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
        // v1.5.14: draw the red bar at the FULL height of the overlay
        // (was 4dp) so the alert segment is as tall as the SeekBar's
        // progress track — much more visible against the white track.
        // The overlay sits ON TOP of the SeekBar (FrameLayout stacking
        // in dialog_recordings.xml) so it occludes the progress at
        // alert time positions, which is exactly what the user wants:
        // "alert happened here" stamped in red on the timeline.
        val barH = h
        // v1.5.15: minimum visible width for short alerts. Motion
        // detection events are often only 3-10 seconds long, which
        // on a 24-hour timeline at 720px maps to <0.1 pixels —
        // completely invisible. The previous code skipped anything
        // < 1px (the `continue` check), which meant ALL short alerts
        // were silently dropped, producing an empty overlay even
        // though logcat showed "7 ranges for camera 1". Now each
        // range is drawn with at least minW pixels so even a 5s
        // alert shows up as a visible thin red line on the timeline.
        val minW = 4f
        for ((startMs, endMs) in alertRanges) {
            val startRatio = (startMs.toFloat() / maxMs).coerceIn(0f, 1f)
            val endRatio = (endMs.toFloat() / maxMs).coerceIn(0f, 1f)
            val left = startRatio * width
            val right = endRatio * width
            // Expand sub-pixel ranges to minW so they're visible.
            // Center the expansion on the alert's midpoint.
            if (right - left < minW) {
                val center = (left + right) / 2f
                canvas.drawRect(center - minW / 2f, 0f, center + minW / 2f, barH, alertPaint)
            } else {
                canvas.drawRect(left, 0f, right, barH, alertPaint)
            }
        }
    }
}
