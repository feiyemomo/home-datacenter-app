package com.homedatacenter.app.util

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * OkHttp interceptor that automatically retries GET requests on
 * transient failures (5xx, timeout, DNS failure, connection reset).
 * 
 * Does NOT retry:
 *   - POST, PUT, DELETE, PATCH requests (could cause duplicate side effects)
 *   - 4xx client errors (the request itself is invalid)
 *   - requests that were already sent (the request body might have been consumed)
 * 
 * Retry strategy:
 *   - Max 3 attempts (initial + 2 retries)
 *   - Exponential backoff: 1s → 2s → 4s (injectable via `backoff`)
 *   - Only retries on IOException subclasses and 5xx responses
 */
class RetryInterceptor(
    private val maxRetries: Int = 3,
    // Injectable backoff so tests can pass a no-op and skip the delay;
    // defaults to the 1s → 2s → 4s exponential sleep.
    private val backoff: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val method = request.method
        
        // Only retry GET requests
        if (method != "GET") {
            return chain.proceed(request)
        }
        
        var lastResponse: Response? = null
        
        for (attempt in 0 until maxRetries) {
            if (attempt > 0) {
                // Exponential backoff: 1s, 2s, 4s
                val delayMs = (1L shl (attempt - 1)) * 1000L
                Log.d(TAG, "Retry #$attempt for ${request.url.encodedPath} in ${delayMs}ms")
                try {
                    backoff(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Retry interrupted", e)
                }
            }
            
            try {
                val response = chain.proceed(request)
                if (response.isSuccessful) {
                    return response
                }
                
                // Only retry on 5xx server errors
                if (response.code in 500..599) {
                    if (attempt == maxRetries - 1) {
                        // Last attempt: keep the 5xx response open so the
                        // caller (e.g. HomeCenterRepository) can read the
                        // real server status through ApiResponse instead of
                        // receiving an IOException.
                        lastResponse = response
                    } else {
                        response.close()
                    }
                    Log.w(TAG, "Attempt #${attempt + 1} for ${request.url.encodedPath} returned ${response.code}")
                    continue
                }
                
                // 4xx and other codes - don't retry
                return response
            } catch (e: Exception) {
                val shouldRetry = e is IOException  // includes SocketTimeoutException, UnknownHostException, etc.
                
                if (shouldRetry && attempt < maxRetries - 1) {
                    Log.w(TAG, "Attempt #${attempt + 1} for ${request.url.encodedPath} failed: ${e.message}")
                } else {
                    // Last attempt or non-retryable exception
                    throw e
                }
            }
        }
        
        // If we got here, all retries were exhausted on 5xx responses.
        // Instead of throwing, return the last 5xx Response so the caller
        // (e.g. HomeCenterRepository) can read the real server status and
        // surface it through ApiResponse. lastResponse is always set when
        // maxRetries >= 1; the throw below is only a defensive fallback for
        // the degenerate maxRetries == 0 case.
        Log.w(TAG, "All $maxRetries attempts for ${request.url.encodedPath} returned 5xx; returning last response")
        return lastResponse
            ?: throw IOException("All $maxRetries attempts failed for ${request.url.encodedPath}")
    }
    
    companion object {
        private const val TAG = "RetryInterceptor"
    }
}