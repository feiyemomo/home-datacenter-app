package com.homedatacenter.app.util

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.ui.StyledPlayerView

/**
 * v1.6.4 rev6: gesture interactions for the video player.
 *
 * User asked: "为播放器多设计一些交互（暂停键，双击屏幕左右实现
 * 快退进，长按屏幕实现倍速播放)".
 *
 * This helper wires three interactions to a [StyledPlayerView] without
 * touching ExoPlayer's built-in controller (we keep useController=false
 * for the day-playlist mode and only use this custom chrome):
 *
 *  1. Pause button overlay (centered).
 *     - Single tap on the player surface shows the pause button for
 *       2 seconds, then auto-hides.
 *     - Tapping the pause button toggles ExoPlayer play/pause and
 *       swaps the icon (ic_pause ↔ ic_play_circle).
 *
 *  2. Double-tap left/right half of the player.
 *     - Double-tap on the LEFT 40% of the surface seeks -10 seconds.
 *     - Double-tap on the RIGHT 40% of the surface seeks +10 seconds.
 *     - The 20% middle band is reserved for the pause button so it
 *       doesn't fight the seek gesture.
 *
 *  3. Long-press the player surface.
 *     - While held: playback speed jumps to 3.0x (fast-forward).
 *     - On release: restores the previously-saved playback speed
 *       (e.g. 1.0x or whatever the user picked via the speed menu).
 *
 * Implementation note: we attach a [GestureDetector] to the playerView
 * via setOnTouchListener. The pause button itself uses its own
 * OnClickListener (single tap = toggle play/pause). The detector's
 * onSingleTapConfirmed, onDoubleTapEvent, and onLongPress callbacks
 * implement the three interactions.
 *
 * Lifecycle: call [attach] after the player is set on the playerView.
 * Call [onPlayerChanged] when the player instance is replaced (so the
 * helper points at the new player). Call [release] in the host's
 * dismiss / onDestroy to remove the auto-hide handler.
 */
class PlayerGestureHelper(
    private val playerView: StyledPlayerView,
    private val pauseButton: View,
    private val seekHintView: View? = null,
) {
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var savedSpeed: Float = 1.0f
    private var isLongPressing = false

    // Auto-hide the pause button after this many ms of inactivity.
    // 2s matches the typical "tap to see controls" pattern (YouTube
    // uses 3s, but our button is smaller and less intrusive so 2s
    // keeps it visible just long enough to register a tap).
    private val hideDelayMs = 2000L

    // Seek delta for double-tap. 10s matches YouTube/Bilibili UX —
    // fast enough that 5-6 double-taps skip a minute, slow enough
    // that the user can re-target with a single tap without overshooting.
    private val doubleTapSeekMs = 10_000L

    private val hideRunnable = Runnable {
        pauseButton.visibility = View.GONE
    }

    private val gestureDetector = GestureDetector(playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Single tap: toggle pause-button visibility.
                // If currently visible, hide immediately (lets the
                // user dismiss the chrome with one tap). If hidden,
                // show for [hideDelayMs] then auto-hide.
                if (pauseButton.visibility == View.VISIBLE) {
                    pauseButton.visibility = View.GONE
                    handler.removeCallbacks(hideRunnable)
                } else {
                    showPauseButton()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Determine which side of the player the tap landed on.
                // The middle 20% band is reserved (overlaps the centered
                // pause button) so a double-tap there doesn't accidentally
                // trigger a seek — the user gets a no-op, which is safer
                // than an unintended skip.
                val width = playerView.width.toFloat()
                val x = e.x
                val leftBandEnd = width * 0.40f
                val rightBandStart = width * 0.60f
                when {
                    x < leftBandEnd -> seekBy(-doubleTapSeekMs)
                    x > rightBandStart -> seekBy(doubleTapSeekMs)
                    else -> {
                        // Middle band: hide the pause button (treat
                        // as a "dismiss chrome" tap rather than a seek).
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Long-press: enter 3x fast-forward mode.
                // We save the current speed so onLongPressEnd (touch-up)
                // can restore it. If the user is already at >= 3x (e.g.
                // they set 2x via the speed menu, then long-pressed),
                // we still save 2x and restore it on release.
                val p = player ?: return
                if (isLongPressing) return
                isLongPressing = true
                savedSpeed = p.playbackParameters.speed
                p.playbackParameters = PlaybackParameters(3.0f)
                showPauseButton()
            }
        })

    /**
     * Wires the gesture detector + pause button click listener to
     * the views. Must be called AFTER [playerView]'s player is set
     * (otherwise [onPlayerChanged] should be called separately).
     */
    fun attach(player: ExoPlayer?) {
        this.player = player
        // Make sure playerView can receive touch events. The
        // StyledPlayerView's controller surface handles its own
        // touches, but with useController=false the underlying
        // FrameLayout is the touch target — we set it clickable.
        playerView.isClickable = true
        playerView.isFocusable = true
        playerView.setOnTouchListener { _, event ->
            // Route to GestureDetector for tap/double-tap/long-press.
            // We must return false on ACTION_UP/ACTION_CANCEL when a
            // long-press was active so we can detect the release and
            // restore the saved speed — GestureDetector.SimpleOnGesture-
            // Listener doesn't expose touch-up events.
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (isLongPressing) {
                    isLongPressing = false
                    player?.playbackParameters = PlaybackParameters(savedSpeed)
                    // Keep the button visible briefly so the user
                    // sees the speed snap back.
                    showPauseButton()
                }
            }
            gestureDetector.onTouchEvent(event)
        }
        pauseButton.setOnClickListener {
            val p = player ?: return@setOnClickListener
            // Toggle play/pause.
            p.playWhenReady = !p.playWhenReady
            updatePauseIcon(p.playWhenReady)
            // Re-show the button + schedule auto-hide so the user
            // can see the icon swap.
            showPauseButton()
        }
        updatePauseIcon(player?.playWhenReady ?: true)
    }

    /**
     * Updates the player reference when the host replaces its ExoPlayer
     * (e.g. on day-change in RecordingsDialog). Resets the saved
     * speed snapshot.
     */
    fun onPlayerChanged(player: ExoPlayer?) {
        this.player = player
        isLongPressing = false
        savedSpeed = player?.playbackParameters?.speed ?: 1.0f
        updatePauseIcon(player?.playWhenReady ?: true)
    }

    /**
     * Releases the auto-hide handler. Call from the host's
     * onPause / onDestroy / dismiss to avoid leaking callbacks.
     */
    fun release() {
        handler.removeCallbacks(hideRunnable)
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
        // Swap the pause button's icon to match the player's state.
        // The MaterialButton's setIconResource takes care of sizing.
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
        // Briefly show the pause button as a "gesture received"
        // affordance — gives the user feedback that their
        // double-tap registered.
        showPauseButton()
    }
}
