package com.homedatacenter.app.di

import android.content.Context
import com.homedatacenter.app.data.api.HomeCenterApi
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.repository.HomeCenterRepository
import com.homedatacenter.app.util.PrefsManager
import okhttp3.OkHttpClient

class AppContainer(private val context: Context) {

    val prefsManager: PrefsManager by lazy { PrefsManager(context) }

    val okHttpClient: OkHttpClient by lazy {
        NetworkFactory.okHttpClient(enableLogging = true)
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
     * Always returns a non-null base URL. The user-configurable server
     * URL setting was removed (server is fixed/managed); this falls
     * back to [DEFAULT_BASE_URL] when the pref is unset. Fragments that
     * build full URLs (e.g. weather, snapshot, mp4 stream) should use
     * this instead of [PrefsManager.baseUrl] to avoid silently
     * returning early when the pref is null.
     */
    fun getApiBaseUrl(): String = prefsManager.baseUrl ?: DEFAULT_BASE_URL

    fun getRepository(): HomeCenterRepository {
        if (currentRepository == null) {
            currentRepository = HomeCenterRepository(getApi(), prefsManager)
        }
        return currentRepository!!
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
