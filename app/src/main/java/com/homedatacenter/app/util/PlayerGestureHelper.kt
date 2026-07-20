package com.homedatacenter.app.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.ui.StyledPlayerView

/**
 * v1.6.5 rev7: gesture interactions for the video player.
 *
 * User asked: "为播放器多设计一些交互（暂停键，双击屏幕左右实现
 * 快退进，长按屏幕实现倍速播放)" + rev7 follow-up "双击快退进改为
 * 两个单书名号样式闪烁两下的动画吧" and "长按倍速也改为5x".
 *
 * This helper wires these interactions to a [StyledPlayerView]:
 *
 *  1. Pause button overlay (centered).
 *     - Single tap on the player surface shows the pause button for
 *       2 seconds, then auto-hides.
 *     - Tapping the pause button toggles ExoPlayer play/pause and
 *       swaps the icon (ic_pause ↔ ic_play_circle).
 *
 *  2. Double-tap left/right half of the player.
 *     - Double-tap on the LEFT 40% of the surface seeks -10 seconds
 *       AND blinks the [seekRewindHint] TextView ("《") twice.
 *     - Double-tap on the RIGHT 40% seeks +10 seconds AND blinks
 *       [seekForwardHint] ("》") twice.
 *     - Middle 20% reserved for the pause button.
 *     - Animation: alpha 0 → 1 → 0 → 1 → 0 over ~500ms (two visible
 *       flashes). User said "闪烁两下".
 *
 *  3. Long-press the player surface.
 *     - While held: playback speed jumps to [longPressSpeed] (5.0x
 *       per rev7 — was 3.0x in rev6).
 *     - On release: restores the previously-saved playback speed.
 *
 *  4. (rev7) Speed slider hook.
 *     - [onSpeedChangedBySlider] is called by the host when the user
 *       drags the speed Slider, so the helper's [savedSpeed] snapshot
 *       stays in sync (otherwise the long-press-release would restore
 *       a stale pre-slider value).
 */
class PlayerGestureHelper(
    private val playerView: StyledPlayerView,
    private val pauseButton: View,
    private val seekRewindHint: TextView? = null,
    private val seekForwardHint: TextView? = null,
) {
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var savedSpeed: Float = 1.0f
    private var isLongPressing = false

    // v1.6.5 rev7: long-press speed bumped to 5.0x per user request
    // "长按倍速也改为5x".
    private val longPressSpeed = 5.0f

    private val hideDelayMs = 2000L
    private val doubleTapSeekMs = 10_000L

    private val hideRunnable = Runnable {
        pauseButton.visibility = View.GONE
    }

    // Currently-running seek hint animator (so a new double-tap can
    // cancel the previous animation and start fresh).
    private var seekHintAnimator: ObjectAnimator? = null

    private val gestureDetector = GestureDetector(playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (pauseButton.visibility == View.VISIBLE) {
                    pauseButton.visibility = View.GONE
                    handler.removeCallbacks(hideRunnable)
                } else {
                    showPauseButton()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val width = playerView.width.toFloat()
                val x = e.x
                val leftBandEnd = width * 0.40f
                val rightBandStart = width * 0.60f
                when {
                    x < leftBandEnd -> {
                        seekBy(-doubleTapSeekMs)
                        playSeekHintAnimation(seekRewindHint)
                    }
                    x > rightBandStart -> {
                        seekBy(doubleTapSeekMs)
                        playSeekHintAnimation(seekForwardHint)
                    }
                    else -> {
                        // Middle band: no-op (avoid misfire).
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val p = player ?: return
                if (isLongPressing) return
                isLongPressing = true
                savedSpeed = p.playbackParameters.speed
                p.playbackParameters = PlaybackParameters(longPressSpeed)
                showPauseButton()
            }
        })

    fun attach(player: ExoPlayer?) {
        this.player = player
        playerView.isClickable = true
        playerView.isFocusable = true
        playerView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (isLongPressing) {
                    isLongPressing = false
                    player?.playbackParameters = PlaybackParameters(savedSpeed)
                    showPauseButton()
                }
            }
            gestureDetector.onTouchEvent(event)
        }
        pauseButton.setOnClickListener {
            val p = player ?: return@setOnClickListener
            p.playWhenReady = !p.playWhenReady
            updatePauseIcon(p.playWhenReady)
            showPauseButton()
        }
        updatePauseIcon(player?.playWhenReady ?: true)
    }

    fun onPlayerChanged(player: ExoPlayer?) {
        this.player = player
        isLongPressing = false
        savedSpeed = player?.playbackParameters?.speed ?: 1.0f
        updatePauseIcon(player?.playWhenReady ?: true)
    }

    /**
     * v1.6.5 rev7: called by the host when the user drags the speed
     * Slider. Updates [savedSpeed] so a subsequent long-press release
     * restores the slider's value (rather than a stale pre-slider
     * value from before the user touched the slider).
     */
    fun onSpeedChangedBySlider(newSpeed: Float) {
        // Only update savedSpeed if we're not currently long-pressing
        // — otherwise we'd overwrite the pre-long-press snapshot
        // and the release would restore the wrong (5x) value.
        if (!isLongPressing) {
            savedSpeed = newSpeed
        }
    }

    fun release() {
        handler.removeCallbacks(hideRunnable)
        seekHintAnimator?.cancel()
        seekHintAnimator = null
        playerView.setOnTouchListener(null)
        pauseButton.setOnClickListener(null)
        player = null
    }

    private fun showPauseButton() {
        pauseButton.visibility = View.VISIBLE
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, hideDelayMs)
    }

    private fun updatePauseIcon(isPlaying: Boolean) {
        if (pauseButton is com.google.android.material.button.MaterialButton) {
            pauseButton.setIconResource(
                if (isPlaying) com.homedatacenter.app.R.drawable.ic_pause
                else com.homedatacenter.app.R.drawable.ic_play_circle
            )
        }
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val current = p.currentPosition
        val target = (current + deltaMs).coerceAtLeast(0L)
            .coerceAtMost(p.duration.coerceAtLeast(0L).let { if (it > 0) it else Long.MAX_VALUE })
        p.seekTo(target)
        showPauseButton()
    }

    /**
     * v1.6.5 rev7: blinks [hintView] twice (alpha 0→1→0→1→0).
     * User said "闪烁两下". We use a single ObjectAnimator with a
     * multi-stop KeyFrame-like float array — AnimatorSet isn't
     * needed since one property (alpha) is animated.
     *
     * Total duration: 500ms (each flash ~125ms on, ~125ms off).
     * The hint view's visibility is set to VISIBLE at the start and
     * back to GONE at the end (via animator listener) so it doesn't
     * intercept future touches.
     */
    private fun playSeekHintAnimation(hintView: TextView?) {
        if (hintView == null) return
        seekHintAnimator?.cancel()
        hintView.visibility = View.VISIBLE
        // Float array = keyframe values at evenly-spaced fractions.
        // 5 keyframes over 500ms = 0.0, 0.25, 0.5, 0.75, 1.0
        // alpha pattern: 0 → 1 → 0 → 1 → 0 (two visible flashes)
        val animator = ObjectAnimator.ofFloat(
            hintView, "alpha",
            0f, 1f, 0f, 1f, 0f,
        ).apply {
            duration = 500L
            // Slightly ease the flash on/off — feels more "liquid
            // glass" than a linear snap.
            setAutoCancel(true)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    hintView.visibility = View.GONE
                    hintView.alpha = 0f
                }
            })
        }
        seekHintAnimator = animator
        animator.start()
    }
}
