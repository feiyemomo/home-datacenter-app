package com.homedatacenter.app

import android.app.Application
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.util.NetworkChangeMonitor
import com.homedatacenter.app.util.PrefsManager
import com.homedatacenter.app.util.NetworkMonitor
import com.homedatacenter.app.util.ThemeManager

class HomeCenterApp : Application() {

    lateinit var container: AppContainer
        private set

    private var networkMonitor: NetworkChangeMonitor? = null

    override fun onCreate() {
        super.onCreate()
        ThemeManager.init(this)
        container = AppContainer(this)
        // Always register the NetworkChangeMonitor — it fires
        // forceProbe on every WiFi/cellular handoff, which is the
        // reliable signal for "network just changed, re-probe now".
        // We register it unconditionally (even when a user override
        // for baseUrl is set) because the monitor itself is cheap
        // and the resolver's forceProbe is a no-op when a user
        // override is in effect.
        networkMonitor = NetworkChangeMonitor(this, container).also {
            it.register()
        }
        // Initialize NetworkMonitor for UI state
        NetworkMonitor.getInstance(this)
        // Synchronously probe the LAN (NAS) URL only when the user
        // hasn't set a manual baseUrl override. probeLanOnStartup
        // now runs on background daemon threads (no main-thread
        // blocking) — the first API call may go to the remote URL
        // while the probe is in flight; once LAN is confirmed
        // reachable the resolver switches and AppContainer.resetApi
        // fires to rebuild the Retrofit instance against the LAN.
        if (container.prefsManager.baseUrl.isNullOrBlank()) {
            container.baseUrlResolver.probeLanOnStartup()
        }
        // v1.5.6: pre-warm the WebRTC PeerConnectionFactory + EGL
        // context on a background thread so the first camera detail
        // page opens with minimal first-frame latency. Safe to skip
        // when the user isn't logged in yet — LoginActivity will
        // call warmWebRtc() after successful auth.
        if (!container.prefsManager.token.isNullOrBlank()) {
            container.warmWebRtc()
            // v1.6.36: prefetch ICE config at app startup (previously
            // deferred to DashboardFragment.onResume). On remote
            // networks GET /api/v1/network/ice-config takes 1.4s+;
            // starting it here means by the time the user navigates
            // to any camera the config is already in memory + persisted
            // to PrefsManager. Idempotent — no-ops if already cached.
            container.prefetchIceConfig()
            // v1.6.11: silent background check for a new APK version.
            // Result is cached in AppContainer.cachedUpdateInfo —
            // SettingsFragment reads it to show a "new version
            // available" hint. We do NOT auto-prompt here (would
            // interrupt the user on every cold start).
            container.checkUpdateOnStartup()
        }
    }
}
