package com.homedatacenter.app.util

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.homedatacenter.app.ui.login.LoginActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.14.2: single place that reacts to "the server says this login is no
 * longer valid" (device deleted / revoked, user deleted, re-bind rejected
 * with HTTP 401).
 *
 * IMPORTANT: only call [onAuthInvalid] when the server actually answered
 * with an auth rejection. Network failures (IOException, timeouts, 5xx)
 * must NEVER log the user out - that is the NAS-offline path.
 *
 * Effects (once per invalidation):
 *   1. clears the persisted auth (token / userId / accessKey ...)
 *   2. flips [isInvalidated] so background loops (token refresh,
 *      keep-alive, update check) stop retrying with dead credentials
 *   3. restarts the task on [LoginActivity] with a
 *      "账号已失效，请重新登录" message
 */
class AuthInvalidHandler(
    private val context: Context,
    private val prefsManager: PrefsManager,
) {

    private val invalidated = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Optional hook (set by AppContainer) to stop background services. */
    @Volatile
    var onInvalidated: (() -> Unit)? = null

    val isInvalidated: Boolean get() = invalidated.get()

    /** Called after a successful login to re-arm the handler. */
    fun reset() {
        invalidated.set(false)
    }

    fun onAuthInvalid(reason: String) {
        // Nothing to invalidate (already logged out) - ignore.
        if (prefsManager.token.isNullOrEmpty() && invalidated.get()) return
        if (!invalidated.compareAndSet(false, true)) return
        Log.w(TAG, "Auth invalidated by server: $reason")
        prefsManager.clearAuth()
        try {
            onInvalidated?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "onInvalidated hook failed: ${e.message}")
        }
        mainHandler.post {
            try {
                val intent = Intent(context, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(LoginActivity.EXTRA_AUTH_INVALID_MESSAGE, MESSAGE)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open LoginActivity: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "AuthInvalidHandler"
        const val MESSAGE = "账号已失效，请重新登录"

        /** 401 messages from the JWT middleware that mean "log in again". */
        val FATAL_401_MESSAGES = setOf(
            "device not found",
            "device revoked",
            "missing authorization header",
        )
    }
}
