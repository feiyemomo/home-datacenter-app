package com.homedatacenter.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors device network connectivity and exposes the online/offline
 * state as a StateFlow<Boolean> for reactive UI updates.
 *
 * Usage:
 *   val networkMonitor = NetworkMonitor.getInstance(context)
 *   networkMonitor.isOnline.observeAsState(initial = true) { online ->
 *       // React to connectivity changes
 *   }
 *   // Or in coroutines:
 *   networkMonitor.isOnline.collect { online ->
 *       // React
 *   }
 *
 * Unlike NetworkChangeMonitor (which triggers BaseUrlResolver re-probes),
 * this monitor focuses on the binary online/offline state for UI purposes
 * (showing/hiding offline banners, pausing polling, etc.).
 *
 * Registered in HomeCenterApp.onCreate() — never unregistered (process-lifetime).
 */
class NetworkMonitor private constructor(context: Context) {
    
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    
    private val _isOnline = MutableStateFlow(checkCurrentConnectivity())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
    
    private var registered = false
    
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "onAvailable: network available, updating state to online")
            updateState()
        }
        
        override fun onLost(network: Network) {
            Log.i(TAG, "onLost: network lost, updating state")
            updateState()
        }
        
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            val hasInternet = capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
            val validated = capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
            val online = hasInternet && validated
            Log.d(TAG, "onCapabilitiesChanged: internet=$hasInternet validated=$validated")
            _isOnline.value = online
        }
    }
    
    init {
        register()
    }
    
    /**
     * Registers the NetworkCallback. Safe to call multiple times.
     */
    fun register() {
        if (registered) return
        val cm = connectivityManager ?: run {
            Log.w(TAG, "ConnectivityManager unavailable")
            return
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        registered = true
        Log.i(TAG, "Registered network callback, initial state: ${_isOnline.value}")
    }
    
    /**
     * Synchronous check of current connectivity.
     */
    fun isOnlineNow(): Boolean = _isOnline.value
    
    /**
     * Force a re-check of current connectivity state.
     */
    fun forceCheck() {
        updateState()
    }
    
    private fun updateState() {
        _isOnline.value = checkCurrentConnectivity()
    }
    
    private fun checkCurrentConnectivity(): Boolean {
        val cm = connectivityManager ?: return true // assume online if unavailable
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    
    companion object {
        private const val TAG = "NetworkMonitor"
        
        @Volatile
        private var instance: NetworkMonitor? = null
        
        fun getInstance(context: Context): NetworkMonitor {
            return instance ?: synchronized(this) {
                instance ?: NetworkMonitor(context.applicationContext).also { instance = it }
            }
        }
        
        /**
         * Reset the singleton (for testing).
         */
        fun reset() {
            instance = null
        }
    }
}