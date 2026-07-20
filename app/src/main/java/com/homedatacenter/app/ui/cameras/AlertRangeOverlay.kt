package com.homedatacenter.app.ui.cameras

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v1.5.12: an overlay that paints a red bar at specific time ranges
 * on top of a SeekBar (or any horizontal bar). The bar aligns with
 * the SeekBar's drawable area so the alert ranges visually overlap
 * the progress track.
 *
 * v1.6.3: gained an [aiIndices] parameter to paint AI-detected
 * ranges (peak_objects > 0) in a brighter red, plus a white tick
 * dot at each range's start.
 *
 * v1.6.4 REWRITE: this view is now interactive and replaces the
 * v1.6.3 HorizontalScrollView chip list. Each motion range is
 * drawn at its time-proportional X position across the view's width,
 * so the ENTIRE 24h is visible at once without scrolling. This
 * directly addresses the user's complaint "划chip还是太慢了，且不
 * 直观，可以再改进一下：两边密，中间疏的chip，使其可以全屏内
 * 显示完全一整天的" — by laying out ranges at their proportional
 * time positions, natural motion density (mornings + evenings
 * active, midday quiet) is immediately visible as "dense edges +
 * sparse middle" without any horizontal scrolling.
 *
 * Key changes from v1.6.3:
 *  - 4-tier intensity coloring (teal/amber/orange/red) instead of
 *    just "regular" vs "AI". Each [MotionRangeUi] carries a [tier]
 *    (0..3) computed by the caller from motion_score + peak_objects.
 *  - Touch handling: tapping a range invokes [onSeekToRange] with
 *    that range's start Ms. Touch slop is respected so a drag does
 *    not accidentally seek.
 *  - Background track: a dim rectangle fills the full width so the
 *    user can see the complete 24h extent even on a quiet day.
 *  - Standalone row: in v1.6.3 the overlay shared a FrameLayout
 *    with the SeekBar (red marks overlapped the seekbar track).
 *    v1.6.4 puts the overlay in its own row above the SeekBar so
 *    it can be taller (28dp) for easier tapping without blocking
 *    SeekBar drag gestures.
 *
 * The view is purely visual + tap-to-seek — it does NOT intercept
 * drags, so horizontal swipes within the overlay fall through to
 * the parent (which is fine since the overlay doesn't scroll).
 */
class AlertRangeOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * v1.6.4: per-range display model. Replaces the v1.6.0-v1.6.3
     * Pair<Long, Long> + aiIndices Set approach. Each range carries
     * its own [tier] (0..3) so the overlay can pick the right paint
     * without consulting a parallel list.
     *
     *  - tier 0 (LOW):   teal #4DB6AC    — minor motion (bottom 25% of max score)
     *  - tier 1 (MID):   amber #FFB300   — moderate motion (25-75%)
     *  - tier 2 (HIGH):  orange #FF7043   — strong motion (top 25%)
     *  - tier 3 (ALERT): red #EF5350      — AI detected (peak_objects > 0)
     *
     * [startMs] / [endMs] are in the same time scale as the SeekBar's
     * progress (0..[maxMs]).
     */
    data class MotionRangeUi(
        val startMs: Long,
        val endMs: Long,
        val tier: Int,
    )

    // v1.6.4: 4 intensity paints, one per tier. The previous code
    // had just two (regular red + AI brighter red); the new tiers
    // let the user visually skim a busy day: red chips are the most
    // important (something was detected), orange chips are "lots of
    // motion" (likely the user themselves walking by), teal chips
    // are minor (leaves blowing, light changes).
    private val tierPaints = listOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4DB6AC") // tier 0 LOW — teal
            isDither = true
        },
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFB300") // tier 1 MID — amber
            isDither = true
        },
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF7043") // tier 2 HIGH — orange
            isDither = true
        },
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EF5350") // tier 3 ALERT — red (AI detected)
            isDither = true
        },
    )
    // v1.6.4: dim background track showing the full 24h extent.
    // Helps the user see "this is the complete day timeline" even
    // on a quiet day with only 1-2 ranges.
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1AFFFFFF") // 10% white
        isDither = true
    }
    // v1.6.4: subtle separator lines between ranges so adjacent
    // ranges of the same tier don't look like one merged blob.
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000") // 20% black
        strokeWidth = 1f
    }

    private val ranges = mutableListOf<MotionRangeUi>()
    private var maxMs: Long = 1L

    // v1.6.4: touch state for tap-to-seek. We record the DOWN X and
    // only invoke the seek callback on UP if the finger hasn't moved
    // beyond [touchSlop] — this matches Android's standard "tap"
    // detection and lets the user scroll past the overlay without
    // accidentally seeking.
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * v1.6.4: tap-to-seek callback. Invoked with the startMs of the
     * tapped range. Set by [RecordingsDialog] to seek ExoPlayer to
     * the range's start via the existing clipStartOffsets binary
     * search path.
     */
    var onSeekToRange: ((Long) -> Unit)? = null

    /**
     * Sets the maximum value of the corresponding SeekBar. Used to
     * map time ranges to pixel positions. Must be called BEFORE
     * [setRanges] for the mapping to be correct.
     */
    fun setMax(max: Long) {
        maxMs = if (max <= 0) 1L else max
        invalidate()
    }

    /**
     * Sets the motion ranges to render. Each range carries its own
     * [MotionRangeUi.tier] for color selection. Pass an empty list
     * to clear the overlay.
     *
     * v1.6.4: replaces the v1.6.3 `setAlertRanges(ranges, aiIndices)`
     * API. The tier is now per-range so callers don't have to track
     * a parallel Set of AI indices.
     */
    fun setRanges(ranges: List<MotionRangeUi>) {
        this.ranges.clear()
        this.ranges.addAll(ranges)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                return true  // consume so we get the UP event
            }
            MotionEvent.ACTION_UP -> {
                val dx = abs(event.x - touchDownX)
                val dy = abs(event.y - touchDownY)
                // Only treat as a tap if the finger barely moved.
                // Larger movements are drags (scrolls) and should
                // fall through — but since the overlay is in its
                // own row above the SeekBar, the parent doesn't
                // scroll either way; we just don't want to seek on
                // an obvious drag.
                if (dx < touchSlop && dy < touchSlop) {
                    val idx = findRangeAtX(event.x)
                    if (idx >= 0) {
                        onSeekToRange?.invoke(ranges[idx].startMs)
                        performClick()
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Returns the index of the range whose drawn rect contains [x],
     * or -1 if no range covers that X. Expands each range's hit-rect
     * by [touchSlop] on both sides so the user can tap "near" a thin
     * range and still hit it — important because a 10s range on a
     * 24h timeline is only ~0.7px wide at 720px screen width.
     */
    private fun findRangeAtX(x: Float): Int {
        if (ranges.isEmpty() || width <= 0) return -1
        val w = width.toFloat()
        for ((i, r) in ranges.withIndex()) {
            val left = (r.startMs.toFloat() / maxMs) * w
            val right = (r.endMs.toFloat() / maxMs) * w
            // Expand sub-pixel ranges to a min hit width of touchSlop
            // so the user can actually tap a 10s range (which would
            // otherwise be ~0.7px wide on a 720px screen).
            val hitLeft: Float
            val hitRight: Float
            if (right - left < touchSlop) {
                val center = (left + right) / 2f
                hitLeft = center - touchSlop / 2f
                hitRight = center + touchSlop / 2f
            } else {
                hitLeft = left
                hitRight = right
            }
            if (x in hitLeft..hitRight) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        // v1.6.4: dim background track fills the full width so the
        // user sees the entire 24h extent even on a quiet day. The
        // track is drawn first so ranges paint on top.
        canvas.drawRect(0f, 0f, w, h, trackPaint)
        if (ranges.isEmpty() || maxMs <= 0L) return
        // v1.6.4: minimum visible width per range. 2dp at xhdpi is
        // ~4.5px — wide enough to be tappable when expanded by
        // touchSlop in findRangeAtX, narrow enough that a busy day
        // with ~180 ranges doesn't completely fill the bar.
        val minW = 2f * resources.displayMetrics.density
        for (r in ranges) {
            val left = (r.startMs.toFloat() / maxMs) * w
            val right = (r.endMs.toFloat() / maxMs) * w
            val paint = tierPaints.getOrElse(r.tier) { tierPaints[0] }
            // Expand sub-pixel ranges to minW centered on midpoint.
            val actualLeft: Float
            val actualRight: Float
            if (right - left < minW) {
                val center = (left + right) / 2f
                actualLeft = max(0f, center - minW / 2f)
                actualRight = min(w, center + minW / 2f)
            } else {
                actualLeft = left
                actualRight = right
            }
            canvas.drawRect(actualLeft, 0f, actualRight, h, paint)
            // Separator line on the right edge so adjacent same-tier
            // ranges don't blur into one big block.
            if (actualRight < w) {
                canvas.drawLine(actualRight, 0f, actualRight, h, separatorPaint)
            }
        }
    }
}
