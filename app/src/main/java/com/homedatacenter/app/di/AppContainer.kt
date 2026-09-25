package com.homedatacenter.app.di

import android.content.Context
import android.util.Log
import com.homedatacenter.app.data.api.HomeCenterApi
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.repository.HomeCenterRepository
import com.homedatacenter.app.util.BaseUrlResolver
import com.homedatacenter.app.util.PrefsManager
import com.homedatacenter.app.util.RoleManager
import com.homedatacenter.app.util.TokenRefreshInterceptor
import com.homedatacenter.app.util.TokenManager
import com.homedatacenter.app.util.WebRtcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class AppContainer(private val context: Context) {

    val prefsManager: PrefsManager by lazy { PrefsManager(context) }


    /**
     * v1.10.0: single owner of the JWT re-bind flow. Shared by the
     * TokenRefreshInterceptor (401 recovery) and the monthly silent
     * refresh (tryAutoRefreshToken).
     */
    val tokenManager: TokenManager by lazy { TokenManager(prefsManager) { getApiBaseUrl() } }
    val okHttpClient: OkHttpClient by lazy {
        val baseClient = NetworkFactory.okHttpClient(enableLogging = true)
        // v1.8.15: add token refresh interceptor AFTER the main
        // builder so it wraps the User-Agent interceptor. The
        // interceptor silently re-binds on 401 "token version
        // mismatch" and retries the request with a fresh token.
        baseClient.newBuilder()
            .addInterceptor(TokenRefreshInterceptor(prefsManager, tokenManager))
            .build()
    }

    /**
     * Picks between LAN (http://192.168.31.235/) and remote
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
    private var latestKnownReleaseInfo: com.homedatacenter.app.data.model.UpdateInfo? = null
    @Volatile
    private var updateCheckFailed: Boolean = false
    private var updateCheckJob: kotlinx.coroutines.Job? = null

    // v1.6.28: background auto-download state. As soon as a new
    // version is detected (startup check or manual force-check) we
    // start streaming the APK to disk so it's ready by the time the
    // user opens the settings page. SettingsFragment reads these
    // fields to render "ready, tap to install" / "downloading… X%" /
    // "download failed, tap to retry" without driving the download
    // itself.
    @Volatile
    private var cachedDownloadedApk: java.io.File? = null
    @Volatile
    private var downloadProgress: Int = 0  // 0..100
    @Volatile
    private var downloadFailed: Boolean = false
    @Volatile
    private var downloadingApk: Boolean = false

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
                val isAdmin = prefsManager.isAdmin
                val targetFlavor = if (isAdmin) null else "release"
                val info = getRepository().getLatestRelease(token, targetFlavor)
                latestKnownReleaseInfo = info
                // 普通用户只接受 release 版本的推送；admin 用户接受两种版本的推送
                if (!isAdmin && !info.isRelease) {
                    Log.d("AppContainer", "Skipping non-release update for regular user: ${info.file_name}")
                    return@launch
                }
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
                        "Update available: ${info.version_name} (flavor=${info.flavor.ifEmpty { if (info.isRelease) "release" else "debug" }}, installed=$installedName)")
                    // v1.6.28: start downloading the APK immediately
                    // so it's ready on disk when the user visits the
                    // settings page — no manual "download" step.
                    startBackgroundDownload(info)
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
     * Returns the latest known release metadata fetched from the server,
     * regardless of whether an update is available or the app is already
     * at the latest version. Useful for reading release_notes.
     */
    fun getLatestKnownReleaseInfo(): com.homedatacenter.app.data.model.UpdateInfo? =
        latestKnownReleaseInfo

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
            val isAdmin = prefsManager.isAdmin
            val targetFlavor = if (isAdmin) null else "release"
            val info = getRepository().getLatestRelease(token, targetFlavor)
            latestKnownReleaseInfo = info
            // 普通用户只接受 release 版本的推送；admin 用户接受两种版本的推送
            if (!isAdmin && !info.isRelease) {
                Log.d("AppContainer", "Skipping non-release update for regular user: ${info.file_name}")
                cachedUpdateInfo = null
                return null
            }
            // v1.6.14: compare versionName strings (see checkUpdateOnStartup
            // for the version_code scale-mismatch explanation).
            val installedName = com.homedatacenter.app.util.ApkInstaller
                .installedVersionName(context)
            val hasUpdate = com.homedatacenter.app.util.ApkInstaller
                .compareVersions(info.version_name, installedName) > 0
            if (hasUpdate) {
                cachedUpdateInfo = info
                // v1.6.28: a manual check that finds a new version
                // also kicks off the background download so the UI
                // can show progress immediately.
                startBackgroundDownload(info)
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

    // --- v1.6.28: background APK auto-download ---
    //
    // As soon as a new version is detected (startup check or manual
    // force-check) we start streaming the APK to disk in the
    // background. The user no longer has to tap "download" — by the
    // time they reach the settings page the APK is usually already
    // on disk and they just tap "install" to launch the system
    // PackageInstaller.

    /**
     * Kick off the background APK download for [info]. Idempotent:
     *   - If the APK is already on disk (isApkCached), just record
     *     the file path and return.
     *   - If a download is already in flight, return (no duplicate).
     *
     * v1.8.21: On failure, auto-retries with exponential backoff
     * (3 attempts: immediate → 5s → 15s). Each attempt resumes from
     * the partial .part file left by the previous attempt, so a
     * dropped connection mid-download doesn't waste the bytes
     * already fetched. Only after all retries are exhausted does
     * it set [downloadFailed] so the UI can offer a manual retry.
     */
    private fun startBackgroundDownload(info: com.homedatacenter.app.data.model.UpdateInfo) {
        // If the APK is already on disk from a previous session,
        // skip re-downloading — just remember the file path.
        if (com.homedatacenter.app.util.ApkInstaller.isApkCached(context, info)) {
            val fileName = if (info.file_name.isNotEmpty()) info.file_name
                else "app-debug-v${info.version_name}.apk"
            cachedDownloadedApk = java.io.File(
                java.io.File(context.filesDir, "downloads"), fileName
            )
            downloadingApk = false
            downloadFailed = false
            downloadProgress = 100
            Log.d("AppContainer", "APK already cached on disk: $fileName")
            return
        }
        // Prevent concurrent downloads.
        if (downloadingApk) return
        val token = prefsManager.token ?: return

        downloadingApk = true
        downloadFailed = false
        downloadProgress = 0

        warmScope.launch {
            // v1.8.21: auto-retry with exponential backoff.
            //   attempt 1: immediate
            //   attempt 2: 5s delay
            //   attempt 3: 15s delay
            // Each attempt resumes from the partial .part file,
            // so interrupted downloads continue where they left off.
            val delays = longArrayOf(0L, 5_000L, 15_000L)
            var file: java.io.File? = null
            for (attempt in delays.indices) {
                if (attempt > 0) {
                    Log.d("AppContainer",
                        "Background download retry #$attempt after ${delays[attempt]}ms delay…")
                    downloadProgress = 0
                    kotlinx.coroutines.delay(delays[attempt])
                }
                file = com.homedatacenter.app.util.ApkInstaller.downloadOnly(
                    context = context,
                    repo = getRepository(),
                    token = token,
                    info = info,
                    onProgress = { percent -> downloadProgress = percent },
                )
                if (file != null) break
            }
            if (file != null) {
                cachedDownloadedApk = file
                downloadProgress = 100
                Log.d("AppContainer", "Background download ready: ${file.absolutePath}")
            } else {
                downloadFailed = true
                Log.w("AppContainer", "Background download failed after ${delays.size} attempts")
            }
            downloadingApk = false
        }
    }

    /** The fully-downloaded APK File ready to install, or null. */
    fun getCachedDownloadedApk(): java.io.File? = cachedDownloadedApk

    /** Current background download progress (0..100). */
    fun getDownloadProgress(): Int = downloadProgress

    /** True if the last background download attempt failed. */
    fun isDownloadFailed(): Boolean = downloadFailed

    /** True if a background download is currently in flight. */
    fun isDownloadingApk(): Boolean = downloadingApk

    /**
     * Retry the background download after a failure. No-op unless
     * [isDownloadFailed] is true and we still have cached UpdateInfo
     * (i.e. a check has previously found a new version).
     */
    fun retryDownload() {
        if (!downloadFailed) return
        val info = cachedUpdateInfo ?: return
        downloadFailed = false
        startBackgroundDownload(info)
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
    private val iceFetchLock = java.util.concurrent.atomic.AtomicBoolean(false)

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
        if (!iceFetchLock.compareAndSet(false, true)) return
        warmScope.launch {
            try {
                val config = getRepository().getIceConfig(token)
                cachedIceConfig = config
                // Persist to PrefsManager so on cold start the app can
                // synchronously return the last-known ICE config via
                // [getIceConfig] without waiting for the
                // GET /api/v1/network/ice-config round-trip (10ms LAN /
                // 1.4s Tunnel). Wrapped in its own try/catch so a
                // serialization/prefs failure never blocks caching in
                // memory.
                try {
                    val json = NetworkFactory.json.encodeToString(
                        com.homedatacenter.app.data.model.IceConfig.serializer(),
                        config
                    )
                    prefsManager.setIceConfigJson(json)
                } catch (e: Exception) {
                    Log.w("AppContainer", "ICE config persist failed: ${e.message}")
                }
                Log.d("AppContainer", "ICE config prefetched: ${config.ice_servers.size} servers")
            } catch (e: Exception) {
                iceFetchLock.set(false)  // allow retry on failure
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

    /**
     * Synchronously return the ICE config: from the in-memory cache,
     * else from the persisted [PrefsManager] copy (< 1 hour old), else
     * null. Does NOT hit the network — callers needing a guaranteed
     * non-null value must fall back to [getOrFetchIceConfig]. On cold
     * start, before any network call has completed, this returns the
     * last-known config persisted by [prefetchIceConfig], shaving the
     * GET /api/v1/network/ice-config round-trip (10ms LAN / 1.4s
     * Tunnel) off the first camera open.
     */
    fun getIceConfig(): com.homedatacenter.app.data.model.IceConfig? {
        cachedIceConfig?.let { return it }
        val json = prefsManager.getIceConfigJson() ?: return null
        return try {
            val config = NetworkFactory.json.decodeFromString(
                com.homedatacenter.app.data.model.IceConfig.serializer(),
                json
            )
            cachedIceConfig = config
            config
        } catch (e: Exception) {
            Log.w("AppContainer", "ICE config deserialize from prefs failed: ${e.message}")
            null
        }
    }

    // --- v1.8.15: 每月自动静默刷新 JWT ---
    //
    // 在 App 启动时检查，如果距离上次刷新已超过 30 天，则用
    // 存储的 access_key 重新绑定，获取新令牌。此刷新是静默的
    // （用户无感知），且独立于服务端 token_version 旋转。
    // 即使管理员没有手动旋转，客户端也会定期刷新 JWT，
    // 缩短令牌泄露窗口期。
    /**
     * v1.10.0: checks and performs the monthly silent JWT refresh.
     * Implementation moved to TokenManager (shared with the
     * TokenRefreshInterceptor bind flow).
     */
    fun tryAutoRefreshToken(force: Boolean = false) = tokenManager.tryAutoRefreshToken(force)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.feiyemomo.top/"
    }
}
