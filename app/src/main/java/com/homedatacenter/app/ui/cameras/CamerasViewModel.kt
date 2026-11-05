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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Filter state data class for alert stream in Cameras screen.
 */
data class AlertFilterState(
    val cameraId: Long? = null,
    val label: String? = null,
    val days: Int = 0 // 0 = all, 1 = today, 3 = last 3 days, 7 = last 7 days
) {
    val hasActiveFilter: Boolean get() = cameraId != null || label != null || days != 0

    fun reset(): AlertFilterState = AlertFilterState()
}

/**
 * CamerasViewModel — owns camera list, alert list pagination,
 * responsive alert filters, and cross-screen scroll trigger events.
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

    // --- "全部报警" pagination and reactive filter state ---

    private val _alerts = MutableStateFlow<List<Alert>?>(null)
    val alerts: StateFlow<List<Alert>?> = _alerts.asStateFlow()

    private val _alertsLoading = MutableStateFlow(false)
    val alertsLoading: StateFlow<Boolean> = _alertsLoading.asStateFlow()

    private val _alertsHasMore = MutableStateFlow(true)
    val alertsHasMore: StateFlow<Boolean> = _alertsHasMore.asStateFlow()

    private val _filterState = MutableStateFlow(AlertFilterState())
    val filterState: StateFlow<AlertFilterState> = _filterState.asStateFlow()

    // One-shot scroll trigger channel for Dashboard navigation to Alerts section
    private val _scrollToAlertsEvent = Channel<Unit>(Channel.BUFFERED)
    val scrollToAlertsEvent = _scrollToAlertsEvent.receiveAsFlow()

    fun triggerScrollToAlerts() {
        _scrollToAlertsEvent.trySend(Unit)
    }

    private var alertsLimit = 20

    val filterCameraId: Long? get() = _filterState.value.cameraId
    val filterLabel: String? get() = _filterState.value.label
    val filterDays: Int get() = _filterState.value.days

    fun updateFilters(cameraId: Long?, label: String?, days: Int) {
        _filterState.value = AlertFilterState(cameraId, label, days)
    }

    /** Cache-first paint, then silent refresh (onResume / tab show). */
    fun loadCameras(token: String?) {
        if (token.isNullOrEmpty()) {
            android.util.Log.w("CamerasDebug", "loadCameras called with null or empty token!")
            return
        }
        val cached = CacheManager.getInstance(getApplication())
            .get<List<Camera>>("cameras.list", 300_000L)
        android.util.Log.d("CamerasDebug", "loadCameras cached: ${cached?.size}")
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
                        .get<List<Camera>>("cameras.list", 300_000L)
                    if (!cached.isNullOrEmpty()) {
                        _cameras.value = cached
                    }
                    return@launch
                }

                val cameras = repository.listCameras(
                    token, useCache = false, refreshCache = true
                )
                android.util.Log.d("CamerasDebug", "Loaded ${cameras.size} cameras from network: $cameras")
                _cameras.value = cameras
                CacheManager.getInstance(getApplication()).set("cameras.list", cameras)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("CamerasDebug", "Failed to refresh cameras: ${e.message}", e)
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
                val currentFilters = _filterState.value
                val afterTs: Long? = if (currentFilters.days > 0) {
                    val cal = java.util.Calendar.getInstance()
                    if (currentFilters.days == 1) {
                        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                        cal.set(java.util.Calendar.MINUTE, 0)
                        cal.set(java.util.Calendar.SECOND, 0)
                        cal.set(java.util.Calendar.MILLISECOND, 0)
                    } else {
                        cal.add(java.util.Calendar.DAY_OF_YEAR, -currentFilters.days)
                    }
                    cal.timeInMillis / 1000L
                } else null

                val resp = api.listAlerts(
                    "Bearer $token",
                    limit = alertsLimit,
                    cameraId = currentFilters.cameraId,
                    label = currentFilters.label,
                    after = afterTs
                )
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
