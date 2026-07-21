package com.homedatacenter.app.util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
     */
    fun isIpv6Direct(): Boolean = resolved == IPV6_DIRECT_URL

    /**
     * v1.6.23: returns true if the resolved URL is a direct path to
     * the NAS (either LAN or IPv6 direct), bypassing Cloudflare Tunnel.
     * Used by WebRTC code to decide whether to skip STUN/TURN servers
     * — direct paths only need host candidates, the Tunnel can't route
     * WebRTC media anyway.
     */
    fun isDirectPath(): Boolean = resolved == LAN_URL || resolved == IPV6_DIRECT_URL

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
     * in flight, the call is a no-op (the in-flight probe will pick
     * up the new network state anyway).
     */
    fun forceProbe() {
        if (!probing.compareAndSet(false, true)) return
        Thread {
            try {
                probeSync()
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
    }

    private fun probeSync() {
        // v1.6.23: three-tier probe — LAN → IPv6 direct → Tunnel.
        //
        // LAN probe is a two-pronged check (HTTP GET + raw TCP socket
        // connect) to work around vendor HTTP policy on some ROMs.
        // IPv6 direct and Tunnel probes use HTTP GET only — no vendor
        // policy issues on HTTPS (Tunnel) or IPv6-literal HTTP (the
        // raw socket fallback was specifically for cleartext HTTP on
        // LAN where MIUI/ColorOS intercepts).
        //
        // Probe order matters: LAN is fastest when available (~10ms),
        // IPv6 direct is next (~50ms when phone has IPv6), Tunnel is
        // the always-works fallback (~1.4s TTFB from China).
        //
        // We cache the IPv6 direct result in [ipv6DirectAvailable]
        // so onNetworkLost() can pick it as the safe default without
        // waiting for a probe — this is what makes the LAN → remote
        // switch feel instant instead of taking 1.5s+ per API call.

        // 1. LAN probe (two-pronged: HTTP + TCP fallback)
        val lanHttpOk = probeUrl(LAN_URL, LAN_TIMEOUT_MS)
        val lanTcpOk = if (!lanHttpOk) probeTcp(LAN_HOST, LAN_PORT, LAN_TIMEOUT_MS) else false
        val lanAlive = lanHttpOk || lanTcpOk
        android.util.Log.i(
            TAG,
            "probeSync: LAN http=$lanHttpOk tcp=$lanTcpOk alive=$lanAlive (resolved=$resolved)",
        )
        if (lanAlive) {
            switchTo(LAN_URL)
            // LAN is alive — reset IPv6 cache (we don't need IPv6
            // when LAN works). Next time network drops, onNetworkLost
            // will fall back to Tunnel until a probe redetects IPv6.
            ipv6DirectAvailable = false
            lastProbedAt = System.currentTimeMillis()
            return
        }

        // 2. IPv6 direct probe. OkHttp will fail immediately
        // (NoRouteToHostException / UnknownHostException) if the
        // phone has no IPv6 connectivity, so this is also an
        // implicit phone-IPv6 check — no separate ConnectivityManager
        // probe needed.
        val ipv6Alive = probeUrl(IPV6_DIRECT_URL, IPV6_TIMEOUT_MS)
        ipv6DirectAvailable = ipv6Alive
        android.util.Log.i(
            TAG,
            "probeSync: IPv6 direct alive=$ipv6Alive (resolved=$resolved)",
        )
        if (ipv6Alive) {
            switchTo(IPV6_DIRECT_URL)
            lastProbedAt = System.currentTimeMillis()
            return
        }

        // 3. Tunnel fallback. v1.6.23: timeout shortened 8s → 4s.
        // The Tunnel is the last resort; if it's truly unreachable
        // the user has bigger problems. 4s is enough for a cold
        // Cloudflare Tunnel connection (typical 1-2s, worst ~3s on
        // Chinese cellular). The previous 8s timeout made the
        // startup probe feel sluggish when both LAN and IPv6 were
        // unavailable.
        val tunnelAlive = probeUrl(REMOTE_URL, REMOTE_TIMEOUT_MS)
        android.util.Log.i(
            TAG,
            "probeSync: Tunnel alive=$tunnelAlive (resolved=$resolved)",
        )
        if (tunnelAlive) {
            switchTo(REMOTE_URL)
        }
        lastProbedAt = System.currentTimeMillis()
    }

    private fun switchTo(url: String) {
        if (resolved != url) {
            android.util.Log.i(TAG, "probeSync: switching resolved → $url")
            resolved = url
            onUrlChanged?.invoke(url)
        }
    }

    private fun probeUrl(url: String, timeoutMs: Int): Boolean {
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
        val probeClient = client.newBuilder()
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url("${url.trimEnd('/')}/api/v1/system/status")
            .get()
            .build()
        return try {
            val sw = System.currentTimeMillis()
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
                alive
            }
        } catch (e: Exception) {
            android.util.Log.w(
                TAG,
                "probeUrl: ${url.trimEnd('/')} → error in ${timeoutMs}ms: ${e.javaClass.simpleName}: ${e.message}",
            )
            false
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
     */
    private fun probeTcp(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            val sw = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val elapsed = System.currentTimeMillis() - sw
            socket.close()
            android.util.Log.i(
                TAG,
                "probeTcp: $host:$port → connected in ${elapsed}ms",
            )
            true
        } catch (e: Exception) {
            android.util.Log.w(
                TAG,
                "probeTcp: $host:$port → ${e.javaClass.simpleName}: ${e.message}",
            )
            false
        }
    }

    companion object {
        private const val TAG = "BaseUrlResolver"

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
        const val IPV6_DIRECT_URL = "http://[2409:8a70:37a0:63f0:62be:b4ff:fe08:bd09]:8088/"

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
