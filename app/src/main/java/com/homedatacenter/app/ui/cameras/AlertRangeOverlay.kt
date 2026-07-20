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
 * v1.6.5 rev7: extended to support per-range tier coloring. The user
 * asked: "把展示出来的chip区间大概标注再进度条上面吧". Previously
 * the overlay only painted ALERT ranges (red) — now it accepts a
 * tier per range (LOW/MID/HIGH/ALERT) and paints each in the chip's
 * color so the SeekBar's range markers visually match the chips
 * shown in the fisheye scroller below.
 *
 * Tier colors (matching bg_chip_motion_*.xml):
 *   - LOW (teal):    #4DB6AC
 *   - MID (amber):   #FFB300
 *   - HIGH (orange): #FF7043
 *   - ALERT (red):   #EF5350
 *
 * Usage:
 *  1. Place [AlertRangeOverlay] ABOVE the [android.widget.SeekBar]
 *     in a FrameLayout.
 *  2. Call [setMax] with the same max as the SeekBar.
 *  3. Call [setAlertRanges] with the ranges + aiIndices (legacy) OR
 *     [setTieredRanges] with explicit per-range tier to get the
 *     tier-colored rendering.
 */
class AlertRangeOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // Legacy paints — kept for backward compat with callers that
    // use the original [setAlertRanges] (no tier info).
    private val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935") // Material Red 600
        strokeWidth = 0f
        isDither = true
    }
    private val aiAlertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        strokeWidth = 0f
        isDither = true
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        strokeWidth = 1f
        isDither = true
    }

    // v1.6.5 rev7: per-tier paints matching the chip backgrounds.
    private val lowTierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DB6AC") // teal
        strokeWidth = 0f
        isDither = true
    }
    private val midTierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300") // amber
        strokeWidth = 0f
        isDither = true
    }
    private val highTierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7043") // orange
        strokeWidth = 0f
        isDither = true
    }
    private val alertTierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF5350") // red
        strokeWidth = 0f
        isDither = true
    }

    private val alertRanges = mutableListOf<Pair<Long, Long>>()
    private val rangeIsAi = mutableListOf<Boolean>()
    // v1.6.5 rev7: per-range tier index, parallel to alertRanges.
    // -1 = legacy (use alertPaint/aiAlertPaint based on rangeIsAi);
    // 0/1/2/3 = LOW/MID/HIGH/ALERT.
    private val rangeTiers = mutableListOf<Int>()
    private var maxMs: Long = 1L

    /** Tier constants exposed for callers. */
    companion object {
        const val TIER_LEGACY = -1
        const val TIER_LOW = 0
        const val TIER_MID = 1
        const val TIER_HIGH = 2
        const val TIER_ALERT = 3
    }

    fun setMax(max: Long) {
        maxMs = if (max <= 0) 1L else max
        invalidate()
    }

    /**
     * Legacy setter — paints all ranges in red (or brighter red
     * for AI-detected). Kept for backward compat; new callers
     * should use [setTieredRanges].
     */
    fun setAlertRanges(ranges: List<Pair<Long, Long>>, aiIndices: Set<Int> = emptySet()) {
        alertRanges.clear()
        alertRanges.addAll(ranges)
        rangeIsAi.clear()
        rangeTiers.clear()
        for (i in ranges.indices) {
            rangeIsAi.add(i in aiIndices)
            rangeTiers.add(TIER_LEGACY)
        }
        invalidate()
    }

    /**
     * v1.6.5 rev7: sets the alert time ranges with explicit per-range
     * tier. Each [TieredRange] carries (startMs, endMs, tier).
     * The overlay paints each range in its tier color so the SeekBar
     * matches the fisheye chip scroller visually.
     */
    fun setTieredRanges(ranges: List<TieredRange>) {
        alertRanges.clear()
        rangeIsAi.clear()
        rangeTiers.clear()
        for (r in ranges) {
            alertRanges.add(r.startMs to r.endMs)
            rangeIsAi.add(r.tier == TIER_ALERT)
            rangeTiers.add(r.tier)
        }
        invalidate()
    }

    /** Convenience holder for tiered range data. */
    data class TieredRange(
        val startMs: Long,
        val endMs: Long,
        val tier: Int,
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (alertRanges.isEmpty() || width <= 0 || height <= 0) return
        val h = height.toFloat()
        val barH = h
        val minW = 1f
        for ((i, pair) in alertRanges.withIndex()) {
            val (startMs, endMs) = pair
            val startRatio = (startMs.toFloat() / maxMs).coerceIn(0f, 1f)
            val endRatio = (endMs.toFloat() / maxMs).coerceIn(0f, 1f)
            val left = startRatio * width
            val right = endRatio * width
            val tier = if (i < rangeTiers.size) rangeTiers[i] else TIER_LEGACY
            val paint = when (tier) {
                TIER_LOW -> lowTierPaint
                TIER_MID -> midTierPaint
                TIER_HIGH -> highTierPaint
                TIER_ALERT -> alertTierPaint
                else -> if (i < rangeIsAi.size && rangeIsAi[i]) aiAlertPaint else alertPaint
            }
            if (right - left < minW) {
                val center = (left + right) / 2f
                canvas.drawRect(center - minW / 2f, 0f, center + minW / 2f, barH, paint)
            } else {
                canvas.drawRect(left, 0f, right, barH, paint)
            }
            canvas.drawCircle(left, barH / 2f, 0.8f, tickPaint)
        }
    }
}
