package com.homedatacenter.app.ui.cameras

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
 *  - [cameras]    : the list to render (null until first load).
 *  - [refreshing] : drives SwipeRefreshLayout.isRefreshing.
 *
 * Behavior mirrors the pre-ViewModel implementation exactly: cache
 * paint first (30s TTL), then a silent network refresh; when offline,
 * fall back to cache; on network failure, keep whatever is showing.
 */
class CamerasViewModel(
    app: Application,
    private val repository: HomeCenterRepository,
) : AndroidViewModel(app) {

    private val _cameras = MutableStateFlow<List<Camera>?>(null)
    val cameras: StateFlow<List<Camera>?> = _cameras.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

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
                // Network failure: keep cached data
            } finally {
                _refreshing.value = false
            }
        }
    }
}