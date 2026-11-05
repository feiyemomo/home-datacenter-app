package com.homedatacenter.app.ui.network

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.data.model.NetworkStatus
import com.homedatacenter.app.data.model.ServerEndpoint
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.ui.glass.AmbientFluidBackground
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.NetworkPathPreference
import com.homedatacenter.app.util.ThemeManager
import kotlinx.coroutines.launch

/**
 * Network detail page — Pure Compose Liquid Glass implementation.
 *
 * Renders the full NetworkStatus (NAT / P2P / Relay / quality / strategy),
 * P2P server endpoint, and MQTT / WebSocket status from SystemStatus.
 */
class NetworkDetailActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    private var clientPath by mutableStateOf("")
    private var isProbing by mutableStateOf(false)
    private var preference by mutableStateOf(NetworkPathPreference.AUTO)
    private var networkStatus by mutableStateOf<NetworkStatus?>(null)
    private var systemStatus by mutableStateOf<SystemStatus?>(null)
    private var serverEndpoint by mutableStateOf<ServerEndpoint?>(null)
    private var isRefreshing by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        container = (application as HomeCenterApp).container

        preference = container.baseUrlResolver.getPreference()
        clientPath = container.baseUrlResolver.currentMethodLabel()

        container.baseUrlResolver.onProbeStateChanged = { inProgress ->
            runOnUiThread {
                isProbing = inProgress
                if (!inProgress) {
                    clientPath = container.baseUrlResolver.currentMethodLabel()
                }
            }
        }

        renderFromCache()
        loadAll(forceRefresh = false)

        setContent {
            val preset by ThemeManager.themePresetFlow.collectAsState()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !preset.isDark
            }
            ProvideGlassTheme(preset = preset) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(preset.fluidBaseColor)
                ) {
                    AmbientFluidBackground(
                        preset = preset,
                        modifier = Modifier.fillMaxSize()
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(WindowInsets.statusBars.asPaddingValues())
                    ) {
                        NetworkDetailPageContent(
                            clientPath = clientPath,
                            isProbing = isProbing,
                            preference = preference,
                            onPreferenceChange = { newPref ->
                                preference = newPref
                                container.baseUrlResolver.setPreference(newPref)
                                clientPath = container.baseUrlResolver.currentMethodLabel()
                            },
                            networkStatus = networkStatus,
                            systemStatus = systemStatus,
                            serverEndpoint = serverEndpoint,
                            isRefreshing = isRefreshing,
                            onRefresh = {
                                loadAll(forceRefresh = true)
                                clientPath = container.baseUrlResolver.currentMethodLabel()
                            },
                            onBackClick = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        preference = container.baseUrlResolver.getPreference()
        clientPath = container.baseUrlResolver.currentMethodLabel()
    }

    override fun onDestroy() {
        super.onDestroy()
        container.baseUrlResolver.onProbeStateChanged = null
    }

    private fun renderFromCache() {
        networkStatus = container.getRepository().getCachedNetworkStatus()
        container.prefsManager.cachedSystemStatus?.let { raw ->
            try {
                systemStatus = container.getRepository().decodeSystemStatus(raw)
            } catch (_: Exception) {}
        }
    }

    private fun loadAll(forceRefresh: Boolean) {
        val token = container.prefsManager.token ?: return

        lifecycleScope.launch {
            isRefreshing = true
            try {
                val net = container.getRepository().getNetworkStatus(
                    token,
                    refresh = forceRefresh,
                )
                networkStatus = net
            } catch (_: Exception) {}

            try {
                val sys = container.getRepository().getSystemStatus(
                    token,
                    useCache = false,
                    refreshCache = true,
                )
                systemStatus = sys
            } catch (_: Exception) {}

            try {
                val ep = container.getRepository().getServerEndpoint(token)
                serverEndpoint = ep
            } catch (_: Exception) {
                serverEndpoint = null
            } finally {
                isRefreshing = false
            }
        }
    }
}
