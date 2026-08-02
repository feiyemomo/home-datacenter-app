package com.homedatacenter.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import android.util.Log
import com.homedatacenter.app.data.api.NetworkFactory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Unified cache manager backed by SharedPreferences + in-memory LRU cache.
 *
 * Provides type-safe get/set via inline reified functions.
 *
 * ## Usage
 * ```kotlin
 * val cache = CacheManager.getInstance(context)
 *
 * // Store
 * cache.set("dashboard.status", systemStatus)
 *
 * // Retrieve (returns null if missing or expired)
 * val status = cache.get<SystemStatus>("dashboard.status", 30_000L)
 *
 * // Clear by prefix
 * cache.clear("cameras")
 *
 * // Invalidate everything
 * cache.invalidateAll()
 * ```
 *
 * ## Cache key naming convention
 * | Key | Description |
 * |---|---|
 * | `dashboard.status` | 系统状态 |
 * | `dashboard.weather` | 天气 |
 * | `cameras.list` | 摄像头列表 |
 * | `devices.list` | 设备列表 |
 * | `logs.page.{page}` | 日志分页 |
 * | `network.status` | 网络状态 |
 * | `profile.info` | 个人信息 |
 *
 * ## Storage design
 * - **Memory**: LRU cache with up to 100 entries, providing O(1) reads for hot data.
 * - **Disk**: Plain SharedPreferences. Each cache entry occupies two keys:
 *   `key` → raw JSON string, `key:ts` → timestamp (unix ms).
 *   Timestamps are stored separately to avoid the complexity of nested generic
 *   deserialization required by a single `{timestamp, data}` wrapper.
 */
class CacheManager private constructor(context: Context) {

    @PublishedApi internal val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @PublishedApi internal val memoryCache: LruCache<String, CachedEntry> =
        LruCache(MAX_MEMORY_ENTRIES)

    /** Pre-configured [Json] instance shared with the rest of the app. */
    @PublishedApi internal val json: Json = NetworkFactory.json

    companion object {
        @PublishedApi internal const val TAG = "CacheManager"
        private const val PREFS_NAME = "app_cache"
        @PublishedApi internal const val MAX_MEMORY_ENTRIES = 100
        /** Suffix appended to the key for storing the timestamp. */
        @PublishedApi internal const val TS_SUFFIX = ":ts"

        @Volatile
        private var instance: CacheManager? = null

        /**
         * Obtain the singleton [CacheManager]. The [context] is only used on
         * the first call (application context is stored internally).
         */
        @JvmStatic
        fun getInstance(context: Context): CacheManager {
            return instance ?: synchronized(this) {
                instance ?: CacheManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * In-memory entry holding the raw JSON string and the timestamp at which
     * it was written to disk. This avoids re-serializing on every memory hit.
     */
    @PublishedApi internal data class CachedEntry(
        val timestamp: Long,
        val jsonStr: String,
    )

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Retrieve a cached value.
     *
     * @param T The expected type. Must be a [kotlinx.serialization.Serializable] class.
     * @param key Cache key.
     * @param maxAgeMs Maximum age in milliseconds. Returns `null` if the
     *   cached entry is older than this threshold.
     * @return The deserialized value, or `null` if not found, expired, or
     *   deserialization fails.
     */
    inline fun <reified T> get(key: String, maxAgeMs: Long): T? {
        // 1. Try memory cache (fastest path)
        val memEntry = memoryCache.get(key)
        if (memEntry != null) {
            if (System.currentTimeMillis() - memEntry.timestamp <= maxAgeMs) {
                return try {
                    json.decodeFromString<T>(memEntry.jsonStr)
                } catch (e: Exception) {
                    Log.w(TAG, "Memory deserialization failed for key=$key: ${e.message}")
                    memoryCache.remove(key)
                    null
                }
            }
            // Expired — evict from memory
            memoryCache.remove(key)
        }

        // 2. Try disk cache
        val ts = prefs.getLong("$key$TS_SUFFIX", 0L)
        if (ts == 0L || System.currentTimeMillis() - ts > maxAgeMs) {
            // Expired or missing — clean up
            if (ts != 0L) {
                prefs.edit()
                    .remove(key)
                    .remove("$key$TS_SUFFIX")
                    .apply()
            }
            return null
        }

        val jsonStr = prefs.getString(key, null) ?: return null
        return try {
            val data = json.decodeFromString<T>(jsonStr)
            // Promote to memory for subsequent fast access
            memoryCache.put(key, CachedEntry(ts, jsonStr))
            data
        } catch (e: Exception) {
            Log.w(TAG, "Disk deserialization failed for key=$key: ${e.message}")
            // Corrupt entry — remove it
            prefs.edit()
                .remove(key)
                .remove("$key$TS_SUFFIX")
                .apply()
            null
        }
    }

    /**
     * Store a value in both memory and disk cache.
     *
     * @param T The value type. Must be a [kotlinx.serialization.Serializable] class.
     * @param key Cache key.
     * @param data The value to cache.
     */
    inline fun <reified T> set(key: String, data: T) {
        val jsonStr = try {
            json.encodeToString(data)
        } catch (e: Exception) {
            Log.w(TAG, "Serialization failed for key=$key: ${e.message}")
            return
        }

        val now = System.currentTimeMillis()
        memoryCache.put(key, CachedEntry(now, jsonStr))
        prefs.edit()
            .putString(key, jsonStr)
            .putLong("$key$TS_SUFFIX", now)
            .apply()
    }

    /**
     * Remove all cached entries whose key starts with the given [prefix].
     *
     * Example: `clear("cameras")` removes `cameras.list`, `cameras.detail.1`,
     * and their corresponding timestamps.
     */
    fun clear(prefix: String) {
        // Memory: iterate and remove matching keys
        val memKeys = memoryCache.snapshot().keys.filter { it.startsWith(prefix) }
        memKeys.forEach { memoryCache.remove(it) }

        // Disk: iterate and remove matching keys + their timestamps
        val prefKeys = prefs.all.keys.filter { it.startsWith(prefix) && !it.endsWith(TS_SUFFIX) }
        if (prefKeys.isNotEmpty()) {
            prefs.edit().apply {
                prefKeys.forEach { k ->
                    remove(k)
                    remove("$k$TS_SUFFIX")
                }
                apply()
            }
        }
    }

    /**
     * Remove ALL cached entries from both memory and disk.
     */
    fun invalidateAll() {
        memoryCache.evictAll()
        prefs.edit().clear().apply()
    }

    /**
     * Retrieve the raw JSON string stored for [key], without any TTL check
     * or deserialization. This is useful when the caller needs to perform
     * custom or partial deserialization.
     */
    fun getRaw(key: String): String? = prefs.getString(key, null)
}