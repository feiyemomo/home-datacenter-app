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

    fun saveUserInfo(name: String, admin: Boolean) {
        prefs.edit()
            .putString(KEY_USER_NAME, name)
            .putBoolean(KEY_IS_ADMIN, admin)
            .apply()
    }

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_IS_ADMIN)
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

    companion object {
        private const val PREFS_FILE = "home_datacenter_prefs"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_IS_ADMIN = "is_admin"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_CACHED_DEVICES = "cached_devices"
        private const val KEY_CACHED_CAMERAS = "cached_cameras"
        private const val KEY_CACHED_SYSTEM_STATUS = "cached_system_status"
        private const val KEY_CACHED_NETWORK_STATUS = "cached_network_status"
        private const val KEY_LAST_DEVICES_FETCH = "last_devices_fetch"
        private const val KEY_LAST_CAMERAS_FETCH = "last_cameras_fetch"
        private const val KEY_LAST_STATUS_FETCH = "last_status_fetch"

        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_FOLLOW_SYSTEM = 2

        const val CACHE_DURATION_SHORT = 30 * 1000L
        const val CACHE_DURATION_MEDIUM = 5 * 60 * 1000L
        const val CACHE_DURATION_LONG = 30 * 60 * 1000L
    }
}
