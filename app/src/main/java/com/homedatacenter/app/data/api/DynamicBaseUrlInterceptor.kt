package com.homedatacenter.app.data.api

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Dynamically rewrites HTTP requests to the currently active base URL
 * (LAN, domestic H3C tunnel, IPv6 direct, or Cloudflare remote tunnel).
 *
 * This allows all existing Retrofit instances, ViewModels, Coil image
 * loaders, and repositories to seamlessly track network path switches
 * and tunnel port rotations without needing to rebuild or re-inject
 * ViewModel instances.
 */
class DynamicBaseUrlInterceptor(
    private val baseUrlProvider: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Allow probes, direct health checks, or explicit bypasses
        if (request.header(HEADER_SKIP_DYNAMIC_BASE_URL) != null ||
            request.header("X-Probe-Request") != null ||
            request.url.encodedPath == "/fast-status"
        ) {
            return chain.proceed(request)
        }

        val targetBaseUrlStr = baseUrlProvider().trim()
        if (targetBaseUrlStr.isBlank()) {
            return chain.proceed(request)
        }

        val targetUrl = targetBaseUrlStr.toHttpUrlOrNull()
        if (targetUrl == null) {
            Log.w(TAG, "Invalid target base URL: $targetBaseUrlStr")
            return chain.proceed(request)
        }

        val currentUrl = request.url

        // Check if the request is destined for the home datacenter backend.
        // It matches if:
        // 1. Path is a known backend route: /api/, /go2rtc/, /frigate/, /uploads/, /static/
        // 2. OR host is one of the known datacenter hosts/IPs:
        //    "154.8.195.220", "192.168.31.234", "192.168.31.235", "api.feiyemomo.top", "nas.feiyemomo.top"
        val path = currentUrl.encodedPath
        val isBackendPath = path.startsWith("/api/") ||
                path.startsWith("/go2rtc/") ||
                path.startsWith("/frigate/") ||
                path.startsWith("/uploads/") ||
                path.startsWith("/static/")
        val isKnownHost = isDatacenterHost(currentUrl.host)

        if (!isBackendPath && !isKnownHost) {
            // Not a backend request, do not rewrite
            return chain.proceed(request)
        }

        // Check if already matching the target scheme, host, and port
        val schemeMatches = currentUrl.scheme.equals(targetUrl.scheme, ignoreCase = true)
        val hostMatches = currentUrl.host.equals(targetUrl.host, ignoreCase = true)
        val portMatches = currentUrl.port == targetUrl.port

        if (schemeMatches && hostMatches && portMatches) {
            // Already targeting current active base URL
            return chain.proceed(request)
        }

        // Rewrite scheme, host, and port while keeping original path and query
        val newUrl = currentUrl.newBuilder()
            .scheme(targetUrl.scheme)
            .host(targetUrl.host)
            .port(targetUrl.port)
            .build()

        val hostHeader = if ((targetUrl.scheme == "http" && targetUrl.port == 80) ||
            (targetUrl.scheme == "https" && targetUrl.port == 443)
        ) {
            targetUrl.host
        } else {
            "${targetUrl.host}:${targetUrl.port}"
        }

        val newRequest = request.newBuilder()
            .url(newUrl)
            .header("Host", hostHeader)
            .build()

        Log.d(TAG, "Rewrote base URL: ${currentUrl.scheme}://${currentUrl.host}:${currentUrl.port} -> ${targetUrl.scheme}://${targetUrl.host}:${targetUrl.port} for ${currentUrl.encodedPath}")

        return chain.proceed(newRequest)
    }

    private fun isDatacenterHost(host: String): Boolean {
        return host.equals("api.feiyemomo.top", ignoreCase = true) ||
                host.equals("nas.feiyemomo.top", ignoreCase = true) ||
                host == "154.8.195.220" ||
                host == "192.168.31.234" ||
                host == "192.168.31.235" ||
                host.startsWith("192.168.") ||
                host.startsWith("100.")
    }

    companion object {
        private const val TAG = "DynamicBaseUrl"
        const val HEADER_SKIP_DYNAMIC_BASE_URL = "X-Skip-Dynamic-Base-Url"
    }
}
