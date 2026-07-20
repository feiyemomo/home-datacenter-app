package com.homedatacenter.app.util

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupMenu
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.homedatacenter.app.R

/**
 * Bridges ExoPlayer's [StyledPlayerView] fullscreen button and the
 * standalone playback-speed button to the host Activity / Dialog.
 *
 * v1.5.3 introduced two changes that needed identical wiring across
 * three player hosts ([CameraDetailActivity], [RecordingsDialog],
 * [AlertsDialog]):
 *  1. A native fullscreen button on the player controller — tapping
 *     it forces landscape orientation and hides the toolbar / other
 *     UI so the video fills the screen. Tapping again restores the
 *     portrait layout.
 *  2. A standalone speed button (previously the gear/settings menu
 *     in the player controller exposed playback speed; per user
 *     feedback we moved it out into a top-right overlay button).
 *
 * Lifecycle: the helper is created with a non-null player view
 * plus a list of views to hide when fullscreen. The fullscreen
 * button click listener is attached in [attach]. The speed button
 * is optional — if [speedButton] is non-null, [attachSpeedButton]
 * wires up a popup with 0.5x / 1x / 1.5x / 2x options.
 *
 * The host Activity must declare `configChanges="orientation|`
 * screenSize|keyboardHidden|screenLayout"` in the manifest so the
 * activity isn't recreated on rotation — otherwise ExoPlayer would
 * be torn down and the live stream would need to re-buffer.
 *
 * For dialogs (RecordingsDialog / AlertsDialog), the host Activity
 * is found via [unwrapActivity]; the orientation request is set on
 * the underlying Activity, which the Dialog follows visually.
 */
