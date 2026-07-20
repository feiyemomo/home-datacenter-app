package com.homedatacenter.app.util

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    /**
     * v1.6.4: views that should fade in/out with the ExoPlayer
     * controller's visibility while in fullscreen. The user asked:
     * "全屏时，点击播放器收起进度条等的插件，再次点击是在展现".
     * Instead of writing a separate tap-to-toggle mechanism, we hook
     * StyledPlayerView's existing [ControllerVisibilityListener] —
     * ExoPlayer's controller is already click-to-toggle by default,
     * so its visibility transitions are exactly the "tap playerView
     * to show/hide chrome" signal we need.
     *
     * Behavior:
     *  - Enter fullscreen: [hideOnFullscreen] views go GONE (toolbar);
     *    [controllerSyncViews] follow the controller (VISIBLE by default
     *    since the controller auto-shows on enter).
     *  - Tap video while controller visible: controller auto-hides
     *    after [show_timeout]; listener fires, [controllerSyncViews]
     *    go GONE.
     *  - Tap video again: controller re-shows; listener fires,
     *    [controllerSyncViews] go VISIBLE.
     *  - Exit fullscreen: [hideOnFullscreen] + [controllerSyncViews]
     *    all restore to VISIBLE (their pre-fullscreen state).
     *
     * Typical members: dayScrubBarContainer, motionChipScroller,
     * btnBack, btnPlaybackSpeed. NOT [fullscreenButton] (it must
     * always be reachable to exit fullscreen) and NOT [toolbar]
     * (it should stay GONE for the whole fullscreen session).
     */
    private val controllerSyncViews: List<View> = emptyList(),
) {
    private var isFullscreen: Boolean = false
    private var player: ExoPlayer? = null
    // v1.6.4: latch for the playerView tap-to-toggle behavior. True
    // means the synced chrome (dayScrubBarContainer + chip scroller +
    // buttons) is currently VISIBLE; false means the user has tapped
    // to hide it. Starts true so the first fullscreen entry shows
    // chrome by default (matches the v1.5.x "always visible" UX the
    // user is used to).
    private var controllerOverlayVisible: Boolean = true
    // v1.6.4: default to MATCH_PARENT so the first exit-fullscreen
    // restores the player to fill its container (matches the typical
    // XML layout_height="match_parent"). The previous default was 0,
    // which caused applyHeight's `saved.takeIf { it > 0 }` fallback
    // to kick in and force 200dp — the player appeared at the top of
    // the container instead of filling it after exiting fullscreen.
    private var savedPlayerHeightPx: Int = ViewGroup.LayoutParams.MATCH_PARENT
    private var savedSecondaryHeightPx: Int = ViewGroup.LayoutParams.MATCH_PARENT
    // v1.5.9: remember each host view's fitsSystemWindows state so
    // we can restore it on exit. CameraDetailActivity's root ScrollView
    // sets fitsSystemWindows=true to push content below the status bar
    // in portrait — but in fullscreen landscape we WANT edge-to-edge
    // rendering, so we disable it temporarily. For dialog hosts this
    // is typically false already (dialogs handle insets themselves).
    private var savedHostFitsSystemWindows: Boolean = false
    private var savedParentFitsSystemWindows: Boolean = false
    // v1.5.10: v1.5.9 only disabled fitsSystemWindows on hostView +
    // its direct parent, but CameraDetailActivity's playerView lives
    // inside a ScrollView which is itself a child of the content view.
    // The ScrollView's fitsSystemWindows=true applied the status bar
    // inset as top padding EVEN when its child LinearLayout had
    // fitsSystemWindows=false — leaving the player stuck below the
    // status bar.
    //
    // Fix: walk the ENTIRE ancestor chain from hostView up to the
    // android.R.id.content root, saving + clearing fitsSystemWindows
    // AND topPadding on each one. This guarantees the player surface
    // can render edge-to-edge under the (hidden) status bar.
    private data class SavedInsetState(
        val view: View,
        val fitsSystemWindows: Boolean,
        val paddingTop: Int,
        val paddingBottom: Int,
        val paddingLeft: Int,
        val paddingRight: Int,
    )
    private val savedAncestorStates = mutableListOf<SavedInsetState>()

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
        // v1.6.4: wire playerView tap-to-toggle for [controllerSyncViews]
        // while in fullscreen. The user asked: "全屏时，点击播放器收起
        // 进度条等的插件，再次点击是在展现". We can't rely on
        // StyledPlayerView's controller visibility listener because
        // the host typically calls useController=false (custom scrub
        // bar instead of ExoPlayer's built-in one), so the controller
        // never shows/hides and the listener never fires. Instead we
        // register a direct OnClickListener on the playerView — the
        // click flips [controllerSyncViews] between VISIBLE and GONE
        // while in fullscreen.
        //
        // We also latch the user's "hidden" intent so subsequent
        // fullscreen entries remember it (e.g. user hides chrome,
        // exits fullscreen, re-enters — chrome stays hidden until
        // they tap to show again). The latch is reset only on exit
        // so non-fullscreen always shows chrome.
        if (controllerSyncViews.isNotEmpty()) {
            playerView.isClickable = true
            playerView.isFocusable = true
            playerView.setOnClickListener {
                if (!isFullscreen) return@setOnClickListener
                val target = if (controllerOverlayVisible) View.GONE else View.VISIBLE
                controllerSyncViews.forEach { v ->
                    if (v.visibility != target) v.visibility = target
                }
                controllerOverlayVisible = !controllerOverlayVisible
            }
        }
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
        // v1.5.9: hide system bars (status + navigation) in fullscreen
        // and extend edge-to-edge. Previously, the host ScrollView's
        // fitsSystemWindows=true left a status-bar-shaped gap at the
        // top of the player even in landscape, so the player never
        // filled the screen. We now:
        //  1. Set the host activity to edge-to-edge (WindowCompat
        //     setDecorFitsSystemWindows(false)) so the window covers
        //     the full display.
        //  2. Hide system bars via WindowInsetsControllerCompat
        //     (IMMERSIVE_BEHAVIOR so they auto-show on swipe).
        //  3. Disable fitsSystemWindows on the hostView + its parent
        //     so child views don't apply insets as padding.
        // All three are restored on exit.
        if (activity != null) {
            val window = activity.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, !isFullscreen)
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                if (isFullscreen) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        // v1.5.10: walk the ENTIRE ancestor chain (hostView →
        // parent → ... → android.R.id.content) and clear
        // fitsSystemWindows + padding on each View. Without this, a
        // ScrollView that sits between the player and the content
        // root keeps its top padding (status bar inset) even when
        // its child's fitsSystemWindows is false, leaving the player
        // stuck below the status bar.
        if (isFullscreen) {
            savedAncestorStates.clear()
            var node: View? = hostView
            val rootView = hostView.rootView
            while (node != null && node !== rootView) {
                savedAncestorStates.add(
                    SavedInsetState(
                        view = node,
                        fitsSystemWindows = node.fitsSystemWindows,
                        paddingTop = node.paddingTop,
                        paddingBottom = node.paddingBottom,
                        paddingLeft = node.paddingLeft,
                        paddingRight = node.paddingRight,
                    )
                )
                node.fitsSystemWindows = false
                node.setPadding(0, 0, 0, 0)
                node = node.parent as? View
            }
            // Also clear the root view's fitsSystemWindows + padding
            // so the very top-level FrameLayout (android.R.id.content)
            // doesn't reserve space for the status bar.
            rootView.fitsSystemWindows = false
        } else {
            // Restore in reverse order so each view's parent is
            // already restored when the view itself is restored
            // (matters for layout pass ordering).
            savedAncestorStates.asReversed().forEach { state ->
                state.view.fitsSystemWindows = state.fitsSystemWindows
                state.view.setPadding(
                    state.paddingLeft,
                    state.paddingTop,
                    state.paddingRight,
                    state.paddingBottom,
                )
            }
            savedAncestorStates.clear()
            // Restore host's saved chain flag for compatibility with
            // the old v1.5.9 fields (unused but kept for clarity).
            hostView.fitsSystemWindows = savedHostFitsSystemWindows
        }

        // Hide/show non-player UI elements.
        // v1.6.4: [hideOnFullscreen] views (toolbar) always go GONE in
        // fullscreen and VISIBLE outside. [controllerSyncViews] follow
        // [controllerOverlayVisible] when IN fullscreen (initially
        // true → VISIBLE; tap playerView to toggle). When exiting
        // fullscreen we always restore them to VISIBLE regardless of
        // latch state — non-fullscreen is the "normal" layout per the
        // user's request "正常未全屏时，播放器就放在顶部".
        val hideVisibility = if (isFullscreen) View.GONE else View.VISIBLE
        hideOnFullscreen.forEach { it.visibility = hideVisibility }
        if (controllerSyncViews.isNotEmpty()) {
            val syncVisibility = if (isFullscreen && !controllerOverlayVisible) View.GONE else View.VISIBLE
            controllerSyncViews.forEach { v ->
                if (v.visibility != syncVisibility) {
                    v.visibility = syncVisibility
                }
            }
        }

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
     *      above its own child controller layer.
     *   2. Fullscreen height didn't fill the screen because
     *      displayMetrics.heightPixels includes the status bar inset
     *      but not the navigation bar inset on devices with gesture
     *      nav — leaving a gap at the top or bottom.
     *
     * v1.5.12 ROOT-CAUSE FIX: previously we queried
     * `windowManager.maximumWindowMetrics.bounds.height()` SYNCHRONOUSLY
     * right after calling `activity.requestedOrientation = LANDSCAPE`.
     * But orientation change is ASYNC — the system doesn't actually
     * rotate the display until the next frame. So maximumWindowMetrics
     * still returned the PORTRAIT height (e.g. 2400px). We'd write
     * playerView.layoutParams.height = 2400, then the rotation would
     * complete and the screen would be in landscape (1080px wide), but
     * the playerView would be 2400px tall — taller than the screen —
     * and ScrollView would let the user "scroll" past the bottom of
     * the player. That's the "全屏视频还是有问题" the user kept
     * reporting through v1.5.10 and v1.5.11.
     *
     * Fix: schedule the height apply on the NEXT frame via
     * `hostView.post { ... }`. By then the orientation change has
     * been applied (the system processes orientation changes in the
     * next Choreographer frame). We also register an
     * OnLayoutChangeListener on hostView that RE-APPLIES the height
     * whenever the layout changes while we're in fullscreen — this
     * catches cases where the rotation takes 2 frames or where the
     * user enters fullscreen before the orientation request was
     * even processed.
     */
    private fun applyHeight(
        view: View?,
        save: (Int) -> Unit,
        restore: () -> Int,
    ) {
        if (view == null) return
        val params = view.layoutParams
        if (isFullscreen) {
            // v1.6.4: save the original height regardless of value.
            // The previous `if (params.height > 0) save(...)` skipped
            // MATCH_PARENT (-1) and WRAP_CONTENT (-2), so on exit the
            // restore path fell back to DEFAULT_PLAYER_HEIGHT_DP (200dp)
            // — the playerView shrank to 200dp and sat at the top of
            // the videoContainer, producing the "退出全屏后播放器在上面"
            // effect the user reported. Now we save the raw value and
            // restore it directly (could be -1, -2, or a positive px).
            save(params.height)
            // v1.5.12: defer the actual height assignment to next
            // frame. We set MATCH_PARENT width now (works in any
            // orientation) but defer height — querying
            // maximumWindowMetrics before rotation completes gives
            // the wrong value (portrait height instead of landscape
            // height).
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            view.layoutParams = params
            // Schedule height computation on next frame.
            view.post { applyFullscreenHeightNow(view) }
        } else {
            // Detach the layout listener (we're exiting fullscreen).
            view.removeOnLayoutChangeListener(layoutChangeListener)
            // v1.6.4: restore the exact saved height. The saved value
            // is whatever was stored at enter-fullscreen time — could
            // be -1 (MATCH_PARENT, the typical XML default), -2
            // (WRAP_CONTENT), or a positive pixel value. We no longer
            // fall back to DEFAULT_PLAYER_HEIGHT_DP since that
            // produced the wrong height for MATCH_PARENT layouts.
            val saved = restore()
            params.height = saved
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            view.layoutParams = params
        }
    }

    /**
     * v1.5.12: the actual height assignment, called from
     * `hostView.post { ... }` after the orientation change has
     * propagated. We register an OnLayoutChangeListener afterwards
     * so that any SUBSEQUENT layout passes (e.g. system bars
     * animating in/out) re-trigger this method and keep the
     * playerView filling the screen.
     */
    private fun applyFullscreenHeightNow(view: View) {
        if (!isFullscreen) return  // user exited fullscreen before post fired
        val fullscreenHeight = computeFullscreenHeight()
        val params = view.layoutParams
        params.height = fullscreenHeight
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        view.layoutParams = params
        // v1.5.12: re-apply on any future layout change (e.g.
        // orientation finishing 2 frames later, keyboard opening,
        // system bars showing). The listener re-checks isFullscreen
        // and calls back into [applyFullscreenHeightNow].
        view.addOnLayoutChangeListener(layoutChangeListener)
    }

    /**
     * v1.5.12: shared layout change listener that re-applies the
     * fullscreen height when the layout changes while we're in
     * fullscreen. We use a single instance so we can remove it
     * cleanly on exit via `removeOnLayoutChangeListener`.
     */
    private val layoutChangeListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        if (isFullscreen) {
            val expected = computeFullscreenHeight()
            val cur = v.layoutParams.height
            if (cur != expected) {
                val params = v.layoutParams
                params.height = expected
                v.layoutParams = params
            }
        }
    }

    /**
     * v1.5.12: computes the fullscreen height in pixels using the
     * CURRENT window state. maximumWindowMetrics reflects the
     * current orientation (after rotation completes), so calling
     * this on the post-rotation frame gives the correct landscape
     * height.
     */
    private fun computeFullscreenHeight(): Int {
        return runCatching {
            val wm = hostView.context.getSystemService(Context.WINDOW_SERVICE)
                as WindowManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // currentWindowMetrics reflects what the user
                // actually sees right now (post-rotation). Using
                // maximumWindowMetrics gives the max POSSIBLE size
                // which on a foldable can differ — but on phones
                // currentWindowMetrics is the right call after the
                // rotation completes.
                wm.currentWindowMetrics.bounds.height()
            } else {
                @Suppress("DEPRECATION")
                hostView.resources.displayMetrics.heightPixels
            }
        }.getOrDefault(hostView.resources.displayMetrics.heightPixels)
    }

    private fun attachSpeedButton(button: View) {
        button.setOnClickListener { view ->
            val p = player ?: return@setOnClickListener
            val currentSpeed = p.playbackParameters.speed
            // v1.6.4 rev2: replace PopupMenu with MaterialAlertDialogBuilder.
            // The user said "切倍速的菜单太生硬了，优化一下UI". PopupMenu
            // had no rounded corners, no animation, and rendered as a
            // tiny anchored window that looked like a context menu from
            // Android 4.x. MaterialAlertDialogBuilder gives us:
            //  - Rounded corners (Material 3 default 28dp)
            //  - Smooth fade-in/scale animation (system Material default)
            //  - Wider, more readable list rows (Material list item height)
            //  - A title "播放速度" so the dialog's purpose is obvious
            //  - Standard OK/Cancel button row (we omit since tap = apply)
            //  - Theme-aware colors (works in light/dark)
            //
            // The labels use [formatSpeed] so 1.0 → "1x", 1.5 → "1.5x"
            // (v1.6.4 fix). The current speed is pre-selected; tapping
            // any item immediately applies + dismisses.
            val labels = SPEEDS.map { formatSpeed(it) }.toTypedArray()
            val checkedIdx = SPEEDS.indexOfFirst { it == currentSpeed }
                .coerceAtLeast(0)
            MaterialAlertDialogBuilder(view.context)
                .setTitle("播放速度")
                .setSingleChoiceItems(labels, checkedIdx) { dialog, which ->
                    val speed = SPEEDS[which]
                    p.playbackParameters = PlaybackParameters(speed)
                    if (button is android.widget.TextView) {
                        button.text = formatSpeed(speed)
                    }
                    dialog.dismiss()
                }
                .show()
        }
    }

    companion object {
        private val SPEEDS = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)
        private const val DEFAULT_PLAYER_HEIGHT_DP = 200

        private fun formatSpeed(speed: Float): String {
            // v1.6.4 fix: the previous `else "${speed.toInt()}x"` branch
            // truncated 1.5f to 1, so both 1.0x and 1.5x displayed as "1x"
            // in the popup. Now we strip the trailing ".0" only for integer
            // speeds (1.0 -> "1x", 2.0 -> "2x") and keep the fractional part
            // for half-step speeds (0.5 -> "0.5x", 1.5 -> "1.5x").
            return if (speed == speed.toInt().toFloat()) "${speed.toInt()}x"
                   else "${speed}x"
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
