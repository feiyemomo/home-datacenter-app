package com.homedatacenter.app.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PrefsManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    // v1.8.15: persisted access_key for silent token refresh.
    // Stored so the OkHttp interceptor can re-bind automatically
    // when the server returns "token version mismatch".
    var accessKey: String?
        get() = prefs.getString(KEY_ACCESS_KEY, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_KEY, value).apply()

    // v1.8.15: last time the JWT was proactively refreshed (unix ms).
    // Used by the monthly auto-refresh check on app startup.
    var lastTokenRefreshTime: Long
        get() = prefs.getLong(KEY_LAST_TOKEN_REFRESH, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_TOKEN_REFRESH, value).apply()

    var baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var userId: Long
        get() = prefs.getLong(KEY_USER_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_USER_ID, value).apply()

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var isAdmin: Boolean
        get() = prefs.getBoolean(KEY_IS_ADMIN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_ADMIN, value).apply()

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_FOLLOW_SYSTEM)
        set(value) = prefs.edit().putInt(KEY_THEME_MODE, value).apply()

    fun getDeviceScope(): String = prefs.getString(KEY_DEVICE_SCOPE, "mine") ?: "mine"

    fun setDeviceScope(scope: String) {
        prefs.edit().putString(KEY_DEVICE_SCOPE, scope).apply()
    }

    fun saveUserInfo(name: String, admin: Boolean) {
        prefs.edit()
            .putString(KEY_USER_NAME, name)
            .putBoolean(KEY_IS_ADMIN, admin)
            .apply()
    }

    fun clearAuth() {
        // 登出后不得再静默续签（JWT 续签依赖 access_key，必须一并清除）。
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_IS_ADMIN)
            .remove(KEY_ACCESS_KEY)
            .remove(KEY_LAST_TOKEN_REFRESH)
            .apply()
    }

    fun isLoggedIn(): Boolean = !token.isNullOrEmpty()

    var cachedDevices: String?
        get() = prefs.getString(KEY_CACHED_DEVICES, null)
        set(value) = prefs.edit().putString(KEY_CACHED_DEVICES, value).apply()

    var cachedCameras: String?
        get() = prefs.getString(KEY_CACHED_CAMERAS, null)
        set(value) = prefs.edit().putString(KEY_CACHED_CAMERAS, value).apply()

    var cachedSystemStatus: String?
        get() = prefs.getString(KEY_CACHED_SYSTEM_STATUS, null)
        set(value) = prefs.edit().putString(KEY_CACHED_SYSTEM_STATUS, value).apply()

    var cachedNetworkStatus: String?
        get() = prefs.getString(KEY_CACHED_NETWORK_STATUS, null)
        set(value) = prefs.edit().putString(KEY_CACHED_NETWORK_STATUS, value).apply()

    var lastDevicesFetchTime: Long
        get() = prefs.getLong(KEY_LAST_DEVICES_FETCH, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_DEVICES_FETCH, value).apply()

    var lastCamerasFetchTime: Long
        get() = prefs.getLong(KEY_LAST_CAMERAS_FETCH, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CAMERAS_FETCH, value).apply()

    var lastSystemStatusFetchTime: Long
        get() = prefs.getLong(KEY_LAST_STATUS_FETCH, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_STATUS_FETCH, value).apply()

    fun clearCache() {
        prefs.edit()
            .remove(KEY_CACHED_DEVICES)
            .remove(KEY_CACHED_CAMERAS)
            .remove(KEY_CACHED_SYSTEM_STATUS)
            .remove(KEY_CACHED_NETWORK_STATUS)
            .remove(KEY_LAST_DEVICES_FETCH)
            .remove(KEY_LAST_CAMERAS_FETCH)
            .remove(KEY_LAST_STATUS_FETCH)
            .apply()
    }

    fun getIceConfigJson(): String? {
        val ts = prefs.getLong(KEY_ICE_CONFIG_TS, 0L)
        if (ts == 0L || System.currentTimeMillis() - ts > ICE_CONFIG_TTL_MS) return null
        return prefs.getString(KEY_ICE_CONFIG_JSON, null)
    }

    fun setIceConfigJson(json: String) {
        prefs.edit().apply {
            putString(KEY_ICE_CONFIG_JSON, json)
            putLong(KEY_ICE_CONFIG_TS, System.currentTimeMillis())
            apply()
        }
    }

    // v1.13.0: User notification preferences
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()

    var notifyPerson: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_PERSON, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_PERSON, value).apply()

    var notifyVehicle: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_VEHICLE, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_VEHICLE, value).apply()

    var notifyPet: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_PET, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_PET, value).apply()

    var notifyMotion: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_MOTION, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_MOTION, value).apply()

    var notifySystem: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_SYSTEM, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_SYSTEM, value).apply()

    var notifyIncludeSnapshot: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_INCLUDE_SNAPSHOT, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_INCLUDE_SNAPSHOT, value).apply()

    var notifyDndEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_DND_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_DND_ENABLED, value).apply()

    var notifyDndStartHour: Int
        get() = prefs.getInt(KEY_NOTIFY_DND_START_HOUR, 22)
        set(value) = prefs.edit().putInt(KEY_NOTIFY_DND_START_HOUR, value).apply()

    var notifyDndStartMinute: Int
        get() = prefs.getInt(KEY_NOTIFY_DND_START_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_NOTIFY_DND_START_MINUTE, value).apply()

    var notifyDndEndHour: Int
        get() = prefs.getInt(KEY_NOTIFY_DND_END_HOUR, 7)
        set(value) = prefs.edit().putInt(KEY_NOTIFY_DND_END_HOUR, value).apply()

    var notifyDndEndMinute: Int
        get() = prefs.getInt(KEY_NOTIFY_DND_END_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_NOTIFY_DND_END_MINUTE, value).apply()

    fun isDndActive(): Boolean {
        if (!notifyDndEnabled) return false
        val now = java.util.Calendar.getInstance()
        val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val startMinutes = notifyDndStartHour * 60 + notifyDndStartMinute
        val endMinutes = notifyDndEndHour * 60 + notifyDndEndMinute
        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    fun shouldNotifyAlert(label: String): Boolean {
        if (!notificationsEnabled) return false
        if (isDndActive()) return false
        return when (label.lowercase()) {
            "person" -> notifyPerson
            "system" -> notifySystem
            else -> notifyMotion
        }
    }

    companion object {
        private const val PREFS_FILE = "home_datacenter_prefs"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_ACCESS_KEY = "access_key"
        private const val KEY_LAST_TOKEN_REFRESH = "last_token_refresh"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_IS_ADMIN = "is_admin"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DEVICE_SCOPE = "device_scope"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_NOTIFY_PERSON = "notify_person"
        private const val KEY_NOTIFY_VEHICLE = "notify_vehicle"
        private const val KEY_NOTIFY_PET = "notify_pet"
        private const val KEY_NOTIFY_MOTION = "notify_motion"
        private const val KEY_NOTIFY_SYSTEM = "notify_system"
        private const val KEY_NOTIFY_INCLUDE_SNAPSHOT = "notify_include_snapshot"
        private const val KEY_NOTIFY_DND_ENABLED = "notify_dnd_enabled"
        private const val KEY_NOTIFY_DND_START_HOUR = "notify_dnd_start_hour"
        private const val KEY_NOTIFY_DND_START_MINUTE = "notify_dnd_start_minute"
        private const val KEY_NOTIFY_DND_END_HOUR = "notify_dnd_end_hour"
        private const val KEY_NOTIFY_DND_END_MINUTE = "notify_dnd_end_minute"
        private const val KEY_CACHED_DEVICES = "cached_devices"
        private const val KEY_CACHED_CAMERAS = "cached_cameras"
        private const val KEY_CACHED_SYSTEM_STATUS = "cached_system_status"
        private const val KEY_CACHED_NETWORK_STATUS = "cached_network_status"
        private const val KEY_LAST_DEVICES_FETCH = "last_devices_fetch"
        private const val KEY_LAST_CAMERAS_FETCH = "last_cameras_fetch"
        private const val KEY_LAST_STATUS_FETCH = "last_status_fetch"
        private const val KEY_ICE_CONFIG_JSON = "ice_config_json"
        private const val KEY_ICE_CONFIG_TS = "ice_config_ts"
        private const val ICE_CONFIG_TTL_MS = 60 * 60 * 1000L // 1 hour

        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_FOLLOW_SYSTEM = 2

        const val CACHE_DURATION_SHORT = 30 * 1000L
        const val CACHE_DURATION_MEDIUM = 5 * 60 * 1000L
        const val CACHE_DURATION_LONG = 30 * 60 * 1000L
    }
}
