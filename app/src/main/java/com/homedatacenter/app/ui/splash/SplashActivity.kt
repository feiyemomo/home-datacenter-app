package com.homedatacenter.app.ui.splash

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.BuildConfig
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.AlertListData
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.ui.glass.AmbientFluidBackground
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.ui.login.LoginActivity
import com.homedatacenter.app.ui.main.MainActivity
import com.homedatacenter.app.util.ThemeManager
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
 * then, after a brief hold (~900ms total), routes to MainActivity
 * when the user is logged in or LoginActivity otherwise.
 */
class SplashActivity : ComponentActivity() {

    private var navigated = false
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJobs: List<Job> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val versionText = getString(R.string.splash_version_format, BuildConfig.VERSION_NAME)

        setContent {
            val preset by ThemeManager.themePresetFlow.collectAsState()
            ProvideGlassTheme(preset = preset) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AmbientFluidBackground(
                        preset = preset,
                        modifier = Modifier.fillMaxSize()
                    )
                    SplashPageContent(versionText = versionText)
                }
            }
        }

        if (savedInstanceState != null) {
            routeToNext()
            return
        }

        val container = (application as HomeCenterApp).container
        val token = container.prefsManager.token
        val isLoggedIn = container.prefsManager.isLoggedIn() && !token.isNullOrEmpty()
        if (isLoggedIn) {
            prefetchJobs = startDashboardPrefetch(container, token!!)
        }

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

    private fun startDashboardPrefetch(
        container: AppContainer,
        token: String
    ): List<Job> {
        val repository = container.getRepository()
        val cacheManager = com.homedatacenter.app.util.CacheManager.getInstance(applicationContext)
        val api = container.getApi()

        val tokenRefreshJob = prefetchScope.launch {
            try {
                container.prefsManager.userId?.let { uid ->
                    container.prefsManager.accessKey?.let { key ->
                        val lastRefresh = container.prefsManager.lastTokenRefreshTime
                        val now = System.currentTimeMillis()
                        val hourMs = 60 * 60 * 1000L
                        if (now - lastRefresh > hourMs) {
                            val refreshed = repository.bind(uid, key)
                            container.prefsManager.token = refreshed
                            container.prefsManager.lastTokenRefreshTime = now
                            Log.d(TAG, "Prefetch: refreshed token silently")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch token refresh failed, proceeding with current: ${e.message}")
            }
        }

        fun currentToken() = container.prefsManager.token ?: token

        return listOf(
            prefetchScope.launch {
                try {
                    tokenRefreshJob.join()
                    val curToken = currentToken()
                    val status = repository.getSystemStatus(curToken, useCache = false, refreshCache = true)
                    cacheManager.set("dashboard.status", status)
                    Log.d(TAG, "Prefetched dashboard.status")
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch dashboard.status failed: ${e.message}")
                }
            },
            prefetchScope.launch {
                try {
                    tokenRefreshJob.join()
                    val curToken = currentToken()
                    val weather = repository.getWeather(curToken)
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
                    tokenRefreshJob.join()
                    val curToken = currentToken()
                    val netStatus = repository.getNetworkStatus(curToken, refresh = false)
                    cacheManager.set("network.status", netStatus)
                    Log.d(TAG, "Prefetched network.status")
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch network.status failed: ${e.message}")
                }
            },
            prefetchScope.launch {
                try {
                    tokenRefreshJob.join()
                    val curToken = currentToken()
                    val resp = api.listAlerts("Bearer $curToken", limit = 10)
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
                    tokenRefreshJob.join()
                    val curToken = currentToken()
                    val cameras = repository.listCameras(curToken, useCache = true)
                    cacheManager.set("cameras.list", cameras)
                    Log.d(TAG, "Prefetched cameras.list (${cameras.size})")
                    for (cam in cameras.filter { it.isOnline }.take(3)) {
                        container.warmScope.launch {
                            try {
                                repository.preheatCamera(container.prefsManager.token ?: curToken, cam.id)
                                Log.d(TAG, "Preheated camera ${cam.id}")
                            } catch (e: Exception) {
                                Log.w(TAG, "Preheat camera ${cam.id} failed: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch cameras.list failed: ${e.message}")
                }
            },
            prefetchScope.launch {
                try {
                    tokenRefreshJob.join()
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
        prefetchScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val SPLASH_DURATION_MS = 900L
        const val MAX_SPLASH_MS = 2000L
        private const val TAG = "SplashActivity"
    }
}
