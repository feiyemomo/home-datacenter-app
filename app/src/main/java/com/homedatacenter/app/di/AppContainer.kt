package com.homedatacenter.app.di

import android.content.Context
import com.homedatacenter.app.data.api.HomeCenterApi
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.repository.HomeCenterRepository
import com.homedatacenter.app.util.BaseUrlResolver
import com.homedatacenter.app.util.PrefsManager
import com.homedatacenter.app.util.RoleManager
import okhttp3.OkHttpClient

class AppContainer(private val context: Context) {

    val prefsManager: PrefsManager by lazy { PrefsManager(context) }

    val okHttpClient: OkHttpClient by lazy {
        NetworkFactory.okHttpClient(enableLogging = true)
    }

    /**
     * Picks between LAN (http://192.168.31.234/) and remote
     * (https://api.feiyemomo.top/) at runtime by probing /health.
     * When the device is on the home network the LAN URL is preferred
     * because it's ~10ms TTFB vs the Cloudflare Tunnel's 1.4s+.
     *
     * Call [baseUrlResolver.probeLanOnStartup] once on app launch so
     * the first API call benefits from LAN speed (if available).
     */
    val baseUrlResolver: BaseUrlResolver by lazy {
        BaseUrlResolver(okHttpClient).also { resolver ->
            resolver.onUrlChanged = { _ ->
                // When the resolved URL changes, invalidate the cached
                // Retrofit/Repository so the next call builds a new
                // instance against the new URL.
                resetApi()
            }
        }
    }

    private var currentBaseUrl: String = ""
    private var currentApi: HomeCenterApi? = null
    private var currentRepository: HomeCenterRepository? = null

    fun getApi(): HomeCenterApi {
        val baseUrl = getApiBaseUrl()
        if (currentApi == null || currentBaseUrl != baseUrl) {
            currentBaseUrl = baseUrl
            currentApi = NetworkFactory.createApi(baseUrl, okHttpClient)
            currentRepository = null
        }
        return currentApi!!
    }

    /**
     * Always returns a non-null base URL. Resolves between LAN and
     * remote at runtime via [baseUrlResolver] when no explicit user
     * override is set. Falls back to [DEFAULT_BASE_URL] when the
     * resolver has not yet probed (which only happens before
     * [baseUrlResolver.probeLanOnStartup] runs on app launch).
     *
     * Fragments that build full URLs (weather, snapshot, MP4 stream)
     * should use this instead of [PrefsManager.baseUrl] to avoid
     * silently returning early when the pref is null.
     */
    fun getApiBaseUrl(): String =
        prefsManager.baseUrl ?: baseUrlResolver.current().ifBlank { DEFAULT_BASE_URL }

    fun getRepository(): HomeCenterRepository {
        if (currentRepository == null) {
            currentRepository = HomeCenterRepository(getApi(), prefsManager)
        }
        return currentRepository!!
    }

    /**
     * Role manager — caches the result of /api/v1/user/me in
     * [PrefsManager] so UI code can synchronously decide whether to
     * show admin-only controls. Server-side enforcement remains
     * authoritative — see [RoleManager] for the threat model.
     */
    val roleManager: RoleManager by lazy {
        RoleManager(prefsManager, getRepository())
    }

    fun getWsUrl(): String {
        val baseUrl = getApiBaseUrl()
        return baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "api/v1/ws"
    }

    fun resetApi() {
        currentApi = null
        currentRepository = null
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.feiyemomo.top/"
    }
}
