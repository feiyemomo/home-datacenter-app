package com.homedatacenter.app.ui.cameras

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * v1.6.4: a HorizontalScrollView that applies a "fisheye" scale
 * transform to its children on scroll. Each child is scaled along
 * X and Y based on its distance from the ScrollView's horizontal
 * center: children near the center get scale = 1.0 (full size),
 * children near the edges get scale = [minScale] (e.g. 0.55).
 *
 * This directly addresses the user's request: "在类似与原有chip的
 * 基础上，越靠近两边的chip越小，靠近中间的类似于原有的chip，
 * 整个chip在滑动中不断变化". With this scroller, a chip "grows"
 * as it scrolls into the center and "shrinks" as it scrolls out —
 * giving the user a clear focal point while still keeping the full
 * 24h of motion events accessible via horizontal scroll.
 *
 * Implementation notes:
 *  - We override [onScrollChanged] (fires on every frame during a
 *    scroll/fling) and [onLayout] (fires after children are added/
 *    removed via [populateChips]) to re-apply scales.
 *  - We walk the direct children of the inner LinearLayout (the
 *    chip container). Each child must have pivotX=0.5 (the default
 *    for TextViews) so scaling shrinks toward the chip's center.
 *  - Scale is computed via a smooth falloff: chips within
 *    [fullScaleBand] of the center get scale 1.0, chips farther
 *    than [fullScaleBand] decay linearly toward [minScale] at the
 *    edge of the viewport.
 *  - The transform is GPU-cheap (just scaleX/scaleY on a TextView),
 *    so this is fine to run on every scroll frame even with 200 chips.
 *
 * Usage: caller populates chips into [binding.motionChipContainer]
 * (a LinearLayout inside this scroller, same as v1.6.3), then calls
 * [requestLayout] once to trigger the initial scale pass.
 */
class FisheyeChipScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    /**
     * The minimum scale applied to chips at the edge of the
     * viewport. 0.55 = roughly half size; small enough that edge
     * chips clearly look "compressed" but still readable enough
     * to recognize the time. Setting below 0.5 makes the time
     * label illegible at the edges.
     */
    private val minScale = 0.55f

    /**
     * Half-width (in px) of the "full scale" band around the
     * viewport center. Chips within this band get scale 1.0;
     * chips outside decay linearly toward [minScale]. 96px ≈
     * ~1 chip-width, so the chip currently in the center + its
     * immediate neighbors stay full-size, then decay kicks in.
     */
    private val fullScaleBandPx = 96

    private val container: LinearLayout?
        get() = getChildAt(0) as? LinearLayout

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        applyFisheyeScales()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // After children are laid out (e.g. populateChips added new
        // views), re-apply scales so the initial visible state is
        // correct without requiring the user to scroll first.
        applyFisheyeScales()
    }

    /**
     * Walks each chip in the container and sets its scaleX/scaleY
     * based on its distance from the viewport center. Called from
     * [onScrollChanged] and [onLayout].
     *
     * Scale formula:
     *  - dx = abs(chipCenterX - viewportCenterX)
     *  - if dx <= fullScaleBandPx: scale = 1.0
     *  - else: scale = lerp(1.0, minScale, (dx - band) / (viewportHalfWidth - band))
     *    clamped to [minScale, 1.0]
     *
     * The lerp target is `viewportHalfWidth - band` so a chip at
     * the very edge of the viewport reaches exactly [minScale].
     */
    private fun applyFisheyeScales() {
        val container = container ?: return
        val viewportCenterX = scrollX + width / 2
        val viewportHalfWidth = (width / 2).coerceAtLeast(1)
        // v1.6.4: decay range = from the edge of the full-scale band
        // to the edge of the viewport. Below this we're at full scale,
        // beyond it we're at minScale.
        val decayRange = (viewportHalfWidth - fullScaleBandPx).coerceAtLeast(1)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            val chipCenterX = (child.left + child.right) / 2
            val dx = Math.abs(chipCenterX - viewportCenterX)
            val scale = if (dx <= fullScaleBandPx) {
                1.0f
            } else {
                val t = ((dx - fullScaleBandPx).toFloat() / decayRange).coerceIn(0f, 1f)
                lerp(1.0f, minScale, t)
            }
            // Only set if changed — avoids triggering a layout pass
            // on a chip whose scale is already correct (most chips
            // on every scroll frame).
            if (child.scaleX != scale) {
                child.scaleX = scale
                child.scaleY = scale
            }
            // Dim edge chips slightly for visual depth (alpha 0.5 at
            // the edge). This makes the focal point stand out more
            // without sacrificing legibility since edge chips are
            // "preview" thumbnails the user can scroll to focus on.
            val alpha = if (dx <= fullScaleBandPx) {
                1.0f
            } else {
                val t = ((dx - fullScaleBandPx).toFloat() / decayRange).coerceIn(0f, 1f)
                lerp(1.0f, 0.5f, t)
            }
            if (child.alpha != alpha) {
                child.alpha = alpha
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
