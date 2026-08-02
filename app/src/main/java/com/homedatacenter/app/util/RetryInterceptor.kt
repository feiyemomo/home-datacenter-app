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
 *   - Exponential backoff: 1s → 2s → 4s
 *   - Only retries on IOException subclasses and 5xx responses
 */
class RetryInterceptor(
    private val maxRetries: Int = 3
) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val method = request.method
        
        // Only retry GET requests
        if (method != "GET") {
            return chain.proceed(request)
        }
        
        var lastException: Exception? = null
        var lastResponse: Response? = null
        
        for (attempt in 0 until maxRetries) {
            if (attempt > 0) {
                // Exponential backoff: 1s, 2s, 4s
                val delayMs = (1L shl (attempt - 1)) * 1000L
                Log.d(TAG, "Retry #$attempt for ${request.url.encodedPath} in ${delayMs}ms")
                try {
                    Thread.sleep(delayMs)
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
                    lastResponse = response
                    response.close()
                    Log.w(TAG, "Attempt #${attempt + 1} for ${request.url.encodedPath} returned ${response.code}")
                    continue
                }
                
                // 4xx and other codes - don't retry
                return response
            } catch (e: Exception) {
                lastException = e
                val shouldRetry = e is IOException  // includes SocketTimeoutException, UnknownHostException, etc.
                
                if (shouldRetry && attempt < maxRetries - 1) {
                    Log.w(TAG, "Attempt #${attempt + 1} for ${request.url.encodedPath} failed: ${e.message}")
                } else {
                    // Last attempt or non-retryable exception
                    throw e
                }
            }
        }
        
        // If we got here, all retries failed with 5xx responses
        throw lastException ?: IOException("All $maxRetries attempts failed for ${request.url.encodedPath}")
    }
    
    companion object {
        private const val TAG = "RetryInterceptor"
    }
}