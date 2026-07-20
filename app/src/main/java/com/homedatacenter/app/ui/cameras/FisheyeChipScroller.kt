package com.homedatacenter.app.ui.cameras

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * v1.6.4 (rev2): a non-scrolling ViewGroup that lays out its children
 * in a horizontal "fisheye" layout — all children are visible on
 * screen at once, with edge chips shrunk via [scaleX]/[scaleY] for a
 * "compression" effect, but never hidden.
 *
 * The user explicitly asked: "我想要chip靠近两边时的挤压效果，越靠
 * 近边上，体积越小，而不是隐藏；同时我要能够看到一整天的chip那种".
 *
 * This is a FrameLayout subclass (not HorizontalScrollView) so there
 * is no scrolling at all — the entire 24h of motion chips is always
 * visible. Each chip is centered in its own equal-width slot
 * (viewportWidth / N), then a scale transform is applied based on
 * distance from the viewport center.
 *
 * Scale formula:
 *  - distNorm = abs((i + 0.5) / N - 0.5) * 2  // 0 at center, 1 at edge
 *  - scale = lerp(1.0, minScale, distNorm)     // 1.0 center, 0.45 edge
 *
 * v1.6.3 alpha (edge chip dimmed) is REMOVED — alpha stays 1.0 so
 * edge chips remain fully visible (just smaller). The "compression"
 * is purely geometric (scale), not opacity.
 *
 * Children are added by [RecordingsDialog.populateMotionChips] the
 * same way as before — they're TextViews inflated from
 * [R.layout.item_motion_chip]. The container expects its children
 * to be laid out directly inside it (no intermediate LinearLayout
 * wrapper), but for backwards-compat with v1.6.3's XML which wraps
 * chips in a `motionChipContainer` LinearLayout, we detect the
 * wrapper and operate on its children.
 *
 * Touch handling: children's hit-rect is the post-scale position
 * (since we use [View.scaleX] which is a render transform, not a
 * layout transform). Taps land on the chip's actual drawn region.
 * Edge chips with scale 0.45 are still tappable — the hit area is
 * 45% × 45% of the original, which for a 50dp-wide chip is ~22dp
 * × 14dp — still above the 16dp minimum recommended touch target
 * (with a 6dp slop).
 */
class FisheyeChipScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /**
     * The minimum scale applied to chips at the extreme edges of the
     * viewport. v1.6.4 rev3: bumped from 0.45 to 0.6 — at 0.45 the
     * edge chips' 9sp text became unreadable (~4px effective), and
     * the user said "他们现在连成一片了" because chips at 0.45 scale
     * + 4dp padding were too small to show visual gaps. At 0.6 the
     * edge chips are still clearly "compressed" (60% of full size)
     * but readable, and the tighter item_motion_chip.xml padding
     * (4dp/2dp v.s. v1.6.3's 10dp/5dp) leaves visible inter-chip
     * gaps even at scale 0.6.
     */
    private val minScale = 0.6f

    /**
     * The actual child container — v1.6.3 used a LinearLayout named
     * `motionChipContainer` as the only child of the scroller, and
     * chips were added to that LinearLayout. We keep that layout
     * in the XML (no XML change needed) but treat the LinearLayout's
     * children as our chip list. If there's no wrapper (chips added
     * directly), we fall back to our own children.
     */
    private val chipContainer: LinearLayout?
        get() = (0 until childCount).map { getChildAt(it) }
            .firstOrNull { it is LinearLayout } as? LinearLayout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Measure ourselves to the parent's suggested size.
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val resolvedWidth = when (widthMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> widthSize
            else -> suggestedMinimumWidth
        }
        val resolvedHeight = when (heightMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> heightSize
            else -> suggestedMinimumHeight
        }

        // Measure each chip with UNRESTRICTED width so they report
        // their natural wrap_content size — we apply the fisheye
        // scale visually but layout each chip in its slot.
        val container = chipContainer
        if (container != null) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                measureChildWithMargins(
                    child,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    0,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    0,
                )
            }
        }
        setMeasuredDimension(resolvedWidth, resolvedHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val container = chipContainer ?: return
        val n = container.childCount
        if (n == 0) return
        val w = right - left
        val h = bottom - top
        // Equal-width slot for each chip — all N chips fit in the
        // viewport width. This is the key "see the whole day at once"
        // behavior: no scrolling needed.
        val slotWidth = w.toFloat() / n
        // Layout the wrapper LinearLayout to fill us so its children
        // can be positioned within our coordinate space.
        container.layout(0, 0, w, h)
        for (i in 0 until n) {
            val child = container.getChildAt(i)
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            // Chip center X within viewport: each slot is slotWidth
            // wide; this chip's center is at (i + 0.5) * slotWidth.
            val centerX = (i + 0.5f) * slotWidth
            // Distance from viewport center, normalized to [0, 1]:
            // 0 at exact center chip, 1 at extreme edge chip.
            val distNorm = abs((i + 0.5f) / n - 0.5f) * 2f
            // Fisheye scale: 1.0 at center, minScale at edge.
            // Linear interpolation keeps the gradient predictable
            // (no sudden drop-off the user might find jarring).
            val scale = lerp(1.0f, minScale, distNorm)
            // Pivot center for scale — chip shrinks toward its own
            // center, not toward viewport center. This makes the
            // compression look like "the chip itself is smaller",
            // not "the chip is being pushed off-screen".
            child.pivotX = cw / 2f
            child.pivotY = ch / 2f
            child.scaleX = scale
            child.scaleY = scale
            // v1.6.4 rev2: alpha stays 1.0. v1.6.4 rev1 dimmed edge
            // chips to 0.5 which made them look "hidden" — the user
            // explicitly said "而不是隐藏". Keeping alpha at 1.0
            // means edge chips are just smaller, not faded.
            child.alpha = 1.0f
            // Center the chip in its slot horizontally, and vertically
            // center it within the container.
            val chipLeft = (centerX - cw / 2f).toInt()
            val chipTop = (h - ch) / 2
            child.layout(chipLeft, chipTop, chipLeft + cw, chipTop + ch)
        }
    }

    /**
     * Re-applies fisheye scales without triggering a full layout pass.
     * Useful when the chip count hasn't changed but we want to
     * refresh scales (e.g. after the host rotates and viewport
     * dimensions change). Currently unused — onLayout already runs
     * on rotation — but kept as a public hook for future callers.
     */
    fun refreshScales() {
        requestLayout()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    // LayoutParams required for measureChildWithMargins to work
    // without crashing when the child has no LayoutParams set yet.
    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(2, 2)
    }

    override fun checkLayoutParams(p: LayoutParams): Boolean {
        return p is MarginLayoutParams
    }

    override fun generateLayoutParams(p: LayoutParams): LayoutParams {
        return MarginLayoutParams(p)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(2, 2)
    }
}
