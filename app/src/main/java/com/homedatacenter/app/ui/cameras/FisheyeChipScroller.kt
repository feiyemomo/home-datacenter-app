package com.homedatacenter.app.ui.cameras

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * v1.6.4 rev5: a HorizontalScrollView that applies a "fisheye" scale
 * transform to its children on scroll. Each chip is scaled along X
 * and Y based on its distance from the ScrollView's horizontal center:
 * chips at the center get scale = 1.0 (full size, with label),
 * chips at the edges get scale = minScale (compressed, no label).
 *
 * This is the user's clarified request after rev4: "这个chip滑动不了，
 * 我想要滑动的时候，中间大的chip跟着替换" — the user wants scrollable
 * chips with a fisheye effect where the currently-centered chip is
 * large (showing the time label) and chips scroll INTO the center
 * (growing) and OUT to the edges (shrinking to thin colored bars).
 *
 * rev4 was fit-to-screen (non-scrolling), which caused "中间部分的
 * chip 仍然太密集了" because all N chips were forced into the viewport
 * width. rev5 returns to HorizontalScrollView but keeps rev4's
 * "edge chips collapse to thin colored bars" feature via the
 * [textThreshold] mechanism.
 *
 * Implementation:
 *  - Subclass HorizontalScrollView (rev4 was a ViewGroup; rev5 returns
 *    to scrolling).
 *  - Override [onScrollChanged] (fires on every scroll frame) and
 *    [onLayout] (fires after children are added/removed) to apply
 *    fisheye scales to each chip.
 *  - For each chip:
 *      dx = abs(chipCenterX - viewportCenterX)
 *      distNorm = (dx / viewportHalfWidth).coerceIn(0, 1)
 *      scale = lerp(1.0, minScale, distNorm)
 *  - If scale < [textThreshold]: clear text + force width to
 *    [thinBarWidthDp] (chip becomes a colored tick).
 *  - Else: restore text from tag + use measured width (chip shows
 *    "HH:mm" label at full scale).
 *  - alpha stays 1.0 (no dimming — user said "而不是隐藏").
 *
 * Initial scroll position: when [scrollToInitialCenter] is called
 * (typically from [RecordingsDialog.playDayAsPlaylist] after
 * populating chips), we scroll so that the chip nearest to the
 * current playback position is centered. This gives the user an
 * immediate "you are here" focal point.
 */
class FisheyeChipScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    /**
     * The minimum scale applied to chips at the edge of the viewport.
     * 0.4 = edge chips are 40% of full size — small enough to clearly
     * look "compressed" but large enough to remain visible as a
     * colored marker (alpha stays 1.0).
     */
    private val minScale = 0.4f

    /**
     * v1.6.4 rev5: scale threshold below which chip text is hidden
     * and the chip collapses to a thin colored bar. When scale < 0.65:
     *  - text is cleared (chip's tag retains the label for restoration)
     *  - chip's layoutParams.width is forced to [thinBarWidthDp] px
     *  - alpha stays 1.0 (still visible as a colored marker)
     * This gives the "edge chips compress to thin lines" effect
     * the user wanted.
     */
    private val textThreshold = 0.65f

    /**
     * v1.6.4 rev5: width (in dp) of a chip when collapsed to thin
     * bar mode (scale < [textThreshold]).
     */
    private val thinBarWidthDp = 3f

    /**
     * v1.6.4 rev5: half-width (in px) of the "full scale" band
     * around the viewport center. Chips within this band get
     * scale=1.0 (full size, label visible). Beyond this band,
     * scale decays linearly toward [minScale] at the viewport edge.
     *
     * 80px ≈ 1 chip-width (item_motion_chip.xml wrap_content with
     * 9sp "HH:mm" + 4dp/2dp padding ≈ 60-80px). This means the
     * chip currently in the center + its immediate neighbors stay
     * full-size, then decay kicks in for the rest.
     */
    private val fullScaleBandPx = 80

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
     * (and text/width) based on its distance from the viewport center.
     */
    private fun applyFisheyeScales() {
        val container = container ?: return
        val density = resources.displayMetrics.density
        val thinBarWidthPx = (thinBarWidthDp * density).toInt()
        val viewportCenterX = scrollX + width / 2
        val viewportHalfWidth = (width / 2).coerceAtLeast(1)
        // Decay range: from the edge of the full-scale band to the
        // viewport edge. Within band: scale 1.0. Beyond band: linear
        // decay to minScale.
        val decayRange = (viewportHalfWidth - fullScaleBandPx).coerceAtLeast(1)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            val chipCenterX = (child.left + child.right) / 2
            val dx = abs(chipCenterX - viewportCenterX)
            val scale: Float
            if (dx <= fullScaleBandPx) {
                scale = 1.0f
            } else {
                val t = ((dx - fullScaleBandPx).toFloat() / decayRange).coerceIn(0f, 1f)
                scale = lerp(1.0f, minScale, t)
            }
            // Collapsed: hide text + force thin width.
            // Expanded: restore text + let width be wrap_content.
            val collapsed = scale < textThreshold
            if (child is TextView) {
                val label = child.tag as? String ?: ""
                val currentText = child.text?.toString() ?: ""
                if (collapsed && currentText.isNotEmpty()) {
                    child.text = ""
                } else if (!collapsed && currentText != label) {
                    child.text = label
                }
            }
            // Force the chip's layout width to thinBarWidthPx when
            // collapsed, or WRAP_CONTENT when expanded. We use the
            // LayoutParams.width setter + requestLayout() only when
            // the value changes — avoids unnecessary layout passes.
            val params = child.layoutParams
            val targetWidth = if (collapsed) thinBarWidthPx else
                LinearLayout.LayoutParams.WRAP_CONTENT
            if (params.width != targetWidth) {
                params.width = targetWidth
                child.layoutParams = params
            }
            // Apply scale transform. For collapsed chips, we skip
            // scaling (they're already narrow via targetWidth) so
            // they don't disappear to sub-pixel. Expanded chips get
            // the full fisheye scale.
            val actualScale = if (collapsed) 1.0f else scale
            if (child.scaleX != actualScale) {
                child.scaleX = actualScale
                child.scaleY = actualScale
            }
            // v1.6.4 rev5: alpha stays 1.0 (no dimming). User
            // explicitly said "而不是隐藏".
            if (child.alpha != 1.0f) child.alpha = 1.0f
        }
    }

    /**
     * Scrolls the scroller so that the chip at [centerChildIndex]
     * is centered in the viewport. Called after [populateMotionChips]
     * to give the user an "you are here" initial focal point —
     * the chip closest to the current ExoPlayer position is centered,
     * making it obvious which moment is currently playing.
     */
    fun scrollToCenterChip(centerChildIndex: Int) {
        val container = container ?: return
        if (centerChildIndex !in 0 until container.childCount) return
        val target = container.getChildAt(centerChildIndex)
        post {
            val targetCenter = target.left + target.width / 2
            val viewportCenter = width / 2
            scrollTo(targetCenter - viewportCenter, 0)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
