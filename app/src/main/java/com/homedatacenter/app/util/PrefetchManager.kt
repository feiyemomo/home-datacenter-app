package com.homedatacenter.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Prefetches data into CacheManager in the background so that
 * fragments can display cached data instantly when the user navigates.
 *
 * Prefetch triggers:
 *   - DashboardFragment loads → prefetch cameras.list, devices.list
 *   - CamerasFragment loads → prefetch ICE config
 *   - ProfileFragment loads → prefetch devices.list
 *
 * Usage:
 *   PrefetchManager.getInstance(context).prefetch("cameras.list") {
 *       repository.listCameras(token)
 *   }
 */
class PrefetchManager private constructor(context: Context) {

    private val cacheManager = CacheManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "PrefetchManager"

        @Volatile
        private var instance: PrefetchManager? = null

        fun getInstance(context: Context): PrefetchManager {
            return instance ?: synchronized(this) {
                instance ?: PrefetchManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Immediately prefetch data and cache it.
     * @param key Cache key (e.g. "cameras.list")
     * @param fetcher Suspend function that fetches the data
     */
    fun prefetch(key: String, fetcher: suspend () -> Any?) {
        scope.launch {
            try {
                val data = fetcher()
                if (data != null) {
                    cacheManager.set(key, data)
                    Log.d(TAG, "Prefetched: $key")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch failed for $key: ${e.message}")
            }
        }
    }

    /**
     * Prefetch data when the main thread is idle, with an optional delay.
     * @param key Cache key
     * @param fetcher Suspend function that fetches the data
     * @param idleMs Delay before prefetching (default 2000ms)
     */
    fun prefetchOnIdle(key: String, fetcher: suspend () -> Any?, idleMs: Long = 2000L) {
        mainHandler.postDelayed({
            prefetch(key, fetcher)
        }, idleMs)
    }

    /**
     * Cancel all pending idle prefetches.
     */
    fun cancelPending() {
        mainHandler.removeCallbacksAndMessages(null)
    }
}