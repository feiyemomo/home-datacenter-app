package com.homedatacenter.app.ui.cameras

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.google.android.exoplayer2.ui.StyledPlayerView
import org.webrtc.SurfaceViewRenderer

/**
 * PinchToZoomFrameLayout — Digital Pinch-to-Zoom & Pan container (v1.18.0).
 * Wraps live stream / playback surfaces to support smooth 1.0x to 4.0x digital zoom,
 * single-finger panning with boundary clamping, and double-tap zoom/reset.
 *
 * Core Fixes:
 * 1. Hardware & Outline Clipping: Strictly enforces clipChildren, clipToPadding, and clipToOutline
 *    so neither TextureView nor SurfaceControl (SurfaceView) can overflow onto surrounding UI components.
 * 2. Dedicated Video Surface Targeting: When wrapping a [StyledPlayerView], targets [StyledPlayerView.videoSurfaceView]
 *    directly so playback controllers, timebars, and action buttons remain pinned, crisp, and unmagnified.
 * 3. Dynamic Dual-Kernel Resolution: Automatically binds to the active visible surface ([SurfaceViewRenderer] for WebRTC,
 *    [videoSurfaceView] for ExoPlayer MP4/HLS, or preview image) without hardcoding child indices.
 * 4. Seamless Kernel Handoff: Smoothly transfers current zoom scale and offsets when switching between
 *    WebRTC and ExoPlayer, resetting transforms on the inactive surface.
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

    /**
     * Explicit target view override. If null, [getActiveTargetView] automatically resolves
     * the active visible rendering surface among children.
     */
    var customTargetView: View? = null
        set(value) {
            if (field != value) {
                lastTargetView?.let { resetViewTransform(it) }
                lastTargetView = null
                field = value
                applyTransform()
            }
        }

    private var lastTargetView: View? = null
    private var zoomAnimator: ValueAnimator? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialSpan = 0f
    private var isScalingActive = false
    private var prevFocusX = 0f
    private var prevFocusY = 0f

    // PhotoView-style active-pointer pan tracker (prevents multi-touch <-> single-touch focal jumps)
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    /**
     * Threshold above which the player is considered actively zoomed in.
     * Raised to 1.15f (was 1.05f) to prevent accidental micro-pinches from
     * confusing swipe / scroll navigation gestures.
     */
    private val zoomEngageThreshold = 1.15f

    var onZoomChanged: ((scale: Float) -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null

    init {
        clipChildren = true
        clipToPadding = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRect(0, 0, view.width, view.height)
            }
        }
    }

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                zoomAnimator?.cancel()
                initialSpan = detector.currentSpan
                prevFocusX = detector.focusX
                prevFocusY = detector.focusY
                isScalingActive = currentScale >= zoomEngageThreshold
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                val spanDiff = kotlin.math.abs(detector.currentSpan - initialSpan)
                if (!isScalingActive) {
                    // Commit scale quickly on multi-touch (> touchSlop * 0.5f) so fast pinches aren't lost
                    if (spanDiff > touchSlop * 0.5f) {
                        isScalingActive = true
                        prevFocusX = detector.focusX
                        prevFocusY = detector.focusY
                    } else {
                        return false
                    }
                }

                val scaleFactor = detector.scaleFactor
                val previousScale = currentScale
                currentScale = (currentScale * scaleFactor).coerceIn(minScale, maxScale)

                val focusDx = detector.focusX - prevFocusX
                val focusDy = detector.focusY - prevFocusY
                prevFocusX = detector.focusX
                prevFocusY = detector.focusY

                val focusX = detector.focusX - width / 2f
                val focusY = detector.focusY - height / 2f

                // Scale around pinch center AND track two-finger pan delta smoothly
                val factor = if (previousScale > 0f) currentScale / previousScale else 1f
                translationXVal = (translationXVal - focusX) * factor + focusX + focusDx
                translationYVal = (translationYVal - focusY) * factor + focusY + focusDy

                clampTranslations()
                applyTransform()
                if (currentScale != previousScale) {
                    onZoomChanged?.invoke(currentScale)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScalingActive = false
                lastTouchX = detector.focusX
                lastTouchY = detector.focusY
                if (currentScale < zoomEngageThreshold) {
                    animateZoomTo(1.0f, 0f, 0f)
                } else {
                    clampTranslations()
                    applyTransform()
                }
            }
        }
    ).apply {
        // Disable single-finger double-tap-drag QuickScale so it never fights pan or double-tap reset
        isQuickScaleEnabled = false
    }

    private val tapGestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomAnimator?.cancel()
                if (currentScale >= zoomEngageThreshold) {
                    animateZoomTo(1.0f, 0f, 0f)
                } else {
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
        clipChildren = true
        clipToPadding = true
        clipToOutline = true
    }

    private fun trackPanPointers(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = ev.getPointerId(0)
                lastTouchX = ev.x
                lastTouchY = ev.y
                isDragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch started: pause single-finger pan
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleGestureDetector.isInProgress || ev.pointerCount >= 2) {
                    isDragging = false
                    return
                }
                if (currentScale >= zoomEngageThreshold && activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    val idx = ev.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val x = ev.getX(idx)
                        val y = ev.getY(idx)
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY
                        if (!isDragging && (dx * dx + dy * dy) >= touchSlop * touchSlop * 0.25f) {
                            isDragging = true
                        }
                        if (isDragging) {
                            zoomAnimator?.cancel()
                            translationXVal += dx
                            translationYVal += dy
                            clampTranslations()
                            applyTransform()
                        }
                        lastTouchX = x
                        lastTouchY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = ev.actionIndex
                val pointerId = ev.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    if (newPointerIndex < ev.pointerCount) {
                        activePointerId = ev.getPointerId(newPointerIndex)
                        lastTouchX = ev.getX(newPointerIndex)
                        lastTouchY = ev.getY(newPointerIndex)
                    }
                } else {
                    val idx = ev.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        lastTouchX = ev.getX(idx)
                        lastTouchY = ev.getY(idx)
                    }
                }
                isDragging = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isDragging = false
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            zoomAnimator?.cancel()
        }

        trackPanPointers(ev)
        scaleGestureDetector.onTouchEvent(ev)

        if (ev.pointerCount >= 2 || ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        if (currentScale >= zoomEngageThreshold || (scaleGestureDetector.isInProgress && isScalingActive)) {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            zoomAnimator?.cancel()
        }

        if (event.pointerCount >= 2 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        val scaleHandled = scaleGestureDetector.onTouchEvent(event)
        trackPanPointers(event)
        val tapHandled = if (!scaleGestureDetector.isInProgress && event.pointerCount <= 1) {
            tapGestureDetector.onTouchEvent(event)
        } else {
            false
        }

        if (event.pointerCount >= 2 || currentScale >= zoomEngageThreshold || (scaleGestureDetector.isInProgress && isScalingActive)) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            if (currentScale < zoomEngageThreshold && currentScale > 1.0f) {
                animateZoomTo(1.0f, 0f, 0f)
            }
        }

        return scaleHandled || tapHandled || isDragging || super.onTouchEvent(event)
    }

    private fun clampTranslations() {
        val maxTransX = maxOf(0f, (width * (currentScale - 1f)) / 2f)
        val maxTransY = maxOf(0f, (height * (currentScale - 1f)) / 2f)

        translationXVal = translationXVal.coerceIn(-maxTransX, maxTransX)
        translationYVal = translationYVal.coerceIn(-maxTransY, maxTransY)
    }

    /**
     * Resolves the active target view that should receive scale and translation transforms.
     * Prefers active visible video rendering surfaces over entire container layouts.
     */
    fun getActiveTargetView(): View? {
        val custom = customTargetView
        if (custom != null) {
            return resolveVideoSurface(custom)
        }

        // 1. Check for visible SurfaceViewRenderer (WebRTC surface)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is SurfaceViewRenderer && child.visibility == View.VISIBLE) {
                return child
            }
        }

        // 2. Check for visible StyledPlayerView (ExoPlayer surface)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is StyledPlayerView && child.visibility == View.VISIBLE) {
                return resolveVideoSurface(child)
            }
        }

        // 3. Check for visible preview ImageView (fallback before stream starts)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is ImageView && child.visibility == View.VISIBLE && child.id == com.homedatacenter.app.R.id.ivPreviewFrame) {
                return child
            }
        }

        // 4. Fallback: first child that is not an overlay or control view
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (isEligibleVideoView(child)) {
                return resolveVideoSurface(child)
            }
        }

        return if (childCount > 0) resolveVideoSurface(getChildAt(0)) else null
    }

    private fun isEligibleVideoView(view: View): Boolean {
        val id = view.id
        return id != com.homedatacenter.app.R.id.tvZoomBadge &&
                id != com.homedatacenter.app.R.id.tvStreamStrategy &&
                id != com.homedatacenter.app.R.id.tvHlsNotice &&
                id != com.homedatacenter.app.R.id.webRtcControls &&
                id != com.homedatacenter.app.R.id.progressVideo &&
                id != com.homedatacenter.app.R.id.tvVideoError
    }

    private fun resolveVideoSurface(view: View): View {
        return if (view is StyledPlayerView) {
            view.videoSurfaceView ?: view
        } else {
            view
        }
    }

    private fun resetViewTransform(view: View) {
        view.scaleX = 1.0f
        view.scaleY = 1.0f
        view.translationX = 0f
        view.translationY = 0f
    }

    /**
     * Apply scale and translation to the active video surface.
     * If the active target has changed (e.g. WebRTC <-> ExoPlayer transition),
     * resets the previous target and applies transforms to the newly active one.
     */
    fun applyTransform() {
        val target = getActiveTargetView() ?: return

        if (lastTargetView != null && lastTargetView !== target) {
            lastTargetView?.let { resetViewTransform(it) }
        }
        lastTargetView = target

        if (target.width > 0 && target.height > 0) {
            target.pivotX = target.width / 2f
            target.pivotY = target.height / 2f
        }

        target.scaleX = currentScale
        target.scaleY = currentScale
        target.translationX = translationXVal
        target.translationY = translationYVal
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
            lastTargetView?.let { resetViewTransform(it) }
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
