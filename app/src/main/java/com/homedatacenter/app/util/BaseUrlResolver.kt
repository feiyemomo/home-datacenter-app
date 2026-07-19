package com.homedatacenter.app.util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Picks the fastest reachable backend base URL at runtime.
 *
 * Two candidates are probed in order:
 *  1. LAN URL  http://192.168.31.234/   — when the device is on the
 *     home network this is ~10ms TTFB vs the Cloudflare Tunnel's
 *     measured 1.4s TTFB (with 10s+ timeouts on ~1/3 of requests
 *     from China). For live HLS/MP4 streaming this is the difference
 *     between "instant" and "spinner forever".
 *  2. Remote URL https://api.feiyemomo.top/   — Cloudflare Tunnel,
 *     works from any network but slow + lossy from China.
 *
 * Strategy:
 *  - On app launch, call [probeLanOnStartup] once. It runs a quick
 *    synchronous HEAD against the LAN health endpoint with an 800ms
 *    timeout. If reachable, the resolved URL switches immediately so
 *    the first API call goes to the LAN. If not, the default remote
 *    URL stays. The 800ms cap is generous — a TCP connect to a host
 *    on the same LAN takes ~5-10ms, so if the LAN isn't reachable in
 *    800ms it almost certainly isn't the home network.
 *  - On every [current] call, if the cache is older than [TTL_MS]
 *    we kick off an async re-probe so network switches (user walks
 *    out of Wi-Fi range, or comes back home) are picked up within
 *    a few minutes without blocking the calling thread.
 *  - When the resolved URL changes, [onUrlChanged] fires so
 *    AppContainer can invalidate its cached Retrofit/Repository
 *    instances (otherwise the next call would still hit the old URL).
 *
 * The probe target is GET /health on the backend — an intentionally
 * unauthenticated endpoint that returns {"status":"ok"}. Any HTTP
 * response (200, 404, even 405 for HEAD) means the server is alive.
 * Probing happens against the backend API, not against an opaque TCP
 * port, so we know the *API* is reachable rather than just the host.
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
     * Synchronous quick LAN probe. Call once on app launch before
     * any UI loads. Retries up to 3 times with backoff to absorb
     * the common case where Android reports WiFi "connected" but
     * the route isn't yet usable for outbound TCP — on a real phone
     * `Application.onCreate` runs the instant the user taps the icon,
     * and the WiFi stack's validation handshake can still be in
     * flight. The emulator doesn't have this problem because its
     * networking is bridged through the host PC (which is always
     * validated), so the same code works there with one shot.
     *
     * If LAN is reachable on any attempt the resolved URL switches
     * to the LAN URL immediately so the very first API call benefits
     * from LAN speed. Total worst-case wall time: ~5s (3 attempts ×
     * 1500ms timeout + 500ms gaps). Acceptable on app launch.
     */
    fun probeLanOnStartup() {
        repeat(STARTUP_RETRY_COUNT) { attempt ->
            if (probeUrl(LAN_URL, STARTUP_LAN_TIMEOUT_MS)) {
                if (resolved != LAN_URL) {
                    resolved = LAN_URL
                    onUrlChanged?.invoke(LAN_URL)
                }
                lastProbedAt = System.currentTimeMillis()
                return
            }
            // Brief pause before retrying — gives the WiFi stack
            // time to finish validation. Capped so we don't blow
            // past the launcher animation budget (8s before the
            // system shows an ANR dialog for cold start).
            if (attempt < STARTUP_RETRY_COUNT - 1) {
                try {
                    Thread.sleep(STARTUP_RETRY_GAP_MS.toLong())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        // All retries failed — schedule a delayed async re-probe so
        // that late WiFi validation (which can fire 2-5s after the
        // OS reports "connected") still gets a chance to switch us
        // to LAN without waiting for the 5-minute TTL. The
        // NetworkChangeMonitor should also fire onCapabilitiesChanged
        // around the same time, but we kick this off defensively
        // in case the callback is delayed or dropped.
        lastProbedAt = System.currentTimeMillis()
        if (probing.compareAndSet(false, true)) {
            Thread {
                try {
                    Thread.sleep(STARTUP_LATE_REPROBE_DELAY_MS.toLong())
                    probeSync()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    probing.set(false)
                }
            }.apply {
                isDaemon = true
                name = "BaseUrlResolver-startup-late"
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
        val winner = when {
            probeUrl(LAN_URL, LAN_TIMEOUT_MS) -> LAN_URL
            probeUrl(REMOTE_URL, REMOTE_TIMEOUT_MS) -> REMOTE_URL
            else -> null
        }
        if (winner != null && winner != resolved) {
            resolved = winner
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
                    "probe: ${url.trimEnd('/')} → ${response.code} in ${elapsed}ms (${if (alive) "ALIVE" else "DOWN"})",
                )
                alive
            }
        } catch (e: Exception) {
            android.util.Log.w(
                TAG,
                "probe: ${url.trimEnd('/')} → error in ${timeoutMs}ms: ${e.javaClass.simpleName}: ${e.message}",
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

        // Startup LAN probe timeout. Tight on purpose — a same-LAN
        // host answers TCP SYN in ~10ms even when busy, so 1000ms is
        // already 100x headroom. If the phone's WiFi isn't ready
        // within 1000ms the retry loop catches it.
        private const val STARTUP_LAN_TIMEOUT_MS = 1_000

        // Number of startup probe attempts. Two covers the observed
        // "WiFi connected but not validated" window on real phones:
        // attempt 1 fails (validation not done), attempt 2 (after
        // 400ms) typically succeeds. We intentionally keep this low
        // because probeLanOnStartup runs on the main thread during
        // Application.onCreate — total worst-case blocking time must
        // stay well under the 5s ANR threshold (2 attempts × 1s +
        // 400ms gap = 2.4s worst case).
        private const val STARTUP_RETRY_COUNT = 2

        // Gap between startup retries. 400ms is short enough to keep
        // total startup under 2.5s, long enough for the WiFi validator
        // to make progress between attempts.
        private const val STARTUP_RETRY_GAP_MS = 400

        // Delayed async re-probe after all startup retries failed.
        // 3 seconds is long enough for even a slow captive-portal
        // validation to complete; by then onCapabilitiesChanged
        // should have fired too, and this re-probe is just a
        // belt-and-braces fallback.
        private const val STARTUP_LATE_REPROBE_DELAY_MS = 3_000
    }
}
