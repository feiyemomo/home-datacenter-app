package com.homedatacenter.app.ui.cameras

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homedatacenter.app.data.api.HomeCenterApi
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.AlertListData
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.repository.HomeCenterRepository
import com.homedatacenter.app.util.CacheManager
import com.homedatacenter.app.util.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * v1.10.0 (P2-2): holds the camera list for CamerasFragment. The
 * fragment no longer talks to the repository / CacheManager directly:
 *
 *  - [cameras]      : the list to render (null until first load).
 *  - [refreshing]   : drives SwipeRefreshLayout.isRefreshing.
 *
 * v1.10.0 (P2-3): also owns the "å¨é¨æ¥è­¦" pagination state and the
 * limit-growing fetch (the API has no offset, so each page grows the
 * limit by 20). The fragment only renders [alerts] and uses
 * [alertsLoading] / [alertsHasMore] to gate scroll-triggered loads.
 */
class CamerasViewModel(
    app: Application,
    private val repository: HomeCenterRepository,
    private val api: HomeCenterApi,
) : AndroidViewModel(app) {

    private val _cameras = MutableStateFlow<List<Camera>?>(null)
    val cameras: StateFlow<List<Camera>?> = _cameras.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // --- "å¨é¨æ¥è­¦" pagination ---

    private val _alerts = MutableStateFlow<List<Alert>?>(null)
    val alerts: StateFlow<List<Alert>?> = _alerts.asStateFlow()

    private val _alertsLoading = MutableStateFlow(false)
    val alertsLoading: StateFlow<Boolean> = _alertsLoading.asStateFlow()

    private val _alertsHasMore = MutableStateFlow(true)
    val alertsHasMore: StateFlow<Boolean> = _alertsHasMore.asStateFlow()

    private var alertsLimit = 20

    /** Cache-first paint, then silent refresh (onResume / tab show). */
    fun loadCameras(token: String?) {
        if (token.isNullOrEmpty()) return
        val cached = CacheManager.getInstance(getApplication())
            .get<List<Camera>>("cameras.list", 30_000L)
        if (!cached.isNullOrEmpty()) {
            _cameras.value = cached
        }
        refreshCameras(token)
    }

    /** Network refresh (swipe-to-refresh, register-camera callback). */
    fun refreshCameras(token: String?) {
        if (token.isNullOrEmpty()) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                // If offline, skip the network call and just show cache.
                if (!NetworkMonitor.getInstance(getApplication()).isOnlineNow()) {
                    val cached = CacheManager.getInstance(getApplication())
                        .get<List<Camera>>("cameras.list", 30_000L)
                    if (!cached.isNullOrEmpty()) {
                        _cameras.value = cached
                    }
                    return@launch
                }

                val cameras = repository.listCameras(
                    token, useCache = false, refreshCache = true
                )
                _cameras.value = cameras
                CacheManager.getInstance(getApplication()).set("cameras.list", cameras)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Network failure: if no cameras loaded yet, emit empty list so UI doesn't hang
                if (_cameras.value == null) {
                    _cameras.value = emptyList()
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Reset pagination and fetch the first page (limit = 20). */
    fun loadAllAlerts(token: String?) {
        if (token.isNullOrEmpty() || _alertsLoading.value) return
        alertsLimit = 20
        _alertsHasMore.value = true
        fetchAlerts(token)
    }

    /** Fetch the next page, growing the limit (API has no offset). */
    fun loadMoreAlerts(token: String?) {
        if (token.isNullOrEmpty() || _alertsLoading.value || !_alertsHasMore.value) return
        fetchAlerts(token)
    }

    private fun fetchAlerts(token: String) {
        viewModelScope.launch {
            _alertsLoading.value = true
            try {
                val resp = api.listAlerts("Bearer $token", limit = alertsLimit)
                val alerts = if (resp.isSuccess) {
                    resp.decodeData<AlertListData>()?.alerts ?: emptyList()
                } else {
                    emptyList()
                }
                _alerts.value = alerts
                _alertsHasMore.value = alerts.size >= alertsLimit
                alertsLimit += 20
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Network failure: keep whatever we already have.
            } finally {
                _alertsLoading.value = false
            }
        }
    }
}