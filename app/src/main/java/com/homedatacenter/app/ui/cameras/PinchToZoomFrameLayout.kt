package com.homedatacenter.app.ui.cameras

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * PinchToZoomFrameLayout — Digital Pinch-to-Zoom & Pan container (v1.13.0).
 * Wraps live stream / playback surfaces to support smooth 1.0x to 4.0x digital zoom,
 * single-finger panning with boundary clamping, and double-tap zoom/reset.
 */
class PinchToZoomFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var minScale: Float = 1.0f
    var maxScale: Float = 4.0f
    var currentScale: Float = 1.0f
        private set

    private var translationXVal: Float = 0f
    private var translationYVal: Float = 0f

    val currentTranslationX: Float
        get() = translationXVal

    val currentTranslationY: Float
        get() = translationYVal

    val isZoomed: Boolean
        get() = currentScale > 1.05f

    private var targetView: View? = null
    private var zoomAnimator: ValueAnimator? = null

    var onZoomChanged: ((scale: Float) -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                zoomAnimator?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val previousScale = currentScale
                currentScale = (currentScale * scaleFactor).coerceIn(minScale, maxScale)

                if (currentScale != previousScale) {
                    val focusX = detector.focusX - width / 2f
                    val focusY = detector.focusY - height / 2f

                    // Adjust translation so zooming focuses around pinch center
                    val factor = currentScale / previousScale
                    translationXVal = (translationXVal - focusX) * factor + focusX
                    translationYVal = (translationYVal - focusY) * factor + focusY

                    clampTranslations()
                    applyTransform()
                    onZoomChanged?.invoke(currentScale)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                clampTranslations()
                applyTransform()
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (currentScale > 1.05f) {
                    zoomAnimator?.cancel()
                    translationXVal -= distanceX
                    translationYVal -= distanceY
                    clampTranslations()
                    applyTransform()
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomAnimator?.cancel()
                if (currentScale > 1.05f) {
                    // Reset to 1.0x
                    animateZoomTo(1.0f, 0f, 0f)
                } else {
                    // Zoom to 2.0x centered around double tap point
                    val tapFocusX = e.x - width / 2f
                    val tapFocusY = e.y - height / 2f
                    val targetScale = 2.0f.coerceIn(minScale, maxScale)
                    val targetTransX = -tapFocusX * (targetScale - 1f)
                    val targetTransY = -tapFocusY * (targetScale - 1f)
                    animateZoomTo(targetScale, targetTransX, targetTransY)
                }
                return true
            }
        }
    )

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (childCount > 0) {
            targetView = getChildAt(0)
        }
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        if (targetView == null) {
            targetView = child
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            zoomAnimator?.cancel()
        }

        // Feed scaleGestureDetector so detector tracks pointer changes accurately
        scaleGestureDetector.onTouchEvent(ev)

        // Work Order 2.1: Intercept touch events unconditionally when multi-touch,
        // pinch is in progress, or container is already zoomed, preventing parent scroll containers from stealing touch
        if (ev.pointerCount >= 2 || scaleGestureDetector.isInProgress || currentScale > 1.05f) {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            zoomAnimator?.cancel()
        }

        var handled = scaleGestureDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled

        if (currentScale > 1.05f || event.pointerCount >= 2 || scaleGestureDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        return handled || super.onTouchEvent(event)
    }

    private fun clampTranslations() {
        val maxTransX = maxOf(0f, (width * (currentScale - 1f)) / 2f)
        val maxTransY = maxOf(0f, (height * (currentScale - 1f)) / 2f)

        translationXVal = translationXVal.coerceIn(-maxTransX, maxTransX)
        translationYVal = translationYVal.coerceIn(-maxTransY, maxTransY)
    }

    private fun applyTransform() {
        targetView?.let { view ->
            view.scaleX = currentScale
            view.scaleY = currentScale
            view.translationX = translationXVal
            view.translationY = translationYVal
        }
    }

    fun resetZoom(animate: Boolean = true) {
        zoomAnimator?.cancel()
        if (animate && currentScale > 1.01f) {
            animateZoomTo(1.0f, 0f, 0f)
        } else {
            currentScale = 1.0f
            translationXVal = 0f
            translationYVal = 0f
            applyTransform()
            onZoomChanged?.invoke(1.0f)
        }
    }

    private fun animateZoomTo(targetScale: Float, targetTransX: Float, targetTransY: Float) {
        zoomAnimator?.cancel()

        val startScale = currentScale
        val startTransX = translationXVal
        val startTransY = translationYVal

        val maxTransX = maxOf(0f, (width * (targetScale - 1f)) / 2f)
        val maxTransY = maxOf(0f, (height * (targetScale - 1f)) / 2f)
        val clampedTransX = targetTransX.coerceIn(-maxTransX, maxTransX)
        val clampedTransY = targetTransY.coerceIn(-maxTransY, maxTransY)

        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val fraction = va.animatedFraction
                currentScale = startScale + (targetScale - startScale) * fraction
                translationXVal = startTransX + (clampedTransX - startTransX) * fraction
                translationYVal = startTransY + (clampedTransY - startTransY) * fraction
                applyTransform()
                onZoomChanged?.invoke(currentScale)
            }
        }
        zoomAnimator?.start()
    }
}
