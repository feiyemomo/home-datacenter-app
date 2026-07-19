package com.homedatacenter.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.homedatacenter.app.di.AppContainer

/**
 * Listens for Android network changes (WiFi connects/disconnects,
 * cellular handoff, validated-internet state changes) and forces
 * [AppContainer.baseUrlResolver] to re-probe immediately so the app
 * switches between LAN (http://192.168.31.234:8088/) and remote
 * (https://api.feiyemomo.top/) within seconds instead of waiting for
 * the 5-minute TTL in BaseUrlResolver.
 *
 * Registered in HomeCenterApp.onCreate() with the application context
 * — never unregistered (process-lifetime). The callback is fast and
 * idempotent: forceProbe() is a no-op if a probe is already in flight.
 *
 * Why NetworkCallback instead of CONNECTIVITY_ACTION broadcast:
 *  - The broadcast is deprecated since API 24 (N); NetworkCallback is
 *    the supported path.
 *  - onAvailable fires per-network, but we only want the *default*
 *    network changes. Using a NetworkRequest with
 *    NET_CAPABILITY_INTERNET gives us the default-network callbacks
 *    (onAvailable = default network changed, onLost = default network
 *    lost, onCapabilitiesChanged = validation/transport changed).
 *
 * Why no debouncing: ConnectivityManager already coalesces duplicate
 * callbacks within ~100ms, and forceProbe() short-circuits if a probe
 * is already running. A second onCapabilitiesChanged during an
 * in-flight probe is a no-op. Real-world handoff events (WiFi → LTE)
 * fire onLost(WiFi) + onAvailable(LTE) within ~1s; both trigger the
 * same re-probe, but only the first runs; the second is a no-op.
 *
 * Threading: callbacks arrive on a system binder thread. forceProbe()
 * spawns its own daemon thread, so we never block the binder thread.
 */
class NetworkChangeMonitor(
    private val context: Context,
    private val container: AppContainer,
) {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A new default network just came up (e.g. WiFi just
            // connected, or cellular became the default after WiFi
            // dropped). Re-probe — if we just landed on the home WiFi
            // the LAN URL will now be reachable and we want to switch
            // to it immediately so the next API call is fast.
            Log.i(TAG, "onAvailable: default network changed, forcing re-probe")
            container.baseUrlResolver.forceProbe()
        }

        override fun onLost(network: Network) {
            // Default network lost (e.g. WiFi disconnected, no
            // fallback yet). Re-probe — if LAN was our resolved URL
            // we want to fall back to remote ASAP so the next API
            // call doesn't time out against the now-unreachable LAN.
            Log.i(TAG, "onLost: default network lost, forcing re-probe")
            container.baseUrlResolver.forceProbe()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            // Capabilities changed — most commonly the network just
            // became validated (NET_CAPABILITY_VALIDATED) or switched
            // transports (WiFi → cellular). Re-probe to pick up the
            // new state. This is important because onAvailable fires
            // *before* validation completes — at that point the LAN
            // probe might fail because the route isn't yet usable.
            // onCapabilitiesChanged with VALIDATED is the signal that
            // the network is actually ready to carry traffic.
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                Log.i(TAG, "onCapabilitiesChanged: network validated, forcing re-probe")
                container.baseUrlResolver.forceProbe()
            }
        }
    }

    /**
     * Registers the system NetworkCallback. Safe to call once at app
     * startup; the callback is process-lifetime (no unregister).
     * No-op if ConnectivityManager is unavailable (older devices,
     * headless tests).
     */
    fun register() {
        if (registered) return
        val cm = connectivityManager ?: run {
            Log.w(TAG, "ConnectivityManager unavailable — network change detection disabled")
            return
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        registered = true
        Log.i(TAG, "Registered NetworkCallback")
    }

    /**
     * Unregisters the callback. Only call on app teardown — in
     * practice never called because the process is killed before
     * Application.onTerminate fires. Provided for completeness.
     */
    fun unregister() {
        if (!registered) return
        val cm = connectivityManager ?: return
        cm.unregisterNetworkCallback(callback)
        registered = false
    }

    companion object {
        private const val TAG = "NetworkChangeMonitor"
    }
}
