package com.homedatacenter.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homedatacenter.app.data.api.HomeCenterApi
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.AlertListData
import com.homedatacenter.app.data.model.ApiResponse
import com.homedatacenter.app.data.model.NetworkStatus
import com.homedatacenter.app.data.model.SecurityGuard
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemLogListData
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.data.model.WeatherResponse
import com.homedatacenter.app.data.repository.HomeCenterRepository
import com.homedatacenter.app.util.BaseUrlResolver
import com.homedatacenter.app.util.CacheManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * v1.10.0 (P2-3): owns the dashboard's polled DATA. The fragment keeps
 * the rendering (stat cards, weather UI, network card), the 30s
 * polling loop, the WebSocket handlers, and the path-chip update;
 * this ViewModel owns the three data acquisitions â weather, system
 * status, network status â with the same cache-first / silent-refresh
 * behavior the fragment previously implemented inline.
 *
 * The fragment observes [weather], [weatherLoading], [weatherFailed],
 * [systemStatus], [networkStatus], [networkStatusFailed] to paint UI.
 */
class DashboardViewModel(
    app: Application,
    private val repository: HomeCenterRepository,
    private val baseUrlResolver: BaseUrlResolver,
    private val okHttpClient: OkHttpClient,
    apiOverride: HomeCenterApi? = null,
) : AndroidViewModel(app) {

    private val api: HomeCenterApi by lazy {
        apiOverride ?: NetworkFactory.createApi(baseUrlResolver.current(), okHttpClient)
    }

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<SystemLog>>(emptyList())
    val recentLogs: StateFlow<List<SystemLog>> = _recentLogs.asStateFlow()

    private val _liveAlert = MutableStateFlow<Alert?>(null)
    val liveAlert: StateFlow<Alert?> = _liveAlert.asStateFlow()

    private val _quotaAlert = MutableStateFlow<String?>(null)
    val quotaAlert: StateFlow<String?> = _quotaAlert.asStateFlow()

    private val _automationSummary = MutableStateFlow("0 个规则生效中 · 共 0 个")
    val automationSummary: StateFlow<String> = _automationSummary.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _backendPathText = MutableStateFlow("局域网")
    val backendPathText: StateFlow<String> = _backendPathText.asStateFlow()

    private val _backendPathIsFast = MutableStateFlow(true)
    val backendPathIsFast: StateFlow<Boolean> = _backendPathIsFast.asStateFlow()

    private val _weather = MutableStateFlow<WeatherResponse?>(null)
    val weather: StateFlow<WeatherResponse?> = _weather.asStateFlow()

    private val _weatherLoading = MutableStateFlow(false)
    val weatherLoading: StateFlow<Boolean> = _weatherLoading.asStateFlow()

    private val _weatherFailed = MutableStateFlow(false)
    val weatherFailed: StateFlow<Boolean> = _weatherFailed.asStateFlow()

    private val _systemStatus = MutableStateFlow<SystemStatus?>(null)
    val systemStatus: StateFlow<SystemStatus?> = _systemStatus.asStateFlow()

    private val _systemStatusLoading = MutableStateFlow(false)
    val systemStatusLoading: StateFlow<Boolean> = _systemStatusLoading.asStateFlow()

    private val _networkStatus = MutableStateFlow<NetworkStatus?>(null)
    val networkStatus: StateFlow<NetworkStatus?> = _networkStatus.asStateFlow()

    private val _networkStatusLoading = MutableStateFlow(false)
    val networkStatusLoading: StateFlow<Boolean> = _networkStatusLoading.asStateFlow()

    private val _networkStatusFailed = MutableStateFlow(false)
    val networkStatusFailed: StateFlow<Boolean> = _networkStatusFailed.asStateFlow()

    private val _securityGuard = MutableStateFlow<com.homedatacenter.app.data.model.SecurityGuard?>(null)
    val securityGuard: StateFlow<com.homedatacenter.app.data.model.SecurityGuard?> = _securityGuard.asStateFlow()

    private val _securityGuardLoading = MutableStateFlow(false)
    val securityGuardLoading: StateFlow<Boolean> = _securityGuardLoading.asStateFlow()

    // v1.6.30: force refresh=true on the FIRST network status fetch so
    // the initial Dashboard shows current network quality instead of
    // up to 60s of backend cache staleness. The ViewModel dies with
    // the fragment, so a fresh fragment re-forces the refresh.
    private var firstNetworkFetchDone = false

    /** Cache-first paint, then a silent network refresh (onResume /
     *  tab show). Weather uses the raw OkHttp request the fragment
     *  previously made: GET {base}api/v1/weather with the bearer
     *  token, unwrapping the standard {code, message, data} envelope.
     *  Failure is surfaced through [weatherFailed]; the fragment
     *  shows the error text. [onComplete] fires when the attempt
     *  finishes (success or failure), mirroring the old inline code.
     */
    fun loadWeather(token: String?, baseUrl: String, onComplete: (() -> Unit)? = null) {
        if (token.isNullOrEmpty()) {
            onComplete?.invoke()
            return
        }
        val app = getApplication<Application>()
        if (_weather.value == null) {
            CacheManager.getInstance(app)
                .get<WeatherResponse>("dashboard.weather", 300_000L)
                ?.let { _weather.value = it }
        }
        val hasData = _weather.value != null
        viewModelScope.launch {
            if (!hasData) _weatherLoading.value = true
            _weatherFailed.value = false
            try {
                val weather = repository.getWeather(token)
                    ?: throw RuntimeException("empty weather data")
                _weather.value = weather
                CacheManager.getInstance(app).set("dashboard.weather", weather)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!hasData) _weatherFailed.value = true
            } finally {
                _weatherLoading.value = false
                onComplete?.invoke()
            }
        }
    }

    /** Cache-first paint, then a silent network refresh. Measures the
     *  API call RTT and feeds it back to [BaseUrlResolver] so the
     *  network quality card shows steady-state latency. [onComplete]
     *  stops the fragment's swipe refresh when invoked from refreshAll.
     */
    fun refreshSystemStatus(token: String?, onComplete: (() -> Unit)? = null) {
        if (token.isNullOrEmpty()) {
            onComplete?.invoke()
            return
        }
        val app = getApplication<Application>()
        if (_systemStatus.value == null) {
            CacheManager.getInstance(app)
                .get<SystemStatus>("dashboard.status", 300_000L)
                ?.let { _systemStatus.value = it }
        }
        val hasData = _systemStatus.value != null
        viewModelScope.launch {
            if (!hasData) _systemStatusLoading.value = true
            try {
                val apiStart = System.currentTimeMillis()
                val status = repository.getSystemStatus(
                    token = token,
                    useCache = false,
                    refreshCache = true,
                )
                val apiElapsed = System.currentTimeMillis() - apiStart
                baseUrlResolver.updateRttFromApiCall(apiElapsed)
                _systemStatus.value = status
                CacheManager.getInstance(app).set("dashboard.status", status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // keep cached; nothing to paint on failure
            } finally {
                _systemStatusLoading.value = false
                onComplete?.invoke()
            }
        }
    }

    /** Cache-first paint, then a silent network refresh. The FIRST
     *  fetch after fragment creation forces refresh=true; failure is
     *  surfaced through [networkStatusFailed] so the fragment can
     *  paint the error card.
     */
    fun refreshNetworkStatus(token: String?) {
        if (token.isNullOrEmpty()) return
        val app = getApplication<Application>()
        if (_networkStatus.value == null) {
            CacheManager.getInstance(app)
                .get<NetworkStatus>("network.status", 300_000L)
                ?.let { _networkStatus.value = it }
        }
        val hasData = _networkStatus.value != null
        viewModelScope.launch {
            if (!hasData) _networkStatusLoading.value = true
            _networkStatusFailed.value = false
            try {
                val forceRefresh = !firstNetworkFetchDone
                firstNetworkFetchDone = true
                val status = repository.getNetworkStatus(
                    token,
                    refresh = forceRefresh,
                )
                _networkStatus.value = status
                CacheManager.getInstance(app).set("network.status", status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!hasData) _networkStatusFailed.value = true
            } finally {
                _networkStatusLoading.value = false
            }
        }
    }

    fun refreshSecurityGuard(token: String?) {
        if (token.isNullOrEmpty()) return
        viewModelScope.launch {
            _securityGuardLoading.value = true
            try {
                val guard = repository.getSecurityGuard(token)
                _securityGuard.value = guard
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                _securityGuardLoading.value = false
            }
        }
    }

    fun setSecurityGuard(
        token: String?,
        mode: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (token.isNullOrEmpty()) return
        viewModelScope.launch {
            try {
                val guard = repository.setSecurityGuard(token, mode)
                _securityGuard.value = guard
                onSuccess?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError?.invoke(e.message ?: "设置安防模式失败")
            }
        }
    }

    fun cleanSystemCache(
        token: String?,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (token.isNullOrEmpty()) return
        viewModelScope.launch {
            try {
                repository.cleanSystemCache(token)
                refreshSystemStatus(token)
                onSuccess?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError?.invoke(e.message ?: "清理转码缓存失败")
            }
        }
    }

    fun loadRecentAlerts(token: String?) {
        if (token.isNullOrEmpty()) return
        val app = getApplication<Application>()
        if (_alerts.value.isEmpty()) {
            CacheManager.getInstance(app).get<List<Alert>>("dashboard.alerts", 300_000L)?.let {
                _alerts.value = it
            }
        }
        viewModelScope.launch {
            try {
                val resp = api.listAlerts("Bearer $token", limit = 5)
                val alertsList = if (resp.isSuccess) {
                    resp.decodeData<AlertListData>()?.alerts ?: emptyList()
                } else {
                    emptyList()
                }
                _alerts.value = alertsList
                CacheManager.getInstance(app).set("dashboard.alerts", alertsList)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun loadRecentLogs(token: String?, isAdmin: Boolean) {
        if (token.isNullOrEmpty() || !isAdmin) return
        val app = getApplication<Application>()
        if (_recentLogs.value.isEmpty()) {
            CacheManager.getInstance(app).get<List<SystemLog>>("dashboard.recent_logs", 300_000L)?.let {
                _recentLogs.value = it
            }
        }
        viewModelScope.launch {
            try {
                val resp = repository.listSystemLogs(token, limit = 5, offset = 0)
                _recentLogs.value = resp.logs
                CacheManager.getInstance(app).set("dashboard.recent_logs", resp.logs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun loadAutomationSummary(token: String?, isAdmin: Boolean) {
        if (token.isNullOrEmpty() || !isAdmin) return
        viewModelScope.launch {
            try {
                val rules = repository.listAutomationRules(token)
                val activeCount = rules.count { it.enabled }
                _automationSummary.value = "${activeCount} 个规则生效中 · 共 ${rules.size} 个"
            } catch (_: Exception) {
            }
        }
    }

    fun updateBackendPath() {
        val isLan = baseUrlResolver.isLan()
        val isIpv6 = baseUrlResolver.isIpv6Direct()
        val isH3c = baseUrlResolver.isH3cFast()
        val rtt = baseUrlResolver.currentRttMs()
        val rttStr = if (rtt >= 0) " (${rtt}ms)" else ""
        _backendPathText.value = when {
            isLan -> "局域网$rttStr"
            isH3c -> "国内高速$rttStr"
            isIpv6 -> "IPv6 直连$rttStr"
            else -> "远程$rttStr"
        }
        _backendPathIsFast.value = isLan || isH3c
    }

    fun setLiveAlert(alert: Alert?) {
        _liveAlert.value = alert
    }

    fun setQuotaAlert(message: String?) {
        _quotaAlert.value = message
    }

    fun prependLiveAlert(alert: Alert) {
        val current = _alerts.value.toMutableList()
        current.add(0, alert)
        if (current.size > 5) {
            _alerts.value = current.subList(0, 5)
        } else {
            _alerts.value = current
        }
    }

    fun prependSystemLog(log: SystemLog) {
        val current = _recentLogs.value.toMutableList()
        current.add(0, log)
        if (current.size > 5) {
            _recentLogs.value = current.subList(0, 5)
        } else {
            _recentLogs.value = current
        }
    }

    fun updateSystemStatus(status: SystemStatus) {
        _systemStatus.value = status
    }

    fun refreshAll(token: String?, baseUrl: String, isAdmin: Boolean, isUserTriggered: Boolean = false) {
        viewModelScope.launch {
            if (isUserTriggered) {
                _isRefreshing.value = true
            }
            updateBackendPath()
            loadWeather(token, baseUrl)
            refreshNetworkStatus(token)
            loadRecentAlerts(token)
            loadRecentLogs(token, isAdmin)
            refreshSecurityGuard(token)
            loadAutomationSummary(token, isAdmin)
            refreshSystemStatus(token) {
                _isRefreshing.value = false
            }
        }
    }
}