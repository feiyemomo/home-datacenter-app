package com.homedatacenter.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Result of a single URL probe. `rttMs` is the wall-clock time from
 * request send to response received; -1 means the probe failed
 * before getting any response (network error / timeout).
 */
data class ProbeResult(val alive: Boolean, val rttMs: Long)

/**
 * User-selectable network path preference. Stored in SharedPreferences
 * (key = "preference") so the choice survives app restarts.
 *
 * - AUTO: probe all three candidates and pick the lowest-RTT alive
 *   one (tiebreaker: LAN > IPv6 > Tunnel — see probeSync).
 * - LAN: force the LAN URL if alive, otherwise fall back to IPv6
 *   direct, otherwise Tunnel. The probe still runs so we can measure
 *   RTT and detect that we had to fall back.
 * - IPV6_DIRECT: force the IPv6 direct URL if alive, otherwise fall
 *   back to LAN, otherwise Tunnel.
 * - RELAY: always use the Cloudflare Tunnel (always alive from any
 *   network — the user picks this when they explicitly want to bypass
 *   LAN/IPv6, e.g. to test the tunnel path or because the local
 *   network is misbehaving).
 */
enum class NetworkPathPreference(val label: String) {
    AUTO("自动"),
    LAN("局域网"),
    IPV6_DIRECT("IPv6 直连"),
    RELAY("远程 (Tunnel)");

