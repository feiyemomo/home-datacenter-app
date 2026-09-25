package com.homedatacenter.app.ui.cameras

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import org.webrtc.SurfaceViewRenderer

/**
 * SafeSurfaceViewRenderer wraps org.webrtc.SurfaceViewRenderer to prevent
 * Integer.MAX_VALUE height measurements when embedded in a ScrollView during
 * Picture-in-Picture mode.
 *
 * WebRTC's VideoLayoutMeasure uses View.getDefaultSize(Integer.MAX_VALUE, heightSpec).
 * In an UNSPECIFIED height measure pass (such as inside a ScrollView), this causes
 * a measured height of 2147483647 which crashes Android's SurfaceView with:
 * "SurfaceView width and height must be smaller than 16384", resulting in
 * surface destruction and a black screen.
 *
 * SafeSurfaceViewRenderer intercepts unbounded heights and clamps them to a valid 16:9 ratio.
 */
class SafeSurfaceViewRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceViewRenderer(context, attrs) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val wMode = MeasureSpec.getMode(widthSpec)
        val wSize = MeasureSpec.getSize(widthSpec)
        val hMode = MeasureSpec.getMode(heightSpec)
        val hSize = MeasureSpec.getSize(heightSpec)

        val safeWidthSpec = if (wMode == MeasureSpec.UNSPECIFIED || wSize >= 16384 || wSize <= 0) {
            val w = resources.displayMetrics.widthPixels.coerceIn(320, 3840)
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        } else {
            widthSpec
        }

        val safeHeightSpec = if (hMode == MeasureSpec.UNSPECIFIED || hSize >= 16384 || hSize <= 0) {
            val w = MeasureSpec.getSize(safeWidthSpec)
            val computedH = (w * 9) / 16
            MeasureSpec.makeMeasureSpec(computedH, MeasureSpec.EXACTLY)
        } else {
            heightSpec
        }
        super.onMeasure(safeWidthSpec, safeHeightSpec)
    }
}
