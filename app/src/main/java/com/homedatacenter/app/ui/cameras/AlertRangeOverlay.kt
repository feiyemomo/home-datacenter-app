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
 *   - HIGH (orange): #FFB300 (v1.6.8: merged with MID — visually one yellow tier)
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
        // v1.6.8: merged with midTierPaint — both MID and HIGH render
        // as amber/yellow per user request "黄，橙色chip统一合并为黄色".
        // The HIGH tier is still tracked internally so backend merging
        // logic (curTier upgrade rules) keeps working, but visually
        // MID+HIGH are a single yellow tier.
        color = Color.parseColor("#FFB300") // amber (same as midTierPaint)
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
     *
     * v1.6.6 rev8: client-side consolidation. User said "位于中间的
     * 一些chip的时段并集大概标注在进度条上面". Previously every
     * individual range was painted as a separate marker, so a long
     * quiet stretch with 10+ LOW segments showed as 10+ small teal
     * ticks that visually merged into a smear. Now consecutive ranges
     * of the same tier within [consolidateGapMs] of each other are
     * merged into a single marker spanning the union of their time
     * ranges. The merge is purely visual — the underlying data isn't
     * touched.
     *
     * The gap (default 30s) is intentionally larger than the backend's
     * per-tier merge gap so that visually-adjacent ranges of the same
     * color collapse into one fat marker rather than many tiny ones.
     */
    fun setTieredRanges(ranges: List<TieredRange>) {
        alertRanges.clear()
        rangeIsAi.clear()
        rangeTiers.clear()
        // v1.6.6 rev8: client-side merge of same-tier consecutive ranges.
        // Two ranges are merged if (a) same tier, (b) gap between
        // prevEnd and curStart <= consolidateGapMs. Merged range takes
        // the union (min start, max end).
        val consolidated = consolidateRanges(ranges, consolidateGapMs)
        for (r in consolidated) {
            alertRanges.add(r.startMs to r.endMs)
            rangeIsAi.add(r.tier == TIER_ALERT)
            rangeTiers.add(r.tier)
        }
        invalidate()
    }

    /**
     * v1.6.6 rev8: merges consecutive same-tier ranges whose gap ≤
     * [gapMs]. Returns a new list where each run of same-tier ranges
     * within the gap threshold is collapsed into a single range
     * spanning the union (min start, max end). Tier is preserved.
     *
     * Examples:
     *   - Two LOW ranges 5s apart (gap 5s ≤ 30s): merge into one LOW
     *     range from min(start) to max(end).
     *   - A LOW then ALERT then LOW: not merged (different tiers).
     *   - Two HIGH ranges 60s apart: not merged (gap 60s > 30s).
     */
    private fun consolidateRanges(
        ranges: List<TieredRange>,
        gapMs: Long,
    ): List<TieredRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.startMs }
        val out = mutableListOf<TieredRange>()
        var curStart = sorted[0].startMs
        var curEnd = sorted[0].endMs
        var curTier = sorted[0].tier
        for (i in 1 until sorted.size) {
            val r = sorted[i]
            val gap = r.startMs - curEnd
            if (r.tier == curTier && gap <= gapMs) {
                // Extend current range.
                if (r.endMs > curEnd) curEnd = r.endMs
                if (r.startMs < curStart) curStart = r.startMs
            } else {
                out.add(TieredRange(curStart, curEnd, curTier))
                curStart = r.startMs
                curEnd = r.endMs
                curTier = r.tier
            }
        }
        out.add(TieredRange(curStart, curEnd, curTier))
        return out
    }

    companion object {
        const val TIER_LEGACY = -1
        const val TIER_LOW = 0
        const val TIER_MID = 1
        const val TIER_HIGH = 2
        const val TIER_ALERT = 3
        // v1.6.6 rev8: client-side consolidation gap. Ranges of the
        // same tier within this many ms of each other are merged into
        // one marker. 30s is larger than the backend's per-tier gaps
        // (LOW 180s, MID 15s) — but since the backend already merged
        // within-tier ranges, this is mostly for visual cleanup of
        // edge cases where two ranges of the same tier happen to be
        // close but not adjacent in the backend's merge window.
        private const val consolidateGapMs = 30_000L
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
