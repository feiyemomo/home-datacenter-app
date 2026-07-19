package com.homedatacenter.app.util

import android.view.View
import android.view.animation.AnimationUtils
import com.homedatacenter.app.R

object AnimationHelper {

    fun scalePress(view: View) {
        val anim = AnimationUtils.loadAnimation(view.context, R.anim.scale_down)
        view.startAnimation(anim)
    }

    fun scaleRelease(view: View) {
        val anim = AnimationUtils.loadAnimation(view.context, R.anim.scale_up)
        view.startAnimation(anim)
    }

    fun slideInBottom(view: View, delay: Long = 0) {
        val anim = AnimationUtils.loadAnimation(view.context, R.anim.slide_in_bottom)
        anim.startOffset = delay
        view.startAnimation(anim)
    }

    fun fadeIn(view: View, duration: Long = 300) {
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }
}
