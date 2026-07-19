package com.homedatacenter.app.util

import com.homedatacenter.app.data.repository.HomeCenterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Role-based UI gating helper.
 *
 * The backend deliberately does NOT include `is_admin` in the JWT —
 * admin status is checked via DB lookup on every request (so a demoted
 * admin's change takes effect immediately). This means the client must
 * ask the server for the current role rather than reading it from the
 * token.
 *
 * [RoleManager] caches the result of GET /api/v1/user/me in
 * [PrefsManager] and exposes synchronous lookups for UI code:
 *   - [isAdminCached] — best-effort role used to hide admin-only UI
 *     before /me resolves. Defaults to false until refreshed.
 *   - [refresh] — async fetch; should be called from MainActivity
 *     onCreate / onResume so the cache is always fresh when the user
 *     sees the UI.
 *
 * Server-side enforcement remains authoritative — even if the client
 * shows an admin button to a non-admin user, the API call will return
 * 403 "admin only".
 */
class RoleManager(
    private val prefsManager: PrefsManager,
    private val repository: HomeCenterRepository,
) {

    /** Synchronous best-effort admin check. False until first /me resolves. */
    fun isAdminCached(): Boolean = prefsManager.isAdmin

    /** Cached user id from prefs (set at login, may be updated by /me). */
    fun userIdCached(): Long = prefsManager.userId

    /** Fetch /me and update prefs. Safe to call from any thread. */
    suspend fun refresh(token: String) = withContext(Dispatchers.IO) {
        try {
            val user = repository.getMe(token)
            prefsManager.saveUserInfo(user.name, user.isAdmin)
            prefsManager.userId = user.id
            user
        } catch (_: Exception) {
            // Network failure: keep the previously cached role. The
            // server will still enforce admin-only endpoints, so an
            // outdated "true" only risks showing an admin button that
            // will fail with 403 — a recoverable UX bug, not a security
            // issue.
            null
        }
    }
}
