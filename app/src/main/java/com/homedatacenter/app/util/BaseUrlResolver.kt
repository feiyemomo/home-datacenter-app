package com.homedatacenter.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
 *  1. LAN URL  http://192.168.31.234:8088/   — when the device is on the
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

    /**
     * v1.6.27: dynamically-fetched IPv6 base URL (from the backend
     * `/api/v1/network/ipv6` endpoint). When non-null, takes priority
     * over the hardcoded [IPV6_DIRECT_URL] constant — the constant
     * only reflects the NAS IPv6 address at compile time and goes
     * stale whenever the ISP rotates the /64 prefix (DHCPv6-PD
     * renewal). The backend reports its current outbound IPv6
     * address on every call, so preferring this field lets the app
     * follow prefix rotations without an app rebuild.
     *
     * Written by [fetchDynamicIpv6Url] (called from
     * [probeLanOnStartup] and [probeAsync]); read by [probeSync],
     * [isIpv6Direct], [isDirectPath], and [onNetworkLost].
     */
    @Volatile
    private var dynamicIpv6Url: String? = null

    /**
     * v1.6.27: supplies the JWT required to call the JWT-protected
     * `/api/v1/network/ipv6` endpoint. Set by AppContainer after the
     * auth state is initialized (BaseUrlResolver is constructed
     * before the token is available, hence the late-binding lambda
     * instead of a constructor parameter). When null or when the
     * lambda returns null, [fetchDynamicIpv6Url] is a no-op and we
     * fall back to the hardcoded [IPV6_DIRECT_URL].
     */
    @Volatile
    var tokenProvider: (() -> String?)? = null

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

    private val prefs by lazy {
        // Use applicationContext to avoid leaking whatever context
        // the caller passed in (AppContainer already uses the app
        // context, but defensive — never an Activity context for
        // SharedPreferences).
        context.applicationContext
            .getSharedPreferences("network_path", Context.MODE_PRIVATE)
    }

    init {
        preference = NetworkPathPreference.fromName(prefs.getString(KEY_PREF, null))
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
    fun isLan(): Boolean = resolved == LAN_URL

    /**
     * v1.6.23: returns true if the currently resolved URL is the IPv6
     * direct URL. Used by WebRTC code to decide whether to attempt
     * IPv6 P2P (skip STUN, gather IPv6 host candidates only) and by
     * CameraDetailActivity to gate the WebRTC-over-IPv6 path.
     *
     * v1.6.27: also returns true when `resolved` matches the
     * dynamically-fetched IPv6 URL (from /api/v1/network/ipv6). The
     * dynamic URL is functionally equivalent to [IPV6_DIRECT_URL] —
     * same NAS, same port, just a fresher address after a prefix
     * rotation — so all IPv6-specific WebRTC behavior applies.
     */
    fun isIpv6Direct(): Boolean =
        resolved == IPV6_DIRECT_URL || (dynamicIpv6Url != null && resolved == dynamicIpv6Url)

    /**
     * v1.6.23: returns true if the resolved URL is a direct path to
     * the NAS (either LAN or IPv6 direct), bypassing Cloudflare Tunnel.
     * Used by WebRTC code to decide whether to skip STUN/TURN servers
     * — direct paths only need host candidates, the Tunnel can't route
     * WebRTC media anyway.
     *
     * v1.6.27: delegates to [isIpv6Direct] so the dynamic IPv6 URL is
     * also recognized as a direct path. Without this, WebRTC would
     * incorrectly attempt STUN/TURN gathering when the resolver is
     * pointed at the dynamic IPv6 URL.
     */
    fun isDirectPath(): Boolean = resolved == LAN_URL || isIpv6Direct()

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
        // v1.6.27: prefer the dynamic IPv6 URL when available —
        // matches probeSync's behavior and avoids switching to a
        // stale hardcoded address that may have rotated.
        val safeDefault = if (ipv6DirectAvailable) (dynamicIpv6Url ?: IPV6_DIRECT_URL) else REMOTE_URL
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
        // v1.6.27: fetch dynamic IPv6 address from backend before
        // first probe. This is best-effort — if it fails (no token
        // yet, network not ready, backend unreachable), we fall back
        // to the hardcoded IPV6_DIRECT_URL (which may be stale if
        // the ISP rotated the prefix, but is better than nothing).
        // When the fetch succeeds and the URL differs from the
        // current one, we update dynamicIpv6Url and force a re-probe
        // so probeSync picks up the new address immediately.
        Thread {
            try {
                val newUrl = runBlocking { fetchDynamicIpv6Url() }
                if (newUrl != null && newUrl != dynamicIpv6Url) {
                    dynamicIpv6Url = newUrl
                    android.util.Log.i(TAG, "probeLanOnStartup: dynamic IPv6 URL updated → $newUrl, forcing re-probe")
                    forceProbe()
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "probeLanOnStartup: fetchDynamicIpv6Url failed: ${e.message}")
            }
        }.apply {
            isDaemon = true
            name = "BaseUrlResolver-ipv6-fetch"
            start()
        }

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
                    if (resolved == LAN_URL) return@Thread
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
        // v1.6.27: async refresh of the dynamic IPv6 URL. Decoupled
        // from probeSync so a slow /network/ipv6 call (3s timeout)
        // doesn't delay the URL switch. If the URL changes, we
        // update dynamicIpv6Url and force a re-probe — the next
        // probeSync iteration will pick up the new address. When
        // the URL is unchanged (common case) this is a no-op.
        Thread {
            try {
                val newUrl = runBlocking { fetchDynamicIpv6Url() }
                if (newUrl != null && newUrl != dynamicIpv6Url) {
                    dynamicIpv6Url = newUrl
                    android.util.Log.i(TAG, "probeAsync: dynamic IPv6 URL updated → $newUrl, forcing re-probe")
                    forceProbe()
                }
            } catch (_: Exception) {
                // Best-effort — swallow. Logged inside
                // fetchDynamicIpv6Url if the request itself failed.
            }
        }.apply {
            isDaemon = true
            name = "BaseUrlResolver-ipv6-refresh"
            start()
        }
    }

    /**
     * v1.6.27: fetches the NAS's current outbound IPv6 address from
     * the backend `/api/v1/network/ipv6` endpoint and returns it
     * wrapped as a base URL (`http://[<addr>]:8088/`).
     *
     * Why this exists: the hardcoded [IPV6_DIRECT_URL] only reflects
     * the NAS IPv6 address at compile time. Chinese ISPs rotate the
     * /64 prefix on DHCPv6-PD renewal (sometimes daily, sometimes on
     * router reboot), which invalidates the hardcoded address and
     * silently breaks IPv6 direct connectivity until the user
     * reinstalls the app. The backend reads its own current IPv6
     * address (or the `NAS_IPV6_ADDRESS` env var when the container
     * can't probe it) and returns it on every call, so polling this
     * endpoint lets us follow prefix rotations without an app
     * rebuild.
     *
     * JWT is required (the endpoint is auth-protected). The token is
     * obtained via [tokenProvider] — AppContainer sets this after
     * the auth state is initialized. When no token is available we
     * bail out and the caller keeps using the hardcoded
     * [IPV6_DIRECT_URL] (which may be stale but is better than
     * nothing).
     *
     * Best-effort: any failure (network error, non-200 response,
     * missing/malformed `outbound_address` field) returns null. The
     * caller is responsible for keeping the previous value.
     *
     * @return the freshly-fetched base URL (`http://[<addr>]:8088/`),
     *   or null if the fetch failed or no token was available.
     */
    private suspend fun fetchDynamicIpv6Url(): String? {
        val token = tokenProvider?.invoke() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val url = "${resolved.trimEnd('/')}/api/v1/network/ipv6"
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                client.newBuilder()
                    .callTimeout(3, TimeUnit.SECONDS)
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val body = response.body?.string() ?: return@use null
                        val json = JSONObject(body)
                        val data = json.optJSONObject("data") ?: return@use null
                        val outbound = data.optString("outbound_address", "")
                        if (outbound.isNotEmpty()) {
                            val newUrl = "http://[$outbound]:8088/"
                            android.util.Log.i(TAG, "fetchDynamicIpv6Url: got $outbound → $newUrl")
                            newUrl
                        } else {
                            android.util.Log.w(TAG, "fetchDynamicIpv6Url: outbound_address empty")
                            null
                        }
                    }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "fetchDynamicIpv6Url: failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    private fun probeSync() {
        // v1.6.26: three-tier probe with RTT measurement + manual
        // preference override.
        //
        // All three candidates (LAN / IPv6 direct / Tunnel) are probed
        // every cycle so we can:
        //   1. Measure RTT for each — needed for AUTO's lowest-RTT
        //      selection AND for display in the UI ("局域网 (12ms)").
        //   2. Cache IPv6 reachability for onNetworkLost's safe-default
        //      logic (ipv6DirectAvailable).
        //   3. Detect when a forced preference's URL has died and we
        //      need to fall back to the next priority.
        //
        // Selection:
        //   - If preference == AUTO: pick the alive candidate with the
        //     lowest RTT. Tiebreaker is priority order (LAN > IPv6 >
        //     Tunnel) — implemented by adding to the candidate list in
        //     that order and using minByOrNull which returns the FIRST
        //     minimum on ties.
        //   - If preference == LAN: force LAN if alive, else IPv6 if
        //     alive, else Tunnel.
        //   - If preference == IPV6_DIRECT: force IPv6 if alive, else
        //     LAN if alive, else Tunnel.
        //   - If preference == RELAY: always Tunnel (always alive via
        //     Cloudflare).
        //
        // LAN probe is two-pronged (HTTP + raw TCP socket connect) to
        // work around vendor HTTP policy on some ROMs (MIUI/ColorOS
        // intercept cleartext HTTP). When TCP succeeds but HTTP fails,
        // we use the TCP connect time as the RTT proxy (the next real
        // HTTP API call will be the true test — if it fails, the
        // repository's error handling surfaces it).
        //
        // Probe order matters for the early-return fast path on AUTO
        // when LAN is alive and obviously fastest: we still probe IPv6
        // and Tunnel too so the RTT comparison is meaningful and
        // ipv6DirectAvailable stays fresh for onNetworkLost. The total
        // extra cost is ~50ms (IPv6 NoRouteToHostException on a
        // non-IPv6 network) + the Tunnel probe (~1.4s on cellular).
        // To keep startup feeling snappy we let the Tunnel probe run
        // in the background — the resolver switches to LAN immediately
        // when the LAN probe returns, and the Tunnel probe just
        // updates RTT/availability caches when it finishes.
        //
        // Implementation note: we run the three probes sequentially
        // (not parallel) to keep the code simple and because the LAN
        // and IPv6 probes finish quickly (alive ~10ms / dead <100ms).
        // Only the Tunnel probe takes real time (~1.4s), and by then
        // we've already potentially switched `resolved` to LAN/IPv6 —
        // the user-visible latency is dominated by the fastest alive
        // candidate, not the slowest.

        // 1. LAN probe (two-pronged: HTTP + TCP fallback)
        var lanResult = probeUrl(LAN_URL, LAN_TIMEOUT_MS)
        if (!lanResult.alive) {
            val tcpRtt = probeTcpRtt(LAN_HOST, LAN_PORT, LAN_TIMEOUT_MS)
            if (tcpRtt >= 0) {
                // HTTP failed but TCP succeeded — vendor HTTP policy
                // is likely intercepting. Use TCP connect time as the
                // RTT proxy. The next real API call will reveal if
                // HTTP actually works.
                lanResult = ProbeResult(alive = true, rttMs = tcpRtt)
            }
        }

        // v1.6.27: prefer the dynamically-fetched IPv6 URL (from
        // /api/v1/network/ipv6) over the hardcoded IPV6_DIRECT_URL
        // constant. The dynamic URL tracks ISP prefix rotations; the
        // constant only reflects the address at compile time and goes
        // stale whenever the /64 prefix changes. When dynamicIpv6Url
        // is null (tokenProvider not yet set, or last fetch failed)
        // we fall back to the constant.
        val ipv6Url = dynamicIpv6Url ?: IPV6_DIRECT_URL

        // 2. IPv6 direct probe. OkHttp will fail immediately
        // (NoRouteToHostException / UnknownHostException) if the
        // phone has no IPv6 connectivity, so this is also an
        // implicit phone-IPv6 check — no separate ConnectivityManager
        // probe needed.
        val ipv6Result = probeUrl(ipv6Url, IPV6_TIMEOUT_MS)
        ipv6DirectAvailable = ipv6Result.alive

        // 3. Tunnel probe (always runs so we have a fresh RTT for the
        // Tunnel and so AUTO can compare all three).
        val remoteResult = probeUrl(REMOTE_URL, REMOTE_TIMEOUT_MS)

        android.util.Log.i(
            TAG,
            "probeSync: LAN=${lanResult.alive}(${lanResult.rttMs}ms) " +
                "IPv6=${ipv6Result.alive}(${ipv6Result.rttMs}ms) " +
                "Tunnel=${remoteResult.alive}(${remoteResult.rttMs}ms) " +
                "preference=$preference (resolved=$resolved)",
        )

        // Determine chosen URL based on preference + alive state.
        val chosen: String = when (preference) {
            NetworkPathPreference.LAN -> when {
                lanResult.alive -> LAN_URL
                ipv6Result.alive -> ipv6Url
                remoteResult.alive -> REMOTE_URL
                else -> REMOTE_URL // last resort — Tunnel is always "reachable" in practice
            }
            NetworkPathPreference.IPV6_DIRECT -> when {
                ipv6Result.alive -> ipv6Url
                lanResult.alive -> LAN_URL
                remoteResult.alive -> REMOTE_URL
                else -> REMOTE_URL
            }
            NetworkPathPreference.RELAY -> REMOTE_URL
            NetworkPathPreference.AUTO -> {
                // Pick lowest RTT among alive candidates; LAN > IPv6 >
                // Tunnel tiebreaker. Implemented by adding to the list
                // in priority order — minByOrNull returns the FIRST
                // minimum on ties, so a tie resolves to the
                // earlier-added (higher-priority) candidate.
                val candidates = mutableListOf<Pair<String, Long>>()
                if (lanResult.alive) candidates.add(LAN_URL to lanResult.rttMs)
                if (ipv6Result.alive) candidates.add(ipv6Url to ipv6Result.rttMs)
                if (remoteResult.alive) candidates.add(REMOTE_URL to remoteResult.rttMs)
                candidates.minByOrNull { it.second }?.first ?: REMOTE_URL
            }
        }

        // Update RTT for the chosen URL. If the chosen URL's probe
        // failed but we fell back to Tunnel, use the Tunnel RTT.
        val chosenRtt = when (chosen) {
            LAN_URL -> lanResult.rttMs
            ipv6Url -> ipv6Result.rttMs
            else -> remoteResult.rttMs
        }
        lastRttMs = chosenRtt

        // Apply. We always assign resolved (even if unchanged) so
        // lastProbedAt is fresh. onUrlChanged only fires on actual
        // changes — AppContainer uses it to invalidate cached
        // Retrofit/Repository instances.
        val changed = chosen != resolved
        resolved = chosen
        lastProbedAt = System.currentTimeMillis()
        if (changed) {
            android.util.Log.i(
                TAG,
                "probeSync: switching resolved → $chosen (rtt=${chosenRtt}ms, preference=$preference)",
            )
            onUrlChanged?.invoke(chosen)
            // v1.6.28: warm up the connection pool for the new URL so
            // the first real API call doesn't pay the TCP handshake cost.
            // This is especially valuable on cellular IPv6 where RTT is
            // ~250ms — warmup saves one full RTT on the first request.
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

    private fun switchTo(url: String) {
        // v1.6.26: retained for backward compatibility but no longer
        // used by probeSync (which now applies `resolved` directly so
        // it can also update lastRttMs / lastProbedAt atomically).
        if (resolved != url) {
            android.util.Log.i(TAG, "probeSync: switching resolved → $url")
            resolved = url
            onUrlChanged?.invoke(url)
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
        // our backend, so http://192.168.31.234/ would be wrong.
        const val LAN_URL = "http://192.168.31.234:8088/"
        const val LAN_HOST = "192.168.31.234"
        const val LAN_PORT = 8088

        // v1.6.23: IPv6 direct URL — bypasses Cloudflare Tunnel when
        // the phone has IPv6 connectivity. The NAS 8088 port is bound
        // to both IPv4 and IPv6 via docker-proxy (compose.yaml
        // dual-stack "0.0.0.0:8088:80" + "[::]:8088:80"). docker-proxy
        // handles the IPv6→IPv4 translation to the container's
        // internal 0.0.0.0:80, so nginx needs no IPv6 config.
        //
        // The IPv6 address is the NAS's SLAAC EUI-64 address (stable
        // across reboots; only the /64 prefix rotates on ISP DHCPv6-PD
        // renewal). If the prefix changes:
        //   1. Find the new address: ssh fnos-momo@192.168.31.234
        //      'ip -6 addr show enp4s0 | grep "scope global" |
        //       grep -v temporary'
        //   2. Update IPV6_DIRECT_URL here.
        //   3. Update go2rtc's config.yml webrtc.candidates entry.
        //   4. Update compose.yaml NAS_IPV6_ADDRESS env var.
        //   5. Rebuild app + restart frigate container.
        //
        // Why http:// (not https://): the NAS doesn't have a valid
        // TLS certificate for its bare IPv6 address, and acquiring
        // one isn't possible (Let's Encrypt can't issue certs for
        // raw IP addresses without DNS-01 challenge + AAAA record).
        // Cleartext HTTP is acceptable here because:
        //   - The JWT token is the only sensitive payload, and it's
        //     already transmitted in cleartext on the LAN URL too.
        //   - IPv6 traffic is end-to-end (no Cloudflare MITM), so
        //     it's actually MORE private than the Tunnel path despite
        //     lacking TLS.
        //   - The app already has usesCleartextTraffic=true for the
        //     LAN URL.
        const val IPV6_DIRECT_URL = "http://[2409:8a70:37a3:99d0:62be:b4ff:fe08:bd09]:8088/"

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

        // LAN probe timeout for async re-probes. Generous enough that
        // a busy Wi-Fi router still answers, tight enough that the
        // probe thread doesn't linger when off-LAN.
        private const val LAN_TIMEOUT_MS = 1_500

        // v1.6.23: IPv6 direct probe timeout. OkHttp fails immediately
        // (NoRouteToHostException) if the phone has no IPv6 route, so
        // this timeout only fires when the phone HAS IPv6 but the NAS
        // is unreachable (e.g. NAS offline, prefix rotated). 2s is
        // enough for a cross-carrier IPv6 TCP handshake (typical
        // 100-500ms on Chinese cellular IPv6).
        private const val IPV6_TIMEOUT_MS = 2_000

        // v1.6.23: remote probe timeout shortened 8s → 4s. The Tunnel
        // is the last-resort fallback; if it's truly unreachable the
        // user has bigger problems than which URL we picked. 4s is
        // enough for a cold Cloudflare Tunnel connection (typical
        // 1-2s, worst ~3s on Chinese cellular). The previous 8s
        // timeout made the startup probe feel sluggish when both LAN
        // and IPv6 were unavailable.
        private const val REMOTE_TIMEOUT_MS = 4_000

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
