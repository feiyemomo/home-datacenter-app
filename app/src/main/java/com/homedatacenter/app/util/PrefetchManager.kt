package com.homedatacenter.app.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Prefetches data into CacheManager in the background so that
 * fragments can display cached data instantly when the user navigates.
 *
 * Prefetch triggers:
 *   - CamerasFragment loads → prefetch ICE config
 *   - DashboardFragment loads → prefetch devices.list
 *
 * Lifecycle: pass a scoped [CoroutineScope] (e.g. the fragment's
 * viewLifecycleOwner.lifecycleScope) so the coroutine is cancelled when
 * the fragment is destroyed. When [scope] is null the internal app-wide
 * scope is used, which keeps prefetching regardless of screen state.
 *
 * Usage:
 *   PrefetchManager.getInstance(context).prefetch("cameras.ice", scope) {
 *       repository.getIceConfig(token)
 *   }
 */
class PrefetchManager private constructor(context: Context) {

    private val cacheManager = CacheManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
     * @param scope Optional lifecycle-scoped coroutine scope; when null the
     *   app-wide scope is used. Pass a fragment's lifecycleScope so the
     *   prefetch is cancelled on destroy.
     */
    fun prefetch(key: String, fetcher: suspend () -> Any?, scope: CoroutineScope? = null) {
        val target = scope ?: this.scope
        target.launch {
            try {
                val data = fetcher()
                if (data != null) {
                    cacheManager.set(key, data)
                    Log.d(TAG, "Prefetched: $key")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch failed for $key: ${e.message}")
            }
        }
    }

    /**
     * Prefetch data after a short delay. Uses a coroutine [delay] instead
     * of a Handler so the work is cancelled cleanly when [scope] dies.
     * @param key Cache key
     * @param fetcher Suspend function that fetches the data
     * @param idleMs Delay before prefetching (default 2000ms)
     * @param scope Optional lifecycle-scoped coroutine scope (see [prefetch]).
     */
    fun prefetchOnIdle(
        key: String,
        fetcher: suspend () -> Any?,
        idleMs: Long = 2000L,
        scope: CoroutineScope? = null,
    ) {
        val target = scope ?: this.scope
        target.launch {
            delay(idleMs)
            try {
                val data = fetcher()
                if (data != null) {
                    cacheManager.set(key, data)
                    Log.d(TAG, "Prefetched: $key")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch failed for $key: ${e.message}")
            }
        }
    }
}