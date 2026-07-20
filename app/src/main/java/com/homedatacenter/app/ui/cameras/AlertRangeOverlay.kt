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
    // v1.6.3: brighter orange for AI-detected ranges (PeakObjects > 0).
    // Drawn at a slightly higher opacity so the user can distinguish
    // "pure motion" from "AI tracked something" at a glance.
    private val aiAlertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252") // Material Red A200 — brighter
        strokeWidth = 0f
        isDither = true
    }
    // v1.6.3: thin tick mark drawn ABOVE each range's start position.
    // Helps the user see exactly where a motion event begins, since
    // the bar itself may be only 1-2px wide and hard to read.
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        strokeWidth = 1f  // 1px tick
        isDither = true
    }

    private val alertRanges = mutableListOf<Pair<Long, Long>>()
    // v1.6.3: per-range "is AI detected" flag. Indices match alertRanges.
    // When true the range is painted with [aiAlertPaint] (brighter red)
    // instead of the regular [alertPaint]. Populated by setAlertRanges.
    private val rangeIsAi = mutableListOf<Boolean>()
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
     *
     * v1.6.3: the new [aiIndices] parameter is a set of indices
     * (into the [ranges] list) that should be painted as AI-detected
     * (brighter red). Used to distinguish "pure motion" from "AI
     * tracked something" at a glance.
     */
    fun setAlertRanges(ranges: List<Pair<Long, Long>>, aiIndices: Set<Int> = emptySet()) {
        alertRanges.clear()
        alertRanges.addAll(ranges)
        rangeIsAi.clear()
        for (i in ranges.indices) {
            rangeIsAi.add(i in aiIndices)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (alertRanges.isEmpty() || width <= 0 || height <= 0) return
        val h = height.toFloat()
        val barH = h
        // v1.6.3: minW 2 -> 1. v1.6.2 used 2px which on a busy day
        // (~180 ranges) produced a "thick red wall". With the v1.6.3
        // backend's 2s merge threshold the count is similar but now
        // chips carry the readable info; the overlay is just a
        // visual anchor showing "this is where motion happened on
        // the 24h timeline". 1px is the thinnest visible line on
        // Android — fits the new role.
        val minW = 1f
        for ((i, pair) in alertRanges.withIndex()) {
            val (startMs, endMs) = pair
            val startRatio = (startMs.toFloat() / maxMs).coerceIn(0f, 1f)
            val endRatio = (endMs.toFloat() / maxMs).coerceIn(0f, 1f)
            val left = startRatio * width
            val right = endRatio * width
            val paint = if (i < rangeIsAi.size && rangeIsAi[i]) aiAlertPaint else alertPaint
            // Expand sub-pixel ranges to minW so they're visible.
            // Center the expansion on the alert's midpoint.
            if (right - left < minW) {
                val center = (left + right) / 2f
                canvas.drawRect(center - minW / 2f, 0f, center + minW / 2f, barH, paint)
            } else {
                canvas.drawRect(left, 0f, right, barH, paint)
            }
            // v1.6.3: tick mark at the start of each range — a small
            // white dot at the top of the overlay helps the user see
            // "this is where the motion STARTED" even when the range
            // itself is too thin to read.
            canvas.drawCircle(left, barH / 2f, 0.8f, tickPaint)
        }
    }
}
