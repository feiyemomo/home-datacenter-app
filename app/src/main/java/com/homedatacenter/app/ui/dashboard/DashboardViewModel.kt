package com.homedatacenter.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.ApiResponse
import com.homedatacenter.app.data.model.NetworkStatus
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
) : AndroidViewModel(app) {

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
        CacheManager.getInstance(app)
            .get<WeatherResponse>("dashboard.weather", 30_000L)
            ?.let { _weather.value = it }
        viewModelScope.launch {
            _weatherLoading.value = true
            _weatherFailed.value = false
            try {
                val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                val url = base + "api/v1/weather"
                val req = Request.Builder().url(url).apply {
                    addHeader("Authorization", "Bearer $token")
                }.build()
                val jsonStr = withContext(Dispatchers.IO) {
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                        resp.body?.string() ?: throw RuntimeException("empty body")
                    }
                }
                val apiResp = NetworkFactory.json.decodeFromString(
                    ApiResponse.serializer(), jsonStr)
                val weather = apiResp.decodeData<WeatherResponse>()
                    ?: throw RuntimeException("empty weather data")
                _weather.value = weather
                CacheManager.getInstance(app).set("dashboard.weather", weather)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _weatherFailed.value = true
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
        CacheManager.getInstance(app)
            .get<SystemStatus>("dashboard.status", 30_000L)
            ?.let { _systemStatus.value = it }
        viewModelScope.launch {
            _systemStatusLoading.value = true
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
        CacheManager.getInstance(app)
            .get<NetworkStatus>("network.status", 30_000L)
            ?.let { _networkStatus.value = it }
        viewModelScope.launch {
            _networkStatusLoading.value = true
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
                _networkStatusFailed.value = true
            } finally {
                _networkStatusLoading.value = false
            }
        }
    }
}