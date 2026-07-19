package com.homedatacenter.app

import android.app.Application
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.util.NetworkChangeMonitor
import com.homedatacenter.app.util.PrefsManager
import com.homedatacenter.app.util.ThemeManager

class HomeCenterApp : Application() {

    lateinit var container: AppContainer
        private set

    private var networkMonitor: NetworkChangeMonitor? = null

    override fun onCreate() {
        super.onCreate()
        ThemeManager.init(this)
        container = AppContainer(this)
        // Synchronously probe the LAN (NAS) URL so the first API call
        // goes to the fast local endpoint when the device is on the
        // home network. Blocks up to 800ms — a TCP handshake to a
        // same-LAN host takes ~10ms, so 800ms is generous. If LAN is
        // unreachable we fall through to the Cloudflare Tunnel URL.
        // Skip when a user override is set (manual base URL pref).
        if (container.prefsManager.baseUrl.isNullOrBlank()) {
            container.baseUrlResolver.probeLanOnStartup()
            // Register for real-time network changes so WiFi ↔
            // cellular handoff triggers an immediate re-probe
            // instead of waiting for the 5-minute TTL. This makes
            // walking out of (or back into) home WiFi range pick up
            // the right URL within seconds rather than minutes.
            networkMonitor = NetworkChangeMonitor(this, container).also {
                it.register()
            }
        }
    }
}
