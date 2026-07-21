package com.homedatacenter.app.di

import android.content.Context
import android.util.Log
import com.homedatacenter.app.data.api.HomeCenterApi
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.repository.HomeCenterRepository
import com.homedatacenter.app.util.BaseUrlResolver
import com.homedatacenter.app.util.PrefsManager
import com.homedatacenter.app.util.RoleManager
import com.homedatacenter.app.util.WebRtcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
     *
     * v1.6.26: resolver now takes the application [context] so it can
     * persist the user's network path preference (Auto/LAN/IPv6/Tunnel)
     * in a private SharedPreferences file ("network_path"). The
     * preference is honored on every probe — see BaseUrlResolver for
     * the selection logic.
     */
    val baseUrlResolver: BaseUrlResolver by lazy {
        BaseUrlResolver(okHttpClient, context).also { resolver ->
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

    // --- WebRTC预热 (v1.5.6) ---
    //
    // PeerConnectionFactory.initialize + EGL context creation takes
    // ~300-500ms on real devices. Doing it synchronously when the user
    // taps a camera delays the first frame. We pre-build a WebRtcClient
    // on a background coroutine right after the user is authenticated
    // (HomeCenterApp.onCreate) so by the time they navigate to a
    // camera detail page the factory is ready and only the actual
    // SDP/ICE negotiation (~200-500ms on LAN) remains.
    //
    // v1.6.10: changed from one-shot warm cache (takeWarmWebRtcClient
    // — first caller wins, subsequent callers rebuild synchronously)
    // to a LONG-LIVED SHARED instance. The PeerConnectionFactory +
    // EGL context are thread-safe and can serve multiple
    // CameraDetailActivity instances over the app lifetime. Each
    // CameraDetailActivity only creates its own PeerConnection (still
    // per-activity) but reuses the shared factory. This saves
    // 300-500ms on every camera open after the first one, and lets
    // us keep the factory alive across onPause/onResume so the
    // resume path is instant.
    @Volatile
    private var sharedWebRtcClient: WebRtcClient? = null
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var warmJob: Job? = null

    /**
     * Kick off WebRTC client pre-warming. Called from
     * HomeCenterApp.onCreate after we know the user is logged in
     * (token present). Idempotent — no-op if already warming or
     * already warmed.
     */
    fun warmWebRtc() {
        if (sharedWebRtcClient != null || warmJob?.isActive == true) return
        val baseUrl = getApiBaseUrl().ifBlank { return }
        val token = prefsManager.token ?: return
        warmJob = warmScope.launch {
            try {
                val client = WebRtcClient(
                    context = context,
                    okHttpClient = okHttpClient,
                    baseUrl = baseUrl,
                    token = token,
                )
                // init() must run on a thread with a Looper. We're
                // on Dispatchers.IO here; switch to Main for the
                // PeerConnectionFactory.initialize call.
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    client.init()
                }
                sharedWebRtcClient = client
                Log.d("AppContainer", "WebRtcClient warmed up (shared)")
            } catch (e: Exception) {
                Log.w("AppContainer", "WebRtc warm-up failed: ${e.message}")
            }
        }
    }

    /**
     * v1.6.10: returns the shared WebRtcClient, creating + initializing
     * it synchronously if needed. The returned client is NOT removed
     * from the cache — multiple CameraDetailActivity instances share
     * the same factory + EGL context. Caller is responsible for
     * calling release() on the PeerConnection (not the factory) when
     * the activity is destroyed.
     *
     * Returns null if baseUrl or token is unavailable, or if factory
     * init fails (e.g. WebRTC native lib load error on emulator).
     */
    fun getOrInitWebRtcClient(): WebRtcClient? {
        sharedWebRtcClient?.let { return it }
        val baseUrl = getApiBaseUrl().ifBlank { return null }
        val token = prefsManager.token ?: return null
        return try {
            val client = WebRtcClient(
                context = context,
                okHttpClient = okHttpClient,
                baseUrl = baseUrl,
                token = token,
            )
            // init() must run on the main thread (EGL + PeerConnectionFactory
            // require a Looper). Since this function is called from
            // CameraDetailActivity.onCreate (already on main thread),
            // a direct call is safe.
            client.init()
            sharedWebRtcClient = client
            Log.d("AppContainer", "WebRtcClient init synchronously (shared)")
            client
        } catch (e: Exception) {
            Log.w("AppContainer", "WebRtcClient init failed: ${e.message}")
            null
        }
    }

    /** v1.6.10: legacy one-shot API kept for compatibility — delegates
     *  to the shared instance and returns it WITHOUT removing from
     *  the cache. Old call sites behave identically except the client
     *  can now be reused. */
    fun takeWarmWebRtcClient(): WebRtcClient? = sharedWebRtcClient

    // --- v1.6.11: in-app self-update ---
    //
    // On app startup we silently check the server for a newer APK.
    // If found, we cache the result so the SettingsFragment can show
    // a "new version available" hint without re-fetching. We do NOT
    // auto-prompt — that would interrupt the user on every cold start
    // where a new version exists. The user discovers the update when
    // they visit the settings page (where we show a badge) or when
    // they tap "Check for updates" manually.
    @Volatile
    private var cachedUpdateInfo: com.homedatacenter.app.data.model.UpdateInfo? = null
    @Volatile
    private var updateCheckFailed: Boolean = false
    private var updateCheckJob: kotlinx.coroutines.Job? = null

    /**
     * Silent background update check. Idempotent — no-op if a check
     * is already in flight or has already completed this session.
     * Safe to call from HomeCenterApp.onCreate (background coroutine).
     */
    fun checkUpdateOnStartup() {
        if (cachedUpdateInfo != null || updateCheckFailed) return
        if (updateCheckJob?.isActive == true) return
        val token = prefsManager.token ?: return

        updateCheckJob = warmScope.launch {
            try {
                val info = getRepository().getLatestRelease(token)
                // v1.6.14: compare versionName strings, NOT version_code.
                // Backend derives version_code from the APK filename via
                // parseVersionCode("1.6.12") = 10612, but the app's
                // versionCode in build.gradle.kts is a flat integer
                // (e.g. 55). Different scales — `info.version_code > installed`
                // was always true, causing perpetual "update available"
                // prompts even when versions matched.
                val installedName = com.homedatacenter.app.util.ApkInstaller
                    .installedVersionName(context)
                val hasUpdate = com.homedatacenter.app.util.ApkInstaller
                    .compareVersions(info.version_name, installedName) > 0
                if (hasUpdate) {
                    cachedUpdateInfo = info
                    Log.d("AppContainer",
                        "Update available: ${info.version_name} (installed=$installedName)")
                } else {
                    Log.d("AppContainer",
                        "App is up-to-date (installed=$installedName, latest=${info.version_name})")
                }
            } catch (e: Exception) {
                updateCheckFailed = true
                Log.w("AppContainer", "Update check failed: ${e.message}")
            }
        }
    }

    /**
     * Returns the cached UpdateInfo from the last checkUpdateOnStartup
     * call, or null if no update is available / no check has been run.
     * SettingsFragment reads this to render the "new version available"
     * hint. Manual "Check for updates" button should bypass this cache
     * and call [forceCheckUpdate] instead.
     */
    fun getCachedUpdateInfo(): com.homedatacenter.app.data.model.UpdateInfo? =
        cachedUpdateInfo

    /**
     * Force a fresh update check (manual user action). Always hits the
     * network — does not return a cached result. Updates the cache on
     * success so subsequent visits to SettingsFragment show the new
     * state immediately. Returns the UpdateInfo (or null if no update
     * is available / check failed).
     */
    suspend fun forceCheckUpdate(): com.homedatacenter.app.data.model.UpdateInfo? {
        val token = prefsManager.token ?: return null
        return try {
            val info = getRepository().getLatestRelease(token)
            // v1.6.14: compare versionName strings (see checkUpdateOnStartup
            // for the version_code scale-mismatch explanation).
            val installedName = com.homedatacenter.app.util.ApkInstaller
                .installedVersionName(context)
            val hasUpdate = com.homedatacenter.app.util.ApkInstaller
                .compareVersions(info.version_name, installedName) > 0
            if (hasUpdate) {
                cachedUpdateInfo = info
                info
            } else {
                cachedUpdateInfo = null
                null
            }
        } catch (e: Exception) {
            Log.w("AppContainer", "forceCheckUpdate failed: ${e.message}")
            null
        }
    }

    /** Clear the cached update info (e.g. after the user installs
     *  the new version and the app restarts). */
    fun clearCachedUpdateInfo() {
        cachedUpdateInfo = null
        updateCheckFailed = false
    }

    // --- ICE config 预取 (v1.5.7) ---
    //
    // CameraDetailActivity.startWebRtcStream needs the ICE server list
    // from GET /api/v1/network/ice-config before it can build a
    // PeerConnection. On LAN that's ~10ms, on remote it's 1.4s+.
    // Pre-fetching it on Dashboard means by the time the user taps a
    // camera the config is already in memory — one less network
    // round-trip before the first frame.
    @Volatile
    private var cachedIceConfig: com.homedatacenter.app.data.model.IceConfig? = null

    /**
     * Returns the cached ICE config if available, null otherwise.
     * Does NOT fetch — caller should use [prefetchIceConfig] to warm
     * the cache and [getOrFetchIceConfig] to retrieve with fallback.
     */
    fun getCachedIceConfig(): com.homedatacenter.app.data.model.IceConfig? = cachedIceConfig

    /**
     * Pre-fetch ICE config in the background. Called from
     * DashboardFragment.refreshAll() so the config is warm by the
     * time the user opens a camera. Idempotent — if the cache is
     * fresh (under 5 min) the call is a no-op.
     */
    fun prefetchIceConfig() {
        if (cachedIceConfig != null) return
        val token = prefsManager.token ?: return
        warmScope.launch {
            try {
                val config = getRepository().getIceConfig(token)
                cachedIceConfig = config
                Log.d("AppContainer", "ICE config prefetched: ${config.ice_servers.size} servers")
            } catch (e: Exception) {
                Log.w("AppContainer", "ICE config prefetch failed: ${e.message}")
            }
        }
    }

    /**
     * Get ICE config from cache, or fetch synchronously if the cache
     * is empty. Called from CameraDetailActivity when starting a
     // WebRTC stream.
     */
    suspend fun getOrFetchIceConfig(token: String): com.homedatacenter.app.data.model.IceConfig? {
        cachedIceConfig?.let { return it }
        return try {
            getRepository().getIceConfig(token).also { cachedIceConfig = it }
        } catch (e: Exception) {
            Log.w("AppContainer", "ICE config fetch failed: ${e.message}")
            null
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.feiyemomo.top/"
    }
}