    companion object {
        fun fromName(name: String?): NetworkPathPreference =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * Picks the fastest reachable backend base URL at runtime.
 *
 * Three candidates are probed in priority order:
 *  1. LAN URL  http://192.168.31.235:8088/   — when the device is on the
 *     home network this is ~10ms TTFB vs the Cloudflare Tunnel's
 *     measured 1.4s TTFB (with 10s+ timeouts on ~1/3 of requests
 *     from China). For live HLS/MP4 streaming this is the difference
 *     between "instant" and "spinner forever".
 *  2. IPv6 direct URL  http://[NAS_IPV6]:8088/  — v1.6.23: when the
 *     phone has IPv6 and the NAS 8088 port is bound to IPv6
 *     (compose.yaml dual-stack), this bypasses Cloudflare Tunnel
 *     entirely. TTFB ~50ms vs Tunnel's ~1.4s. Critical for remote
 *     viewing on Chinese carriers where CGNAT blocks IPv4 P2P but
 *     IPv6 routes directly. The NAS IPv6 address is the SLAAC EUI-64
 *     address (stable across reboots; only the /64 prefix rotates
 *     on ISP DHCPv6-PD renewal). If the prefix changes, update
 *     IPV6_DIRECT_URL below AND go2rtc's config.yml webrtc.candidates.
 *  3. Remote URL https://api.feiyemomo.top/   — Cloudflare Tunnel,
 *     works from any network but slow + lossy from China.
 *
 * Strategy:
 *  - On app launch, [probeLanOnStartup] kicks off a background probe
 *    immediately. The very first API call may go to the remote URL
 *    (the default) while the probe is in flight; once LAN is confirmed
 *    reachable the resolver switches and [onUrlChanged] fires so the
 *    cached Retrofit/Repository is rebuilt against the new URL.
 *  - On every [current] call, if the cache is older than [TTL_MS]
 *    we kick off an async re-probe so network switches (user walks
 *    out of Wi-Fi range, or comes back home) are picked up within
 *    a few minutes without blocking the calling thread.
 *  - When the resolved URL changes, [onUrlChanged] fires so
 *    AppContainer can invalidate its cached Retrofit/Repository
 *    instances (otherwise the next call would still hit the old URL).
 *  - v1.6.23: [onNetworkLost] provides an immediate fallback path
 *    for NetworkChangeMonitor — when the default network drops, we
 *    switch `resolved` to the best known safe default (IPv6 direct
 *    if previously reachable, otherwise Tunnel) BEFORE kicking off
 *    the async probe. This fixes the "LAN → remote switch is slow"
 *    bug where every API call timed out against the now-unreachable
 *    LAN URL for 1.5s each while the probe was still running.
 *
 * The probe target is GET /api/v1/system/status on the backend —
 * a JWT-protected endpoint. nginx routes the /api/ prefix to the
 * home-api container; we get 401 when the API is alive (missing
 * JWT), 502 when nginx is up but API is down. Anything less than
 * 500 means "API reachable". We deliberately avoid /health because
 * nginx falls through to try_files /index.html for unmatched paths,
 * returning 200 with SPA HTML even when the API container is
 * crashed — that would be a false positive.
 */
class BaseUrlResolver(
    private val client: OkHttpClient,
    context: Context,
) {
    /**
     * Currently resolved base URL. Always returns immediately — never
     * blocks. Triggers an async re-probe if the cache is stale.
     */
    @Volatile
    private var resolved: String = REMOTE_URL

    @Volatile
    private var lastProbedAt: Long = 0L

    /**
     * v1.6.23: cached reachability of the IPv6 direct URL. Set by
     * probeSync() on every probe. Read by onNetworkLost() to pick the
     * best safe default when the network drops — if IPv6 was working
     * before, we switch to it immediately instead of falling all the
     * way back to the Tunnel.
     */
    @Volatile
    private var ipv6DirectAvailable: Boolean = false

    private val probing = AtomicBoolean(false)

    /**
     * v1.6.26: user-selectable path preference. When != AUTO the
     * chosen URL is forced (probe still runs to measure RTT but
     * won't switch away unless the forced URL is unreachable, in
     * which case we fall back per the preference's priority order).
     * Stored in SharedPreferences so the choice survives restarts.
     */
    @Volatile
    private var preference: NetworkPathPreference = NetworkPathPreference.AUTO

    /**
     * v1.6.26: RTT (wall-clock) of the most recent successful probe
     * against the currently resolved URL, in milliseconds. -1 means
     * the last probe failed or hasn't run yet. Read by UI surfaces
     * (Dashboard path chip, NetworkDetailActivity) via
     * [currentRttMs] / [currentMethodLabel].
     */
    @Volatile
    var lastRttMs: Long = -1L
        private set

    /**
     * v1.6.26: used by setPreference to ensure a re-probe actually
     * fires even if one is already in flight. When forceProbe() is
     * called while probing=true, this flag is set so the in-flight
     * probe loop runs an extra iteration after finishing. Without
     * this, a preference change made during an in-flight probe
     * wouldn't take effect until the next TTL-triggered probe (up
     * to 5 minutes later) — bad UX.
     */
    @Volatile
    private var pendingProbe: Boolean = false

    /**
     * v1.6.34: true while a probe is actively running (between
     * [forceProbe]/[probeAsync] kicking off and [probeSync] finishing).
     * UI surfaces read this via [onProbeStateChanged] to show a spinner
     * next to the "客户端实际路径" row on NetworkDetailActivity.
     */
    @Volatile
    private var probeInProgress: Boolean = false

    /**
     * v1.6.34: invoked on [probeInProgress] state transitions. Receives
     * `true` when a probe starts, `false` when it finishes. Called from
     * background probe threads — callers must marshal to the UI thread
     * when updating views. Set to null in onDestroy to avoid leaking
     * the activity.
     */
    @Volatile
    var onProbeStateChanged: ((Boolean) -> Unit)? = null

    private val prefs by lazy {
        // Use applicationContext to avoid leaking whatever context
        // the caller passed in (AppContainer already uses the app
        // context, but defensive — never an Activity context for
        // SharedPreferences).
        context.applicationContext
            .getSharedPreferences("network_path", Context.MODE_PRIVATE)
    }

    /**
     * v1.8.24: user-configurable LAN URL. When non-null, overrides
     * the hardcoded LAN_URL. Set from SettingsFragment; persisted in
     * SharedPreferences so it survives app restarts.
     */
    @Volatile
    private var effectiveLanUrl: String = LAN_URL
    @Volatile
    private var effectiveLanHost: String = LAN_HOST
    @Volatile
    private var effectiveLanPort: Int = LAN_PORT

    init {
        preference = NetworkPathPreference.fromName(prefs.getString(KEY_PREF, null))
        // v1.8.24: load user-configured custom LAN URL. If set, it
        // overrides the hardcoded LAN_URL/LAN_HOST/LAN_PORT so the
        // app works regardless of NAS IP changes without recompiling.
        loadCustomLanUrl()
        // v1.10.1: restore last successfully connected URL on cold start
        // so SplashActivity prefetch hits the right origin immediately.
        val lastGood = prefs.getString(KEY_LAST_RESOLVED_URL, null)
        resolved = when (preference) {
            NetworkPathPreference.LAN -> effectiveLanUrl
            NetworkPathPreference.IPV6_DIRECT -> IPV6_DIRECT_URL
            NetworkPathPreference.RELAY -> REMOTE_URL
            NetworkPathPreference.AUTO -> if (!lastGood.isNullOrBlank()) lastGood else REMOTE_URL
        }
    }

    private fun loadCustomLanUrl() {
        val custom = prefs.getString(KEY_CUSTOM_LAN_URL, null)
        if (!custom.isNullOrBlank()) {
            applyCustomLanUrl(custom)
        }
    }

    /**
     * Sets a custom LAN URL (e.g. "http://192.168.31.235:8088/").
     * Pass null or empty string to revert to the hardcoded default.
     * Persists to SharedPreferences and forces a re-probe.
     */
    fun setCustomLanUrl(url: String?) {
        if (url.isNullOrBlank()) {
            prefs.edit().remove(KEY_CUSTOM_LAN_URL).apply()
            effectiveLanUrl = LAN_URL
            effectiveLanHost = LAN_HOST
            effectiveLanPort = LAN_PORT
        } else {
            prefs.edit().putString(KEY_CUSTOM_LAN_URL, url).apply()
            applyCustomLanUrl(url)
        }
        android.util.Log.i(TAG, "setCustomLanUrl: $url — forcing re-probe")
        forceProbe()
    }

    fun getCustomLanUrl(): String? = prefs.getString(KEY_CUSTOM_LAN_URL, null)

    private fun applyCustomLanUrl(url: String) {
        try {
            val uri = java.net.URI(url)
            effectiveLanUrl = url
            effectiveLanHost = uri.host ?: url.removePrefix("http://").removePrefix("https://").substringBefore(':')
            effectiveLanPort = if (uri.port > 0) uri.port else 8088
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Invalid custom LAN URL '$url', keeping default", e)
        }
    }

    /**
     * Called on the calling thread when the resolved URL changes due
     * to a probe. Use this to invalidate cached Retrofit / Repository
     * instances that pin the previous base URL.
     */
    var onUrlChanged: ((String) -> Unit)? = null

    /**
     * Returns the currently resolved base URL. Fast — never blocks.
     * Kicks off an async re-probe if the cache is older than [TTL_MS].
     */
    fun current(): String {
        val now = System.currentTimeMillis()
        if (now - lastProbedAt > TTL_MS && probing.compareAndSet(false, true)) {
            probeAsync()
        }
        return resolved
    }

    /**
     * v1.6.26: returns the user's network path preference (Auto/LAN/
     * IPv6/Tunnel). The preference is loaded from SharedPreferences
     * on construction and updated by [setPreference].
     */
    fun getPreference(): NetworkPathPreference = preference

    /**
     * v1.6.26: set the user's path preference. Stored in
     * SharedPreferences (survives app restarts) and honored
     * immediately on the next probe. If AUTO, the probe logic picks
     * the lowest-RTT alive candidate; otherwise the chosen URL is
     * forced (probe still runs to measure RTT but won't switch away
     * unless the forced URL is unreachable — see probeSync for the
     * fallback chain).
     *
     * Calls [forceProbe] so the new preference takes effect within
     * ~1-2 seconds (one probe round). Safe to call from the main
     * thread.
     */
    fun setPreference(pref: NetworkPathPreference) {
        if (preference == pref) return
        prefs.edit().putString(KEY_PREF, pref.name).apply()
        preference = pref
        android.util.Log.i(TAG, "setPreference: $pref — forcing re-probe")
        forceProbe()
    }

    /**
     * v1.6.26: RTT (wall-clock ms) of the most recent successful
     * probe against the currently resolved URL. -1 means no probe has
     * succeeded yet. Exposed for UI surfaces (Dashboard path chip,
     * NetworkDetailActivity) so the user can see the actual latency
     * of the path they're on.
     */
    fun currentRttMs(): Long = lastRttMs

    /**
     * v1.6.29: Update [lastRttMs] from a real API call's RTT (e.g.
     * DashboardFragment.loadSystemStatus). This makes the displayed
     * latency reflect steady-state connection reuse (~250ms on cellular
     * IPv6) rather than the probe RTT which includes TCP handshake
     * (~500ms on cellular IPv6).
     *
     * Only updates when the new RTT is lower than the current value
     * (or when no probe has succeeded yet, lastRttMs < 0). This
     * prevents transient network jitter from degrading the displayed
     * value — the probe RTT acts as an upper bound, and API RTT
     * only pulls it down toward the true steady-state latency.
     *
     * Safe to call from any thread (lastRttMs is @Volatile).
     */
    fun updateRttFromApiCall(rtt: Long) {
        if (rtt < 0) return
        val current = lastRttMs
        if (current < 0 || rtt < current) {
            lastRttMs = rtt
        }
    }

    /**
     * v1.6.26: human-readable label of the currently selected path,
     * e.g. "局域网 (12ms)" or "IPv6 直连 (45ms)" or "远程 (1400ms)".
     * Used by UI surfaces (Dashboard path chip,
     * NetworkDetailActivity) so the user can see at a glance which
     * path the app is using and how fast it is.
     *
     * RTT is omitted when [lastRttMs] is negative (no successful
     * probe yet) to avoid misleading "( -1ms)" output.
     */
    fun currentMethodLabel(): String {
        val rtt = if (lastRttMs >= 0) " (${lastRttMs}ms)" else ""
        return when {
            isLan() -> "局域网$rtt"
            isIpv6Direct() -> "IPv6 直连$rtt"
            else -> "远程$rtt"
        }
    }

    /**
     * v1.6.10: returns true if the currently resolved URL is the LAN
     * URL. Used by WebRTC code to decide whether to skip STUN/TURN
     * servers (LAN only needs host candidates, avoids 1-2s STUN
     * gathering delay) and to shorten ICE gathering timeout.
     */
    fun isLan(): Boolean = resolved == effectiveLanUrl

    /**
     * v1.6.23: returns true if the currently resolved URL is the IPv6
     * direct URL. Used by WebRTC code to decide whether to attempt
     * IPv6 P2P (skip STUN, gather IPv6 host candidates only) and by
     * CameraDetailActivity to gate the WebRTC-over-IPv6 path.
     */
    fun isIpv6Direct(): Boolean = resolved == IPV6_DIRECT_URL

    /**
     * v1.6.23: returns true if the resolved URL is a direct path to
     * the NAS (either LAN or IPv6 direct), bypassing Cloudflare Tunnel.
     * Used by WebRTC code to decide whether to skip STUN/TURN servers
     * — direct paths only need host candidates, the Tunnel can't route
     * WebRTC media anyway.
     */
    fun isDirectPath(): Boolean = resolved == effectiveLanUrl || isIpv6Direct()

    /**
     * v1.6.23: immediate fallback for NetworkChangeMonitor.onLost().
     * Switches `resolved` to the best known safe default BEFORE
     * kicking off the async probe — fixes the "LAN → remote switch
     * is slow" bug where every API call timed out against the
     * now-unreachable LAN URL (1.5s each) while the probe was
     * still running.
     *
     * Safe default selection:
     *  - If IPv6 direct was reachable on the last probe, switch to
     *    it immediately (phone likely still has IPv6 on the new
     *    network — cellular handoff preserves IPv6 in most cases).
     *  - Otherwise switch to the Cloudflare Tunnel (works from any
     *    network, just slower).
     *
     * After switching, kicks off forceProbe() to re-validate and
     * potentially switch to LAN if the new network is the home WiFi.
     */
    fun onNetworkLost() {
        val safeDefault = if (ipv6DirectAvailable) IPV6_DIRECT_URL else REMOTE_URL
        if (resolved != safeDefault) {
            android.util.Log.i(
                TAG,
                "onNetworkLost: switching resolved → $safeDefault (safe default, ipv6Cached=$ipv6DirectAvailable)",
            )
            resolved = safeDefault
            onUrlChanged?.invoke(safeDefault)
        }
        forceProbe()
    }

    /**
     * Force a re-probe asynchronously. Use this to react to network
     * change broadcasts (ConnectivityManager.NetworkCallback) so the
     * switch to LAN is immediate instead of waiting for the TTL.
     *
     * Safe to call from any thread — the probe runs on a background
     * daemon thread, never blocks the caller. If a probe is already
     * in flight, the call sets [pendingProbe] so the in-flight probe
     * loop runs an extra iteration after finishing. This matters for
     * [setPreference]: without it, a preference change made while a
     * probe is already running wouldn't take effect until the next
     * TTL-triggered probe (up to 5 minutes later). For ordinary
     * network-change calls (where the in-flight probe will pick up
     * the new network state anyway) the extra iteration is a cheap
     * no-op — probeSync is idempotent when nothing has changed.
     */
    fun forceProbe() {
        if (!probing.compareAndSet(false, true)) {
            // Probe already running — make sure we run another pass
            // after it finishes so any state change (e.g. preference)
            // gets applied promptly.
            pendingProbe = true
            return
        }
        // v1.6.34: flip probe state immediately so the UI spinner
        // appears without waiting for the background thread to start.
        // probeSync()'s finally block will flip it back to false.
        setProbeInProgress(true)
        Thread {
            try {
                probeSync()
                // Drain any pending probe requests. Each iteration
                // clears the flag BEFORE running so a new forceProbe
                // call during probeSync() will trigger one more pass
                // after the current one finishes.
                while (pendingProbe) {
                    pendingProbe = false
                    probeSync()
                }
            } finally {
                probing.set(false)
            }
        }.apply {
            isDaemon = true
            name = "BaseUrlResolver-force"
            start()
        }
    }

    /**
     * Schedules a series of background LAN re-probes after app launch.
     *
     * Why a schedule instead of a single synchronous probe:
     *
     *  - Real-phone WiFi validation takes 5-10s on some ROMs (Xiaomi /
     *    Oppo / Vivo especially). On application launch the WiFi
     *    stack may not even have an IP yet. Blocking the main thread
     *    5-10s would ANR.
     *  - The emulator's "WiFi" is bridged through the host PC (always
     *    validated), so the very first probe succeeds — that's why
     *    the emulator works but the phone doesn't.
     *  - NetworkChangeMonitor's `onCapabilitiesChanged(VALIDATED)`
     *    callback is supposed to fire when WiFi is ready, but on
     *    some Chinese ROMs this callback is delayed or dropped, so
     *    we can't rely on it alone.
     *
     * The schedule runs at increasing delays (1.5s, 4s, 9s, 16s)
     * with a hard cap at ~20s — by then any reasonable WiFi stack
     * has finished validation. Each probe is best-effort: if a probe
     * succeeds the resolver switches to LAN immediately; subsequent
     * probes are no-ops because `resolved` already matches.
     *
     * Safe to call from the main thread on app launch — the work
     * happens on background daemon threads.
     */
    fun probeLanOnStartup() {
        // Kick off the first probe immediately (background).
        forceProbe()
        // Schedule escalating retries to absorb the real-phone
        // WiFi validation window. Each schedule entry is a
        // self-contained daemon thread that calls forceProbe after
        // its delay — forceProbe is a no-op if a probe is already
        // in flight.
        STARTUP_RETRY_DELAYS_MS.forEach { delayMs ->
            Thread {
                try {
                    Thread.sleep(delayMs)
                    // Re-check: if a previous probe already
                    // switched us to LAN, skip the rest.
                    if (resolved == effectiveLanUrl) return@Thread
                    forceProbe()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }.apply {
                isDaemon = true
                name = "BaseUrlResolver-startup-$delayMs"
                start()
            }
        }
    }

    private fun probeAsync() {
        // v1.6.34: flip probe state immediately so the UI spinner
        // appears without waiting for the background thread to start.
        // probeSync()'s finally block will flip it back to false.
        setProbeInProgress(true)
        Thread {
            try {
                probeSync()
            } finally {
                probing.set(false)
            }
        }.apply {
            isDaemon = true
            name = "BaseUrlResolver-probe"
            start()
        }
    }

    /**
     * v1.6.34: updates [probeInProgress] and notifies [onProbeStateChanged]
     * only when the value actually changes, so the UI doesn't get spurious
     * hide callbacks while a probe is already running. Safe to call from
     * any thread (probeInProgress is @Volatile).
     */
    private fun setProbeInProgress(value: Boolean) {
        if (probeInProgress == value) return
        probeInProgress = value
        onProbeStateChanged?.invoke(value)
    }

    private fun probeSync() {
        // v1.7.19: fast-path-first probing. Previously all three probes
        // (LAN / IPv6 / Tunnel) were awaited before switching, which
        // meant waiting for the Tunnel (3s timeout, ~1.4s typical from
        // China) even when LAN was alive at 50ms. Now:
        //
        //   - Direct path probes (LAN + IPv6) are awaited first (max
        //     ~1.5s). If either is alive, we switch immediately and
        //     CANCEL the still-in-flight Tunnel probe — no need to wait.
        //   - The Tunnel probe is only awaited as a fallback when both
        //     direct paths are dead.
        //   - RELAY preference is a special case: always Tunnel, so we
        //     probe Tunnel directly (still cache IPv6 availability in
        //     the background for onNetworkLost's safe-default logic).
        //
        // This cuts the typical home-network probe from ~1.4s (waiting
        // for Tunnel) to ~50ms (LAN only), and the typical cellular-
        // IPv6 probe from ~1.4s to ~200ms (IPv6 only).
        //
        // LAN probe is two-pronged (HTTP + raw TCP socket connect) to
        // work around vendor HTTP policy on some ROMs (MIUI/ColorOS
        // intercept cleartext HTTP). When TCP succeeds but HTTP fails,
        // we use the TCP connect time as the RTT proxy.
        //
        // v1.6.29: before probing, warm up the connection pool for the
        // CURRENT resolved URL so the probe can reuse the warmed
        // connection (skipping TCP handshake).
        setProbeInProgress(true)
        try {
            if (resolved.isNotBlank()) {
                warmupConnection(resolved)
            }

            val ipv6Url = IPV6_DIRECT_URL

            // RELAY preference: always Tunnel. Still cache IPv6
            // availability in the background for onNetworkLost.
            if (preference == NetworkPathPreference.RELAY) {
                Thread {
                    try {
                        val r = probeUrl(ipv6Url, IPV6_TIMEOUT_MS)
                        ipv6DirectAvailable = r.alive
                    } catch (_: Exception) {}
                }.apply {
                    isDaemon = true
                    name = "BaseUrlResolver-relay-cache"
                    start()
                }
                val remoteResult = probeUrl(REMOTE_URL, REMOTE_TIMEOUT_MS)
                android.util.Log.i(
                    TAG,
                    "probeSync: Tunnel=${remoteResult.alive}(${remoteResult.rttMs}ms) " +
                        "preference=$preference (resolved=$resolved)",
                )
                lastRttMs = remoteResult.rttMs
                applyResolved(REMOTE_URL)
                return
            }

            // AUTO / LAN / IPV6_DIRECT: launch all three probes in
            // parallel, but only await the direct path probes first.
            // The Tunnel probe is cancelled if a direct path wins.
            runBlocking {
                coroutineScope {
                    val lanDeferred = async(Dispatchers.IO) {
                        // LAN probe: two-pronged (HTTP + TCP in parallel).
                        val httpDeferred = async {
                            probeUrl(effectiveLanUrl, LAN_TIMEOUT_MS)
                        }
                        val tcpDeferred = async {
                            val tcpRtt = probeTcpRtt(effectiveLanHost, effectiveLanPort, LAN_TIMEOUT_MS)
                            if (tcpRtt >= 0) ProbeResult(alive = true, rttMs = tcpRtt)
                            else ProbeResult(alive = false, rttMs = -1L)
                        }
                        val httpResult = httpDeferred.await()
                        val tcpResult = tcpDeferred.await()
                        when {
                            httpResult.alive && tcpResult.alive ->
                                if (httpResult.rttMs <= tcpResult.rttMs) httpResult else tcpResult
                            httpResult.alive -> httpResult
                            tcpResult.alive -> {
                                android.util.Log.i(TAG, "LAN: HTTP dead (${httpResult.rttMs}ms) but TCP alive (${tcpResult.rttMs}ms) — using TCP RTT proxy")
                                tcpResult
                            }
                            else -> ProbeResult(alive = false, rttMs = min(httpResult.rttMs, tcpResult.rttMs))
                        }
                    }
                    val ipv6Deferred = async(Dispatchers.IO) {
                        probeUrl(ipv6Url, IPV6_TIMEOUT_MS)
                    }
                    val remoteDeferred = async(Dispatchers.IO) {
                        probeUrl(REMOTE_URL, REMOTE_TIMEOUT_MS)
                    }

                    // Await direct path probes first (max ~1.5s).
                    val lanResult = lanDeferred.await()
                    val ipv6Result = ipv6Deferred.await()
                    ipv6DirectAvailable = ipv6Result.alive

                    // Select a direct path based on preference.
                    val directChosen: String? = when (preference) {
                        NetworkPathPreference.LAN -> when {
                            lanResult.alive -> effectiveLanUrl
                            ipv6Result.alive -> ipv6Url
                            else -> null
                        }
                        NetworkPathPreference.IPV6_DIRECT -> when {
                            ipv6Result.alive -> ipv6Url
                            lanResult.alive -> effectiveLanUrl
                            else -> null
                        }
                        NetworkPathPreference.AUTO -> {
                            val candidates = mutableListOf<Pair<String, Long>>()
                            if (lanResult.alive) candidates.add(effectiveLanUrl to lanResult.rttMs)
                            if (ipv6Result.alive) candidates.add(ipv6Url to ipv6Result.rttMs)
                            candidates.minByOrNull { it.second }?.first
                        }
                        NetworkPathPreference.RELAY -> null // handled above
                    }

                    if (directChosen != null) {
                        // Fast path: switch immediately, cancel Tunnel probe.
                        remoteDeferred.cancel()
                        val chosenRtt = if (directChosen == effectiveLanUrl) lanResult.rttMs else ipv6Result.rttMs
                        android.util.Log.i(
                            TAG,
                            "probeSync: LAN=${lanResult.alive}(${lanResult.rttMs}ms) " +
                                "IPv6=${ipv6Result.alive}(${ipv6Result.rttMs}ms) " +
                                "Tunnel=cancelled " +
                                "preference=$preference (resolved=$resolved)",
                        )
                        lastRttMs = chosenRtt
                        applyResolved(directChosen)
                    } else {
                        // Fallback: both direct paths dead, wait for Tunnel.
                        val remoteResult = remoteDeferred.await()
                        android.util.Log.i(
                            TAG,
                            "probeSync: LAN=${lanResult.alive}(${lanResult.rttMs}ms) " +
                                "IPv6=${ipv6Result.alive}(${ipv6Result.rttMs}ms) " +
                                "Tunnel=${remoteResult.alive}(${remoteResult.rttMs}ms) " +
                                "preference=$preference (resolved=$resolved)",
                        )
                        lastRttMs = remoteResult.rttMs
                        applyResolved(REMOTE_URL)
                    }
                }
            }
        } finally {
            setProbeInProgress(false)
        }
    }

    /**
     * Apply the chosen URL: update [resolved] and [lastProbedAt],
     * fire [onUrlChanged] and warm up the connection pool when the
     * URL actually changes.
     */
    private fun applyResolved(chosen: String) {
        val changed = chosen != resolved
        resolved = chosen
        lastProbedAt = System.currentTimeMillis()
        // v1.10.1: persist last known good URL for subsequent cold starts
        prefs.edit().putString(KEY_LAST_RESOLVED_URL, chosen).apply()
        if (changed) {
            android.util.Log.i(
                TAG,
                "probeSync: switching resolved → $chosen (preference=$preference)",
            )
            onUrlChanged?.invoke(chosen)
            warmupConnection(chosen)
        }
    }

    /**
     * v1.6.28: Pre-warm the OkHttp ConnectionPool by issuing a throwaway
     * HEAD request to the resolved URL. This establishes a TCP connection
     * (and TLS for the Tunnel path) that subsequent API calls reuse,
     * saving one RTT on the first real API request.
     *
     * On cellular IPv6 with ~250ms RTT, this cuts the first API call
     * from ~500ms (TCP handshake + HTTP) to ~250ms (HTTP only).
     *
     * Safe to call from any thread. Failures are silently ignored —
     * the connection pool simply won't have a warm connection, and the
     * first API call will pay the full TCP handshake cost. No functional
     * impact.
     *
     * Called by [probeSync] when the resolved URL changes (including
     * the very first successful probe at startup).
     */
    private fun warmupConnection(url: String) {
        try {
            val warmupClient = client.newBuilder()
                .callTimeout(3, TimeUnit.SECONDS)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
            // HEAD request to /api/v1/system/status — same path as the
            // probe, so the connection is to the exact same origin that
            // subsequent API calls will use. HEAD is cheaper than GET
            // (no response body), but the TCP+TLS establishment is
            // identical. The response (401) is irrelevant — we only
            // care that the connection is now in the pool.
            //
            // Note: we use the SHARED client's newBuilder(), so the
            // connection pool is shared with the main OkHttpClient
            // used by Retrofit. The warmed connection will be reused
            // by the next API call.
            val request = Request.Builder()
                .url("${url.trimEnd('/')}/api/v1/system/status")
                .head()
                .build()
            warmupClient.newCall(request).execute().use { response ->
                android.util.Log.i(
                    TAG,
                    "warmupConnection: $url → HTTP ${response.code} " +
                        "(connection now in pool for reuse)",
                )
            }
        } catch (e: Exception) {
            // Best-effort — if warmup fails, the first API call will
            // just take longer. Don't surface this to the user.
            android.util.Log.d(
                TAG,
                "warmupConnection: $url → failed (non-critical): ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun probeUrl(url: String, timeoutMs: Int): ProbeResult {
        // Probe /api/v1/system/status. nginx routes /api/* to the
        // home-api container (see web/nginx.conf: location /api/ →
        // proxy_pass http://api:8080). The endpoint is JWT-protected
        // so we get 401 when the API is alive — that's still "server
        // reachable" for our purposes. If the API container is down
        // nginx returns 502 Bad Gateway, which we treat as unreachable.
        //
        // We deliberately do NOT probe /health (root-level Gin route)
        // because nginx falls through to `try_files ... /index.html`
        // for any path not under /api/, /go2rtc/, /frigate/ — so /health
        // would return 200 with the SPA HTML body even if the API
        // container is crashed. That would give a false-positive probe.
        //
        // We use GET (not HEAD) because the Gin router in this backend
        // version returns 404 for HEAD requests on GET-registered
        // routes (verified empirically: HEAD /api/v1/system/status →
        // 404, GET → 401). A GET probe is reliable; HEAD would make us
        // reject both the LAN and remote URLs and never switch.
        //
        // v1.6.23: for the IPv6 direct URL, OkHttp connects directly
        // to the IPv6 literal address (no DNS). If the phone has no
        // IPv6 route, the connect() fails immediately with
        // NoRouteToHostException (typically <100ms) — the timeout
        // never triggers. So the IPv6 probe is also an implicit
        // phone-IPv6-connectivity check.
        //
        // v1.6.26: returns ProbeResult(alive, rttMs) so probeSync can
        // pick the lowest-RTT alive candidate (AUTO preference) and
        // surface RTT in the UI. rttMs is wall-clock from request
        // build to response received; -1 means the request errored
        // before any response (timeout / connect refused / DNS failure).
        val probeClient = client.newBuilder()
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url("${url.trimEnd('/')}/api/v1/system/status")
            .get()
            .build()
        val sw = System.currentTimeMillis()
        return try {
            probeClient.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - sw
                // 200/401 = API alive (auth required, but reachable).
                // 502/503 = nginx up but API container down.
                // 404 = nginx misconfigured (no /api/ proxy rule).
                // Anything < 500 counts as "the backend is alive and
                // serving requests" — we accept 401 even though auth
                // is missing, because the question we're answering is
                // "can we route API calls to this URL?" not "are we
                // authenticated?".
                val alive = response.code < 500
                android.util.Log.i(
                    TAG,
                    "probeUrl: ${url.trimEnd('/')} → HTTP ${response.code} in ${elapsed}ms (${if (alive) "ALIVE" else "DOWN"})",
                )
                ProbeResult(alive, elapsed)
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - sw
            android.util.Log.w(
                TAG,
                "probeUrl: ${url.trimEnd('/')} → error in ${elapsed}ms: ${e.javaClass.simpleName}: ${e.message}",
            )
            ProbeResult(false, elapsed)
        }
    }

    /**
     * Raw TCP socket connect probe. Used as a fallback when the HTTP
     * probe fails — on some Android ROMs (Xiaomi MIUI, Oppo ColorOS)
     * OkHttp's HTTP stack rejects cleartext HTTP even when
     * `usesCleartextTraffic=true` is set, due to a vendor-injected
     * "network policy" that intercepts HTTP. A raw socket connect
     * bypasses that policy and answers the simpler question "is the
     * host actually routable from this device". If the socket probe
     * succeeds but the HTTP probe fails, we'll still switch to LAN —
     * the next API call will use the HTTP path, and if that fails the
     * repository's error handling will surface it.
     *
     * v1.6.26: returns the TCP connect time in ms (>= 0) so probeSync
     * can use it as the RTT proxy when HTTP failed but TCP succeeded.
     * Returns -1 on failure.
     */
    private fun probeTcpRtt(host: String, port: Int, timeoutMs: Int): Long {
        val sw = System.currentTimeMillis()
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val elapsed = System.currentTimeMillis() - sw
            socket.close()
            android.util.Log.i(
                TAG,
                "probeTcp: $host:$port → connected in ${elapsed}ms",
            )
            elapsed
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - sw
            android.util.Log.w(
                TAG,
                "probeTcp: $host:$port → ${e.javaClass.simpleName}: ${e.message} (after ${elapsed}ms)",
            )
            -1L
        }
    }

    companion object {
        private const val TAG = "BaseUrlResolver"

        // v1.6.26: SharedPreferences key for the user's network path
        // preference (NetworkPathPreference.name). Stored in a separate
        // "network_path" prefs file so it doesn't collide with other
        // prefs and is easy to inspect/reset independently.
        private const val KEY_PREF = "preference"

        // v1.8.24: SharedPreferences key for user-configured custom LAN URL.
        // When set (e.g. "http://192.168.31.235:8088/"), overrides the
        // hardcoded LAN_URL so the app adapts to NAS IP changes without
        // recompiling. Set/cleared from SettingsFragment.
        private const val KEY_CUSTOM_LAN_URL = "custom_lan_url"

        // v1.10.1: SharedPreferences key for the last successfully resolved base URL.
        // Persisted so cold starts immediately prefetch against the last known good
        // path (e.g. LAN ~10ms) instead of defaulting to Cloudflare Tunnel (1.4s+).
        private const val KEY_LAST_RESOLVED_URL = "last_resolved_url"

        // LAN (NAS) URL — the home network address of the backend.
        // Port 8088 is the home-datacenter nginx/web container (bound
        // to 0.0.0.0:8088 in compose.yaml), which reverse-proxies:
        //   /api/    → home-api:8080 (REST API)
        //   /go2rtc/ → home-frigate:1984 (HLS + WebRTC)
        //   /frigate/→ home-frigate:5000 (Frigate UI)
        //   /        → SPA static files
        // Using port 8088 (not the raw API port 8080) matches what the
        // remote URL provides via Cloudflare Tunnel — both surface the
        // full reverse-proxy stack so HLS/MP4/WebRTC URLs work
        // identically. Port 80 on the NAS is the FNOS system UI, NOT
        // our backend, so http://192.168.31.235/ would be wrong.
        const val LAN_URL = "http://192.168.31.235:8088/"
        const val LAN_HOST = "192.168.31.235"
        const val LAN_PORT = 8088

        // v1.6.23: IPv6 direct URL — bypasses Cloudflare Tunnel when
        // the phone has IPv6 connectivity. The NAS 8088 port is bound
        // to both IPv4 and IPv6 via docker-proxy (compose.yaml
        // dual-stack "0.0.0.0:8088:80" + "[::]:8088:80"). docker-proxy
        // handles the IPv6→IPv4 translation to the container's
        // internal 0.0.0.0:80, so nginx needs no IPv6 config.
        //
        // v1.6.32: switched from a hard-coded IPv6 literal to the DDNS
        // domain nas.feiyemomo.top. The domain has only an AAAA record
        // pointing at the NAS's SLAAC EUI-64 address, so OkHttp resolves
        // it to an IPv6 address and connects over IPv6 — semantically
        // identical to the old literal URL, but now the ISP DHCPv6-PD
        // prefix rotations are handled by the DDNS provider updating
        // the AAAA record, with ZERO code changes needed here.
        //
        // Why http:// (not https://): the NAS doesn't have a valid
        // TLS certificate for nas.feiyemomo.top (Let's Encrypt HTTP-01
        // can't reach the NAS on port 80 because port 80 is the FNOS
        // system UI, not our nginx; DNS-01 would require moving DNS to
        // a supported provider). Cleartext HTTP is acceptable here
        // because:
        //   - The JWT token is the only sensitive payload, and it's
        //     already transmitted in cleartext on the LAN URL too.
        //   - IPv6 traffic is end-to-end (no Cloudflare MITM), so
        //     it's actually MORE private than the Tunnel path despite
        //     lacking TLS.
        //   - The app already has usesCleartextTraffic=true for the
        //     LAN URL.
        //
        // Note: the compose.yaml NAS_IPV6_ADDRESS env var and the
        // go2rtc webrtc.candidates entry still use the raw IPv6 literal
        // because (a) the API validates it with net.ParseIP, and
        // (b) WebRTC ICE candidates require an IP:port, not a hostname.
        // Those are infrastructure-layer config, not app-layer URLs —
        // the app always uses the DDNS domain.
        const val IPV6_DIRECT_URL = "http://nas.feiyemomo.top:8088/"

        // Remote URL — Cloudflare Tunnel. Works from anywhere but is
        // slow + lossy from China (TTFB 1.4s average, 10s+ timeouts on
        // ~1/3 of requests through the tunnel).
        const val REMOTE_URL = "https://api.feiyemomo.top/"

        // Re-probe at most this often. Shorter wastes battery + data,
        // longer means we miss network switches (user leaves/returns
        // home). 5 minutes matches the weather cache TTL — a natural
        // cadence given how often the user is likely to switch
        // networks in practice.
        private const val TTL_MS = 5L * 60 * 1000

        // LAN probe timeout. 1s is more than enough for a local network
        // (typical 10-50ms RTT). Reduced from 1.5s to speed up the
        // overall probe cycle — the slowest probe (Remote) dominates
        // the wall time anyway, but tightening LAN helps when the HTTP
        // probe fails and we need the TCP fallback quickly.
        private const val LAN_TIMEOUT_MS = 1_000

        // v1.6.23: IPv6 direct probe timeout. OkHttp fails immediately
        // (NoRouteToHostException) if the phone has no IPv6 route, so
        // this timeout only fires when the phone HAS IPv6 but the NAS
        // is unreachable (e.g. NAS offline, prefix rotated). 1.5s is
        // enough for a cross-carrier IPv6 TCP handshake (typical
        // 100-500ms on Chinese cellular IPv6).
        private const val IPV6_TIMEOUT_MS = 1_500

        // v1.6.23: remote probe timeout. The Tunnel is the last-resort
        // fallback; 3s is enough for a cold Cloudflare Tunnel connection
        // (typical 1-2s, worst ~3s on Chinese cellular). Reduced from
        // 4s to minimize the total probe wall time.
        private const val REMOTE_TIMEOUT_MS = 3_000

        // Escalating startup probe delays. Each entry schedules a
        // background probe at the given offset from app launch.
        // The schedule is intentionally exponential (1.5s → 4s → 9s
        // → 16s) so that:
        //   - 1.5s catches the common "WiFi validated shortly after
        //     app launch" case on most phones.
        //   - 4s catches slower phones where validation takes ~3s.
        //   - 9s catches pathological cases (captive portal re-auth,
        //     slow DNS resolver on first connection).
        //   - 16s is the last-ditch fallback — by 16s any reasonable
        //     WiFi stack has either validated or failed; we don't
        //     want to keep probing forever (battery + data).
        // Total wall time: ~16s, well within the user's patience for
        // an app's first launch on a new network. Subsequent launches
        // on the same network succeed on the first probe (1.5s or
        // less) because the WiFi stack is already validated.
        private val STARTUP_RETRY_DELAYS_MS = longArrayOf(1_500L, 4_000L, 9_000L, 16_000L)
    }
}
