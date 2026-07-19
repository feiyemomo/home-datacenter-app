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
 * Two candidates are probed in order:
 *  1. LAN URL  http://192.168.31.234:8088/   — when the device is on the
 *     home network this is ~10ms TTFB vs the Cloudflare Tunnel's
 *     measured 1.4s TTFB (with 10s+ timeouts on ~1/3 of requests
 *     from China). For live HLS/MP4 streaming this is the difference
 *     between "instant" and "spinner forever".
 *  2. Remote URL https://api.feiyemomo.top/   — Cloudflare Tunnel,
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
        // Probe LAN first with a short timeout. If it's reachable,
        // we're almost certainly on the home network — switch to it.
        // The Cloudflare Tunnel is the fallback for off-LAN access.
        //
        // Two-pronged probe: HTTP GET (real API reachability check)
        // plus a raw TCP socket connect (fallback for cases where
        // OkHttp's HTTP stack rejects cleartext or fails on certain
        // Android ROMs but the host is actually reachable). Either
        // succeeding is enough — the next API call will use the
        // resolved URL, and if HTTP fails at call time the
        // repository's retry/fallback logic handles it.
        val lanHttpOk = probeUrl(LAN_URL, LAN_TIMEOUT_MS)
        val lanTcpOk = if (!lanHttpOk) probeTcp(LAN_HOST, LAN_PORT, LAN_TIMEOUT_MS) else false
        val lanAlive = lanHttpOk || lanTcpOk
        android.util.Log.i(
            TAG,
            "probeSync: LAN http=$lanHttpOk tcp=$lanTcpOk alive=$lanAlive (resolved=$resolved)",
        )
        val winner = when {
            lanAlive -> LAN_URL
            probeUrl(REMOTE_URL, REMOTE_TIMEOUT_MS) -> REMOTE_URL
            else -> null
        }
        if (winner != null && winner != resolved) {
            resolved = winner
            android.util.Log.i(TAG, "probeSync: switching resolved → $winner")
            onUrlChanged?.invoke(winner)
        }
        lastProbedAt = System.currentTimeMillis()
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

        // Remote probe timeout. The Cloudflare Tunnel can take a few
        // seconds on a cold connection, so this is longer than the LAN
        // probe. We don't want to wait too long — if the remote is
        // truly unreachable the user has bigger problems than which
        // URL we picked.
        private const val REMOTE_TIMEOUT_MS = 8_000

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
