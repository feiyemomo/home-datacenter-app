package com.homedatacenter.app.ui.splash

import android.content.Intent
import android.os.Bundle
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.homedatacenter.app.BuildConfig
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.databinding.ActivitySplashBinding
import com.homedatacenter.app.ui.login.LoginActivity
import com.homedatacenter.app.ui.main.MainActivity

/**
 * Brand splash screen. Plays a short logo entrance animation
 * (fade + gentle scale pop, ~500ms) then, after a brief hold
 * (~900ms total), routes to MainActivity when the user is logged
 * in or LoginActivity otherwise. The cold-start frame before this
 * activity renders is covered by the themed window background
 * (splash_background.xml) so there is no white/black flash.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text =
            getString(R.string.splash_version_format, BuildConfig.VERSION_NAME)

        if (savedInstanceState != null) {
            // Recreated mid-splash (rotation / theme change): skip the
            // entrance animation and route immediately to avoid a
            // duplicated navigation.
            routeToNext()
            return
        }

        playLogoEntrance()

        // Keep the splash on screen for the full entrance + a short
        // hold, then hand off. postDelayed keeps onCreate non-blocking.
        binding.root.postDelayed({ routeToNext() }, SPLASH_DURATION_MS)
    }

    /** Fade the logo in while scaling it from 90% to 100% (500ms pop). */
    private fun playLogoEntrance() {
        binding.logo.alpha = 0f
        binding.logo.scaleX = 0.9f
        binding.logo.scaleY = 0.9f
        binding.logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    private fun routeToNext() {
        if (navigated || isFinishing) return
        navigated = true
        val container = (application as HomeCenterApp).container
        val target = if (container.prefsManager.isLoggedIn()) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, LoginActivity::class.java)
        }
        startActivity(target)
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }

    private companion object {
        /** Total splash duration: 500ms entrance + 400ms hold. */
        const val SPLASH_DURATION_MS = 900L
    }
}
