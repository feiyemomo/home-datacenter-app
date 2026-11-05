package com.homedatacenter.app.ui.glass

import android.app.Activity
import android.app.Dialog
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.homedatacenter.app.util.ThemeManager

object GlassActivityHelper {
    /**
     * Attaches a live AmbientFluidBackground to the lowest layer of an Activity.
     * Automatically clears the root view background so the liquid glass shines through.
     */
    @JvmStatic
    fun attachFluidBackground(activity: Activity) {
        activity.window.setBackgroundDrawableResource(android.R.color.transparent)
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val fluidBg = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setContent {
                val preset by ThemeManager.themePresetFlow.collectAsState()
                ProvideGlassTheme(preset = preset) {
                    AmbientFluidBackground(
                        modifier = Modifier.fillMaxSize(),
                        preset = preset
                    )
                }
            }
        }
        content.addView(fluidBg, 0)
        if (content.childCount > 1) {
            content.getChildAt(1).background = null
        }
    }

    /**
     * Attaches a live AmbientFluidBackground to a full-screen Dialog.
     */
    @JvmStatic
    fun attachDialogFluidBackground(dialog: Dialog) {
        val window = dialog.window ?: return
        window.setBackgroundDrawableResource(android.R.color.transparent)
        val decor = window.decorView as? ViewGroup ?: return

        val fluidBg = ComposeView(dialog.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                val preset by ThemeManager.themePresetFlow.collectAsState()
                ProvideGlassTheme(preset = preset) {
                    AmbientFluidBackground(
                        modifier = Modifier.fillMaxSize(),
                        preset = preset
                    )
                }
            }
        }
        decor.addView(fluidBg, 0)
        if (decor.childCount > 1) {
            decor.getChildAt(1).background = null
        }
    }
}