class PlayerFullscreenHelper(
    private val playerView: StyledPlayerView,
    private val hostView: View,
    private val hideOnFullscreen: List<View>,
    private val speedButton: View? = null,
    /**
     * Optional secondary view that should expand to MATCH_PARENT
     * in fullscreen, mirroring [playerView]'s height changes. Used
     * by [CameraDetailActivity] for the WebRTC SurfaceViewRenderer —
     * only one of [playerView] / this view is VISIBLE at a time, but
     * both need to be resized so the visible one fills the screen
     * when entering fullscreen and restores its height on exit.
     */
    private val secondaryPlayerView: View? = null,
    /**
     * Optional standalone fullscreen button overlay. Used when the
     * StyledPlayerView's built-in fullscreen button isn't visible
     * (e.g. when playerView is GONE during WebRTC playback). The
     * helper wires [View.OnClickListener] to [toggleFullscreen].
     */
    private val fullscreenButton: View? = null,
) {
    private var isFullscreen: Boolean = false
    private var player: ExoPlayer? = null
    private var savedPlayerHeightPx: Int = 0
    private var savedSecondaryHeightPx: Int = 0

    /**
     * Attaches the fullscreen button click listener. Must be called
     * after [playerView]'s player has been set (otherwise the
     * controller UI isn't ready).
     *
     * ExoPlayer 2.x's StyledPlayerView hides the fullscreen button
     * in its controller by default; calling
     * `setFullscreenButtonClickListener` reveals it and wires up
     * the toggle behavior. The standalone [fullscreenButton]
     * overlay (when provided) is used for player hosts where the
     * built-in controller button isn't reachable — e.g. when
     * WebRTC is rendering to SurfaceViewRenderer and the
     * StyledPlayerView is GONE.
     */
    fun attach() {
        playerView.setFullscreenButtonClickListener { enter ->
            isFullscreen = enter
            applyFullscreenState()
        }
        speedButton?.let { attachSpeedButton(it) }
        fullscreenButton?.setOnClickListener { toggleFullscreen() }
        // v1.5.7: no clipChildren manipulation needed — we no longer
        // use bringToFront/elevation; siblings are hidden via
        // [hideOnFullscreen] (visibility=GONE) instead.
    }

    /**
     * Toggles fullscreen state from an external caller (e.g. the
     * WebRTC standalone fullscreen overlay button — see
     * CameraDetailActivity's btnWebRtcFullscreen). The standalone
     * button is needed because [playerView]'s built-in controller
     * isn't visible when playerView is GONE during WebRTC playback.
     */
    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        applyFullscreenState()
    }

    /**
     * Call this when the player is set or replaced so the speed
     * button reflects the new player's current speed.
     */
    fun onPlayerChanged(player: ExoPlayer?) {
        this.player = player
        if (player != null && speedButton is android.widget.TextView) {
            val speed = player.playbackParameters.speed
            (speedButton as android.widget.TextView).text = formatSpeed(speed)
        }
    }

    /**
     * Call this from the host's onPause / onDestroy / dismiss
     * handler to release the orientation lock so the next screen
     * isn't stuck in landscape.
     */
    fun release() {
        val activity = unwrapActivity(hostView.context)
        if (isFullscreen && activity != null) {
            try {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } catch (_: Exception) {
                // Some hosts (e.g. dialog hosted by a transparent
                // activity) may not allow orientation changes —
                // ignore and let the system handle it.
            }
        }
        isFullscreen = false
    }

    private fun applyFullscreenState() {
        val activity = unwrapActivity(hostView.context)
        // Toggle orientation. The host Activity must declare
        // configChanges in the manifest, otherwise the activity
        // will recreate and ExoPlayer will be torn down mid-stream.
        if (activity != null) {
            try {
                activity.requestedOrientation = if (isFullscreen) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            } catch (_: Exception) {
                // Ignore — orientation lock not allowed in this host.
            }
        }
        // Hide/show non-player UI elements.
        val visibility = if (isFullscreen) View.GONE else View.VISIBLE
        hideOnFullscreen.forEach { it.visibility = visibility }

        // Expand/collapse the player view itself. We save the
        // original height so we can restore it exactly when exiting
        // fullscreen — important because the host may have set a
        // specific dp height that differs from the default.
        applyHeight(playerView, save = { savedPlayerHeightPx = it }, restore = { savedPlayerHeightPx })
        // Apply the same treatment to the secondary player view
        // (WebRTC SurfaceViewRenderer) so it fills the screen in
        // fullscreen regardless of which surface is currently
        // rendering. When the secondary view is GONE (i.e. ExoPlayer
        // is the active player), resizing it has no visible effect.
        applyHeight(secondaryPlayerView, save = { savedSecondaryHeightPx = it }, restore = { savedSecondaryHeightPx })
    }

    /**
     * Expands [view] to fill the screen in fullscreen and restores its
     * saved height on exit. v1.5.7: rewritten again to fix two issues
     * from v1.5.6:
     *   1. Player controls (pause / volume / scrubber) disappeared in
     *      fullscreen because elevation=16f lifted the playerView
     *      above its own child controller layer — ExoPlayer's
     *      StyledPlayerView controller is a child of the player view,
     *      so elevating the parent doesn't push it behind the player
     *      surface, but it did break the controller's touch handling
     *      because bringToFront reordered it under the surface
     *      renderer's sibling overlay (btnFullscreen / btnPlaybackSpeed).
     *   2. Fullscreen height didn't fill the screen because
     *      displayMetrics.heightPixels includes the status bar inset
     *      but not the navigation bar inset on devices with gesture
     *      nav — leaving a gap at the top or bottom.
     *
     * Fix:
     *   - Use the host Activity's window visible frame (via
     *     windowManager.maximumWindowMetrics) for the fullscreen
     *     height — this is the actual usable area in the current
     *     orientation.
     *   - Don't use bringToFront/elevation. Instead, hide all sibling
     *     views in [hideOnFullscreen] (visibility=GONE) so the
     *     playerView's parent has nothing else to render. The player
     *     surface + controller remain in their normal z-order within
     *     the playerView, so controls keep working.
     *   - The parent FrameLayout's height is set to the fullscreen
     *     height explicitly (this works for RecordingsDialog and
     *     AlertsDialog where the player's parent is NOT inside a
     *     ScrollView; for CameraDetailActivity the playerView's parent
     *     IS inside a ScrollView, so we set the playerView's own
     *     height to the fullscreen pixel value instead and rely on
     *     ScrollView's child being measured at the exact requested
     *     height when measured with AT_MOST spec — ScrollView uses
     *     UNSPECIFIED for its single child, which honors an exact
     *     pixel height just fine).
     */
    private fun applyHeight(
        view: View?,
        save: (Int) -> Unit,
        restore: () -> Int,
    ) {
        if (view == null) return
        val params = view.layoutParams
        if (isFullscreen) {
            if (params.height > 0) {
                save(params.height)
            }
            // Use the actual window visible frame height in pixels.
            // displayMetrics.heightPixels includes the status bar
            // but not the gesture nav inset on some devices, leaving
            // gaps. windowManager.maximumWindowMetrics gives the
            // actual full window size for the current orientation.
            val fullscreenHeight = runCatching {
                val wm = hostView.context.getSystemService(Context.WINDOW_SERVICE)
                    as WindowManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    wm.maximumWindowMetrics.bounds.height()
                } else {
                    @Suppress("DEPRECATION")
                    hostView.resources.displayMetrics.heightPixels
                }
            }.getOrDefault(hostView.resources.displayMetrics.heightPixels)

            params.height = fullscreenHeight
            // v1.5.7: don't elevate or bringToFront — both break the
            // controller's touch dispatch. Instead, hide siblings via
            // [hideOnFullscreen] (visibility=GONE) so the playerView
            // is the only visible child of its parent.
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            val saved = restore()
            params.height = saved.takeIf { it > 0 }
                ?: TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    DEFAULT_PLAYER_HEIGHT_DP.toFloat(),
                    hostView.resources.displayMetrics,
                ).toInt()
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        view.layoutParams = params
    }

    private fun attachSpeedButton(button: View) {
        button.setOnClickListener { view ->
            val p = player ?: return@setOnClickListener
            val popup = PopupMenu(view.context, view)
            SPEEDS.forEachIndexed { idx, speed ->
                val item = popup.menu.add(0, idx, idx, formatSpeed(speed))
                if (speed == p.playbackParameters.speed) {
                    item.isChecked = true
                }
            }
            popup.menu.setGroupCheckable(0, true, true)
            popup.setOnMenuItemClickListener { menuItem ->
                val speed = SPEEDS[menuItem.itemId]
                p.playbackParameters = PlaybackParameters(speed)
                if (button is android.widget.TextView) {
                    button.text = formatSpeed(speed)
                }
                true
            }
            popup.show()
        }
    }

    companion object {
        private val SPEEDS = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)
        private const val DEFAULT_PLAYER_HEIGHT_DP = 200

        private fun formatSpeed(speed: Float): String {
            return if (speed == 1.0f) "1x"
            else if (speed < 1.0f) "${speed}x"
            else "${speed.toInt()}x"
        }

        /**
         * Unwraps a Context (potentially wrapped by Dialog / ContextWrapper)
         * to find the underlying Activity. Returns null if the context
         * chain doesn't contain an Activity.
         */
        private fun unwrapActivity(context: Context): Activity? {
            var ctx: Context? = context
            while (ctx is android.content.ContextWrapper) {
                if (ctx is Activity) return ctx
                ctx = ctx.baseContext
            }
            return ctx as? Activity
        }
    }
}
