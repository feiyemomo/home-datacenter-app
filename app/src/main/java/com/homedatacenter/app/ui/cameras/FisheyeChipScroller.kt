package com.homedatacenter.app.ui.cameras

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * v1.6.5 rev7: simplified fisheye transform.
 *
 * User reported "点击相应chip之后chip跳乱滚". Root cause: the rev6
 * implementation toggled layoutParams.width between WRAP_CONTENT and
 * thinBarWidthPx (3dp) based on the chip's scale. That triggered
 * requestLayout() on every chip per scroll frame, and each layout
 * pass re-fired onLayout → applyFisheyeScales → more requestLayout
 * calls. The result was layout thrash that produced visible "jumping"
 * whenever scroll position or chip visibility changed.
 *
 * Fix: pure visual transform. Each chip's layoutParams.width stays
 * at WRAP_CONTENT for its entire lifetime — no requestLayout() calls
 * during scroll. Only scaleX / scaleY / text content change.
 *
 * The "thin bar" effect at the edges is achieved by scaleX decaying
 * to [minScale] (0.4); the chip's measured width stays the same but
 * its visual width shrinks to 40%. Empty text + small scaleX gives
 * the same "colored tick" look as before without the layout thrash.
 *
 * Edge padding (rev7 fix for "两头的chip不能完全拉出来"):
 * the container's horizontal padding is set to half the scroller's
 * width so that the first and last chips can be scrolled to the
 * viewport center. Without this padding, scrollTo on the first chip
 * only scrolls to scrollX=0 (left edge), leaving the chip in the
 * top-left rather than the center.
 */
class FisheyeChipScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    /** Minimum scale at the viewport edge. 0.4 = 40% of full size. */
    private val minScale = 0.4f

    /**
     * Scale threshold below which chip text is hidden (chip becomes
     * a "colored tick"). 0.65 means chips near the edge (scale 0.4-0.65)
     * lose their text — the user said "靠边的就不用带字了".
     */
    private val textThreshold = 0.65f

    /**
     * Half-width (in px) of the "full scale" band around the viewport
     * center. Chips within this band get scale=1.0 (full size, label
     * visible). Beyond this band, scale decays linearly toward
     * [minScale] at the viewport edge.
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
        // views, or the scroller was resized), re-apply scales + set
        // container edge padding so the first/last chips can reach
        // the viewport center.
        updateContainerEdgePadding()
        applyFisheyeScales()
    }

    /**
     * v1.6.5 rev7: sets the container's horizontal padding to half
     * the scroller's width so the first and last chips can be
     * scrolled to the viewport center. Without this, the first
     * chip's left edge can only reach the scroller's left edge (not
     * the center) — the user said "两头的chip不能完全拉出来".
     *
     * We use paddingLeft + paddingRight instead of a single
     * paddingStart so RTL layouts also get the symmetric padding.
     * clipToPadding is set to false so chips remain visible while
     * scrolling through the padding zone.
     */
    private fun updateContainerEdgePadding() {
        val container = container ?: return
        val halfViewport = (width / 2).coerceAtLeast(0)
        if (halfViewport == 0) return
        // Only update if the value changed to avoid requestLayout()
        // storms when this is called from onLayout.
        if (container.paddingLeft != halfViewport ||
            container.paddingRight != halfViewport) {
            container.setPadding(halfViewport, 0, halfViewport, 0)
            // clipToPadding=false lets chips render in the padding
            // zone while the user scrolls past the first/last chip —
            // important so the centered chip isn't clipped at the
            // edges.
            clipToPadding = false
        }
    }

    /**
     * Walks each chip in the container and sets its scaleX/scaleY
     * (and text) based on its distance from the viewport center.
     * Pure visual transform — no requestLayout() calls (rev7 fix).
     */
    private fun applyFisheyeScales() {
        val container = container ?: return
        val viewportCenterX = scrollX + width / 2
        val viewportHalfWidth = (width / 2).coerceAtLeast(1)
        val decayRange = (viewportHalfWidth - fullScaleBandPx).coerceAtLeast(1)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            val chipCenterX = (child.left + child.right) / 2
            val dx = abs(chipCenterX - viewportCenterX)
            val scale: Float = if (dx <= fullScaleBandPx) {
                1.0f
            } else {
                val t = ((dx - fullScaleBandPx).toFloat() / decayRange).coerceIn(0f, 1f)
                lerp(1.0f, minScale, t)
            }
            // Collapsed: hide text. Expanded: restore text.
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
            // v1.6.5 rev7: pure visual transform. No width forcing,
            // no requestLayout() — just scale + alpha. The chip's
            // measured width stays WRAP_CONTENT for its entire life;
            // the "thin tick" look at the edges comes from scaleX
            // decaying to minScale.
            if (child.scaleX != scale) {
                child.scaleX = scale
                child.scaleY = scale
            }
            if (child.alpha != 1.0f) child.alpha = 1.0f
        }
    }

    /**
     * Scrolls the scroller so that the chip at [centerChildIndex]
     * is centered in the viewport. Called after [populateMotionChips]
     * to give the user an "you are here" initial focal point —
     * the chip closest to the current ExoPlayer position is centered,
     * making it obvious which moment is currently playing.
     *
     * v1.6.5 rev7: uses smoothScrollTo (200ms) instead of scrollTo
     * + post{} to avoid the "跳乱滚" the user reported. The smooth
     * animation gives the user visual continuity — they see the
     * chips sliding into focus rather than a hard jump.
     */
    fun scrollToCenterChip(centerChildIndex: Int) {
        val container = container ?: return
        if (centerChildIndex !in 0 until container.childCount) return
        val target = container.getChildAt(centerChildIndex)
        post {
            val targetCenter = target.left + target.width / 2
            val viewportCenter = width / 2
            val targetScroll = (targetCenter - viewportCenter).coerceAtLeast(0)
            smoothScrollTo(targetScroll, 0)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
