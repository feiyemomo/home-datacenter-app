package com.homedatacenter.app.ui.splash

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.BuildConfig
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.ApiResponse
import com.homedatacenter.app.data.model.AlertListData
import com.homedatacenter.app.databinding.ActivitySplashBinding
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.ui.login.LoginActivity
import com.homedatacenter.app.ui.main.MainActivity
import com.homedatacenter.app.util.CacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Brand splash screen. Plays a short logo entrance animation
 * (fade + gentle scale pop, ~500ms) then, after a brief hold
 * (~900ms total), routes to MainActivity when the user is logged
 * in or LoginActivity otherwise. The cold-start frame before this
 * activity renders is covered by the themed window background
 * (splash_background.xml) so there is no white/black flash.
 *
 * For logged-in users we also kick off a parallel prefetch of the
 * Dashboard first-screen data (system status, weather, network status,
 * recent alerts, camera list) so that by the time the user lands on the
 * Dashboard the cache is already warm. Non-blocking coroutine orchestration
 * waits for the entrance animation (900ms minimum) and prefetch jobs
 * (up to [MAX_SPLASH_MS] 2000ms hard cap) without blocking the main UI thread.
 * Data that didn't make it in time will be loaded normally by the fragment's
 * own fallback path.
 *
 * Logged-out users skip prefetch entirely and keep the original
 * 900ms fixed delay.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var navigated = false

    // Prefetch coroutines launched in onCreate so they run in
    // parallel with the logo entrance animation. Stored so
    // routeToNext can await them with a timeout. Empty when the
    // user is not logged in (no prefetch).
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJobs: List<Job> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text =
            getString(R.string.splash_version_format, BuildConfig.VERSION_NAME)

        if (savedInstanceState != null) {
            // Recreated mid-splash (rotation / theme change): skip the
            // entrance animation and route immediately to avoid a
            // duplicated navigation. Also skip prefetch — the previous
            // instance already started it (or already routed).
            routeToNext()
            return
        }

        playLogoEntrance()

        // Kick off prefetch as early as possible so it overlaps with
        // the 500ms entrance animation + brand hold window.
        // The fetcher coroutines write directly to CacheManager; we
        // collect their Jobs so the orchestrator can await them.
        val container = (application as HomeCenterApp).container
        val token = container.prefsManager.token
        val isLoggedIn = container.prefsManager.isLoggedIn() && !token.isNullOrEmpty()
        if (isLoggedIn) {
            prefetchJobs = startDashboardPrefetch(container, token!!)
        }

        // v1.10.1: Non-blocking splash orchestration via lifecycleScope.
        // Replaces the previous postDelayed + runBlocking pattern which
        // could freeze the main looper for up to 1100ms.
        //
        // 1. minHoldJob: ensures brand animation stays visible for at least
        //    SPLASH_DURATION_MS (900ms).
        // 2. prefetchWait: awaits prefetchJobs up to MAX_SPLASH_MS (2000ms).
        // 3. Joins both without blocking the Android UI thread.
        lifecycleScope.launch {
            val minHoldJob = launch { delay(SPLASH_DURATION_MS) }
            if (isLoggedIn && prefetchJobs.isNotEmpty()) {
                withTimeoutOrNull(MAX_SPLASH_MS) {
                    prefetchJobs.joinAll()
                }
            }
            minHoldJob.join()
            routeToNext()
        }
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

    /**
     * Launch parallel prefetch coroutines for the Dashboard first
     * screen. Each coroutine fetches one resource via the repository
     * or the raw API, then writes the result into [CacheManager] using
     * the SAME keys that [DashboardFragment] reads from cache, so the
     * fragment's "cached-first" path finds the prefetched data
     * instantly and skips the loading spinner.
     *
     * Cache keys (must match DashboardFragment exactly):
     *  - "dashboard.status"  → SystemStatus
     *  - "dashboard.weather" → WeatherResponse
     *  - "network.status"    → NetworkStatus
     *  - "dashboard.alerts"  → List<Alert>
     *  - "cameras.list"      → List<Camera>  (consumed by CamerasFragment's prefetch path)
     *
     * Each coroutine catches its own exceptions — one failed fetch
     * must not cancel the others (SupervisorJob + per-job try/catch).
     *
     * @return list of Jobs (one per prefetch) for the orchestrator to await.
     */
    private fun startDashboardPrefetch(
        container: AppContainer,
        token: String,
    ): List<Job> {
        val repository = container.getRepository()
        val api = container.getApi()
        val cacheManager = CacheManager.getInstance(this)
        val bearer = "Bearer $token"

        return listOf(
            prefetchScope.launch {
                try {
                    container.tryAutoRefreshToken(force = true)?.join()
                    Log.d(TAG, "Prefetched token auto-refresh")
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch token refresh failed: ${e.message}")
                }
            },
            prefetchScope.launch {
                try {
                    val status = repository.getSystemStatus(token, useCache = true)
                    cacheManager.set("dashboard.status", status)
                    Log.d(TAG, "Prefetched dashboard.status")
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch dashboard.status failed: ${e.message}")
                }
            },
            prefetchScope.launch {
                try {
                    val weather = repository.getWeather(token)
                    if (weather != null) {
                        cacheManager.set("dashboard.weather", weather)
                        Log.d(TAG, "Prefetched dashboard.weather")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch dashboard.weather failed: ${e.message}")
                }
            },
            prefetchScope.launch {
                try {
                    val netStatus = repository.getNetworkStatus(token, refresh = false)
                    cacheManager.set("network.status", netStatus)
                    Log.d(TAG, "Prefetched network.status")
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch network.status failed: ${e.message}")
                }
            },
            prefetchScope.launch {
                try {
                    val resp = api.listAlerts(bearer, limit = 10)
                    val alerts = if (resp.isSuccess) {
                        resp.decodeData<AlertListData>()?.alerts ?: emptyList()
                    } else {
                        emptyList()
                    }
                    cacheManager.set("dashboard.alerts", alerts)
                    Log.d(TAG, "Prefetched dashboard.alerts (${alerts.size})")
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch dashboard.alerts failed: ${e.message}")
                }
            },
            prefetchScope.launch {
                try {
                    val cameras = repository.listCameras(token, useCache = true)
                    cacheManager.set("cameras.list", cameras)
                    Log.d(TAG, "Prefetched cameras.list (${cameras.size})")
                    // v1.10.1: warm top 3 online cameras to avoid connection pool starvation.
                    // Fire-and-forget — launched on fresh child jobs so joinAll()
                    // never blocks on them.
                    for (cam in cameras.filter { it.isOnline }.take(3)) {
                        prefetchScope.launch {
                            repository.preheatCamera(token, cam.id)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch cameras.list failed: ${e.message}")
                }
            },
            // v1.8.21: check for app updates in parallel with the
            // dashboard prefetch.
            prefetchScope.launch {
                try {
                    container.checkUpdateOnStartup()
                    Log.d(TAG, "Update check completed")
                } catch (e: Exception) {
                    Log.w(TAG, "Update check failed: ${e.message}")
                }
            },
        )
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

    override fun onDestroy() {
        // Rotation / recreation mid-splash would otherwise leave the old
        // instance's prefetch jobs queued on Dispatchers.IO and let the
        // new instance start a duplicate prefetch. Cancelling the scope
        // here stops any still-running or queued prefetch work.
        prefetchScope.cancel()
        super.onDestroy()
    }

    private companion object {
        /** Total splash duration: 500ms entrance + 400ms hold. */
        const val SPLASH_DURATION_MS = 900L

        /** Hard cap on total splash time when prefetch is running. */
        const val MAX_SPLASH_MS = 2000L

        private const val TAG = "SplashActivity"
    }
}
