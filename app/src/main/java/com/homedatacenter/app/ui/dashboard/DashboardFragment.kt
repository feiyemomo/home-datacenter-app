package com.homedatacenter.app.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.AlertListData
import com.homedatacenter.app.data.model.NetworkStatus
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.data.model.WeatherResponse
import com.homedatacenter.app.data.model.WsMessage
import com.homedatacenter.app.data.model.WsMessageType
import com.homedatacenter.app.data.ws.HomeCenterWebSocket
import com.homedatacenter.app.data.ws.WsEventListener
import com.homedatacenter.app.databinding.FragmentDashboardBinding
import com.homedatacenter.app.databinding.ItemStatCardBinding
import com.homedatacenter.app.ui.alerts.AlertListAdapter
import com.homedatacenter.app.ui.alerts.AlertSnapshotDialogFragment
import com.homedatacenter.app.ui.cameras.CameraDetailActivity
import com.homedatacenter.app.ui.main.MainActivity
import com.homedatacenter.app.ui.network.NetworkDetailActivity
import com.homedatacenter.app.util.AnimationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var alertAdapter: AlertListAdapter
    private var statusPollingJob: Job? = null
    private var liveAlertDismissJob: Job? = null
    private var dashboardWebSocket: HomeCenterWebSocket? = null
    private var latestSystemStatus: SystemStatus? = null
    // v1.6.0: cache the last live alert so the banner's click handler
    // can jump to its recording at the exact timestamp. Cleared when
    // the banner auto-dismisses.
    private var lastLiveAlert: Alert? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as? MainActivity ?: return
        val baseUrl = mainActivity.container.getApiBaseUrl()
        val token = mainActivity.container.prefsManager.token
        val okHttpClient = mainActivity.container.okHttpClient

        alertAdapter = AlertListAdapter(
            baseUrl = baseUrl,
            token = token,
            okHttpClient = okHttpClient,
            onSnapshotClick = { alert -> showSnapshotDialog(alert) },
            onJumpCamera = { alert -> jumpToCamerasWithAlert(alert) },
            onRowClick = null
        )
        binding.rvAlerts.layoutManager = LinearLayoutManager(context)
        binding.rvAlerts.adapter = alertAdapter

        binding.swipeRefresh.setOnRefreshListener { refreshAll() }
        binding.btnViewAllAlerts.setOnClickListener {
            (activity as? MainActivity)?.let {
                it.binding.bottomNav.selectedItemId = R.id.nav_alerts
            }
        }
        binding.cardNetwork.setOnClickListener {
            startActivity(Intent(requireContext(), NetworkDetailActivity::class.java))
        }

        loadUserName()
        setupDashboardWebSocket()
    }

    override fun onResume() {
        super.onResume()
        if (isAdded && !isHidden) {
            loadUserName()
            refreshAll()
            startStatusPolling()
            connectDashboardWebSocket()
        }
    }

    override fun onPause() {
        stopStatusPolling()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) {
            loadUserName()
            refreshAll()
            startStatusPolling()
            connectDashboardWebSocket()
        } else if (hidden) {
            stopStatusPolling()
        }
    }

    private fun loadUserName() {
        val mainActivity = activity as? MainActivity ?: return
        val prefs = mainActivity.container.prefsManager
        binding.tvUserName.text = prefs.userName ?: getString(R.string.app_name)
    }

    private fun refreshAll() {
        updateBackendPath()
        loadWeather()
        loadNetworkStatus()
        loadRecentAlerts()
        loadSystemStatus(onComplete = {
            if (_binding != null) binding.swipeRefresh.isRefreshing = false
        })
        // v1.5.7: pre-fetch ICE config so the first WebRTC stream
        // doesn't wait for an extra round-trip. Idempotent — the
        // AppContainer skips if already cached.
        (activity as? MainActivity)?.container?.prefetchIceConfig()
    }

    /**
     * Updates the LAN / Remote chip on the network quality card.
     * Reads [BaseUrlResolver.current] from the AppContainer and
     * classifies the resolved URL as either "局域网" (LAN — green dot)
     * or "远程" (Remote — amber dot, indicating the slower Cloudflare
     * Tunnel path). Called on every refresh so the chip reflects any
     * path switch made by the resolver's async re-probe.
     */
    private fun updateBackendPath() {
        val mainActivity = activity as? MainActivity ?: return
        val current = mainActivity.container.baseUrlResolver.current()
        val isLan = current.contains("192.168.") || current.startsWith("http://")
        binding.tvPath.text = if (isLan) "局域网" else "远程"
        binding.pathDot.setBackgroundResource(
            if (isLan) R.drawable.circle_online else R.drawable.circle_warning
        )
    }

    // --- 5-second status polling (matches web Dashboard) ---

    private fun startStatusPolling() {
        if (statusPollingJob?.isActive == true) return
        statusPollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && !isHidden) {
                delay(5_000L)
                loadSystemStatus()
                // Keep the LAN/Remote path chip in sync with the
                // resolver's most recent result. Cheap (just reads a
                // cached string + sets text) — safe to run every poll.
                // The resolver's TTL prevents over-probing; we're just
                // reading the value, not forcing a re-probe.
                updateBackendPath()
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    // --- Weather ---

    private fun loadWeather() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        val baseUrl = mainActivity.container.getApiBaseUrl()
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val url = "${base}api/v1/weather"

        binding.progressWeather.visibility = View.VISIBLE
        binding.tvWeatherError.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val client = mainActivity.container.okHttpClient
                val req = Request.Builder().url(url).apply {
                    if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
                }.build()
                val jsonStr = withContext(Dispatchers.IO) {
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw RuntimeException("HTTP ${resp.code}")
                        }
                        resp.body?.string() ?: throw RuntimeException("empty body")
                    }
                }
                // The weather endpoint wraps wttr.in's response in our
                // standard { code, message, data } envelope, so unwrap
                // `data` before decoding as WeatherResponse.
                val apiResp = NetworkFactory.json.decodeFromString(
                    com.homedatacenter.app.data.model.ApiResponse.serializer(),
                    jsonStr
                )
                val weather = apiResp.decodeData<WeatherResponse>()
                    ?: throw RuntimeException("empty weather data")
                updateWeatherUI(weather)
            } catch (e: Exception) {
                android.util.Log.w("Dashboard", "Weather load failed: ${e.message}")
                binding.tvWeatherError.visibility = View.VISIBLE
                binding.tvWeatherError.text = getString(R.string.weather_failed)
            } finally {
                binding.progressWeather.visibility = View.GONE
            }
        }
    }

    private fun updateWeatherUI(weather: WeatherResponse) {
        val current = weather.currentCondition.firstOrNull() ?: return

        // Fixed location: 宝鸡市陈仓区 (per product requirement, the
        // dashboard always shows this single location regardless of
        // what wttr.in's nearest_area returns).
        binding.tvWeatherLocation.text = "宝鸡市陈仓区"
        binding.tvWeatherTemp.text = "${current.tempC}°"
        binding.tvWeatherDesc.text = current.weatherDesc.firstOrNull()?.value ?: ""

        val extra = buildString {
            append("体感 ${current.feelsLikeC}°")
            append("  湿度 ${current.humidity}%")
            append("  风速 ${current.windSpeedKmph} km/h")
        }
        binding.tvWeatherExtra.text = extra

        // Use local vector icons based on WMO weatherCode (wttr.in
        // returns the standard WMO weather interpretation codes).
        // Cleaner than wttr.in's heavy PNG icons and matches the
        // app's vector design language. See:
        // https://wttr.in/:help (weatherCode column).
        val iconRes = weatherIconFor(current.weatherCode)
        binding.ivWeatherIcon.setImageResource(iconRes)
    }

    /** Map a wttr.in / WMO weatherCode to a local vector drawable. */
    private fun weatherIconFor(weatherCode: String): Int {
        val code = weatherCode.toIntOrNull() ?: return R.drawable.ic_weather_unknown
        return when (code) {
            113 -> R.drawable.ic_weather_clear
            116 -> R.drawable.ic_weather_partly_cloudy
            119, 122 -> R.drawable.ic_weather_cloudy
            143, 248, 260 -> R.drawable.ic_weather_fog
            176, 263, 266, 281, 284, 293, 296, 299, 302, 305, 308,
            311, 314, 317, 350, 353, 356, 359, 362, 365 -> R.drawable.ic_weather_rain
            377, 374, 371, 368, 320, 323, 326, 329, 332, 335, 338,
            341, 344, 347, 392, 395 -> R.drawable.ic_weather_snow
            200, 386, 389 -> R.drawable.ic_weather_thunder
            179, 182, 185, 227, 230, 286, 289 -> R.drawable.ic_weather_heavy_rain
            else -> R.drawable.ic_weather_unknown
        }
    }

    // --- System status (4 stat cards: Devices, MQTT, WS Clients, Uptime) ---

    private fun loadSystemStatus(onComplete: (() -> Unit)? = null) {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return

        // Cached first for instant display
        val cachedStatus = mainActivity.container.prefsManager.cachedSystemStatus
        if (!cachedStatus.isNullOrEmpty()) {
            try {
                val status = NetworkFactory.json.decodeFromString(
                    SystemStatus.serializer(),
                    cachedStatus
                )
                updateStats(status)
            } catch (_: Exception) {
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val status = mainActivity.container.getRepository().getSystemStatus(
                    token = token,
                    useCache = false,
                    refreshCache = true,
                )
                latestSystemStatus = status
                updateStats(status)
            } catch (error: Exception) {
                android.util.Log.w("Dashboard", "System status load failed: ${error.message}")
            } finally {
                onComplete?.invoke()
            }
        }
    }

    private fun updateStats(status: SystemStatus) {
        latestSystemStatus = status

        val devicesCard = ItemStatCardBinding.bind(binding.cardDevices.root)
        devicesCard.tvLabel.text = getString(R.string.stat_devices)
        devicesCard.tvValue.text = status.onlineDeviceCount.toString()
        devicesCard.ivIcon.setImageResource(R.drawable.ic_devices)
        devicesCard.ivIcon.setColorFilter(resources.getColor(R.color.primary, null))
        // Status dot: green if any devices online, gray otherwise.
        applyStatCardDot(devicesCard.statusDot, status.onlineDeviceCount > 0)

        val mqttCard = ItemStatCardBinding.bind(binding.cardMqtt.root)
        mqttCard.tvLabel.text = getString(R.string.stat_mqtt)
        mqttCard.tvValue.text = if (status.mqttConnected)
            getString(R.string.status_online) else getString(R.string.status_offline)
        mqttCard.ivIcon.setImageResource(R.drawable.ic_mqtt)
        mqttCard.ivIcon.setColorFilter(resources.getColor(R.color.online, null))
        applyStatCardDot(mqttCard.statusDot, status.mqttConnected)

        val wsCard = ItemStatCardBinding.bind(binding.cardWs.root)
        wsCard.tvLabel.text = getString(R.string.stat_ws_clients)
        wsCard.tvValue.text = status.wsClients.toString()
        wsCard.ivIcon.setImageResource(R.drawable.ic_ws)
        wsCard.ivIcon.setColorFilter(resources.getColor(R.color.secondary, null))
        // WS clients dot: green if >0 clients, gray otherwise.
        applyStatCardDot(wsCard.statusDot, status.wsClients > 0)

        val uptimeCard = ItemStatCardBinding.bind(binding.cardUptime.root)
        uptimeCard.tvLabel.text = getString(R.string.stat_uptime)
        uptimeCard.tvValue.text = formatUptime(status.uptimeSeconds)
        uptimeCard.ivIcon.setImageResource(R.drawable.ic_dashboard)
        uptimeCard.ivIcon.setColorFilter(resources.getColor(R.color.accent, null))
        // Uptime dot: green if uptime > 1h, yellow otherwise (recently started).
        applyStatCardDot(uptimeCard.statusDot, on = status.uptimeSeconds >= 3_600,
            warning = status.uptimeSeconds in 1 until 3_600)

        // Top status banner — aggregates MQTT + WS + devices into one pill.
        updateStatusBanner(status)
    }

    /** Update the top-right compact status pill based on overall system health. */
    private fun updateStatusBanner(status: SystemStatus) {
        val allOk = status.mqttConnected && status.wsClients > 0 && status.onlineDeviceCount > 0
        val partialOk = status.mqttConnected || status.wsClients > 0 || status.onlineDeviceCount > 0

        val (dotRes, text) = when {
            allOk -> R.drawable.circle_online to getString(R.string.status_online)
            partialOk -> R.drawable.circle_warning to getString(R.string.status_partial)
            else -> R.drawable.circle_error to getString(R.string.status_offline)
        }
        binding.statusBannerDot.setBackgroundResource(dotRes)
        binding.statusBannerText.text = text
    }

    /**
     * Show/hide a stat card's status dot.
     * - [on] = true: green (online), false: gray (offline)
     * - [warning] = true: yellow (degraded) — overrides [on]
     */
    private fun applyStatCardDot(view: View, on: Boolean, warning: Boolean = false) {
        val res = when {
            warning -> R.drawable.circle_warning
            on -> R.drawable.circle_online
            else -> R.drawable.circle_offline
        }
        view.visibility = View.VISIBLE
        view.setBackgroundResource(res)
    }

    /** Format a duration in seconds as "X天 Y小时" / "X小时 Y分" / "X分 Y秒". */
    private fun formatUptime(seconds: Long): String {
        // Compact format: 30d 5h / 5h 12m / 12m 30s / 45s
        // (previously used full Chinese strings like "30天 5小时"
        // which broke the 4-card grid alignment on narrow phones
        // because the long string overflowed the value TextView).
        val s = seconds.coerceAtLeast(0)
        val days = s / 86_400
        val hours = (s % 86_400) / 3_600
        val mins = (s % 3_600) / 60
        return when {
            days >= 1 -> "${days}d ${hours}h"
            hours >= 1 -> "${hours}h ${mins}m"
            else -> "${mins}m ${s % 60}s"
        }
    }

    // --- Network quality card (stars, strategy, IPv6/P2P/Relay dots) ---

    private fun loadNetworkStatus() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return

        // Cached first
        val cached = mainActivity.container.prefsManager.cachedNetworkStatus
        if (!cached.isNullOrEmpty()) {
            try {
                val status = NetworkFactory.json.decodeFromString(
                    NetworkStatus.serializer(),
                    cached
                )
                updateNetworkStatus(status)
            } catch (_: Exception) {
            }
        }

        lifecycleScope.launch {
            try {
                val status = mainActivity.container.getRepository().getNetworkStatus(token)
                updateNetworkStatus(status)
            } catch (e: Exception) {
                android.util.Log.w("Dashboard", "Network status load failed: ${e.message}")
                updateNetworkStatusError()
            }
        }
    }

    private fun updateNetworkStatus(status: NetworkStatus) {
        // Stars (1..5)
        val stars = listOf(binding.star1, binding.star2, binding.star3, binding.star4, binding.star5)
        val q = status.quality.coerceIn(0, 5)
        stars.forEachIndexed { idx, iv ->
            iv.setImageResource(if (idx < q) R.drawable.ic_star_on else R.drawable.ic_star_off)
        }

        // The "当前路径" label shows the ACTUAL client-side path used
        // to reach the backend — read from BaseUrlResolver, not from
        // the backend's `status.strategy` field. The backend's strategy
        // field reflects what the backend probed as reachable FROM THE
        // SERVER'S perspective (P2P / Relay / IPv6 direct), which is
        // independent of how this client is reaching the backend
        // (LAN vs Cloudflare Tunnel). The previous version conflated
        // these two and showed contradictory "P2P + 局域网" pairs.
        // Backend strategy detail has moved to NetworkDetailActivity
        // for users who want to inspect it.
        val mainActivity = activity as? MainActivity
        val currentUrl = mainActivity?.container?.baseUrlResolver?.current().orEmpty()
        val isLan = currentUrl.contains("192.168.") || currentUrl.startsWith("http://")
        val pathLabel = if (isLan) "局域网" else "远程"
        binding.tvNetworkStrategy.text = pathLabel

        // The "↑ upgradable to X" hint still uses the backend's
        // strategy vs initial comparison — that signal is about the
        // backend's reachability, useful as a "you could have better
        // server-side connectivity" hint even when the client is on
        // a fast LAN path.
        val canUpgrade = status.initial != status.strategy &&
            status.initial != "relay" && status.initial.isNotBlank()
        if (canUpgrade) {
            binding.tvNetworkUpgrade.visibility = View.VISIBLE
            binding.tvNetworkUpgrade.text = when (status.initial) {
                "ipv6_direct" -> "↑ " + getString(R.string.network_upgrade_ipv6)
                "p2p" -> "↑ " + getString(R.string.network_upgrade_p2p)
                else -> ""
            }
        } else {
            binding.tvNetworkUpgrade.visibility = View.GONE
        }

        // Capability dots: IPv6, P2P, Relay
        applyDot(binding.dotIPv6, status.ipv6.reachable)
        applyDot(binding.dotP2P, status.p2p.supported)
        applyDot(binding.dotRelay, status.relay.available)
    }

    private fun updateNetworkStatusError() {
        val stars = listOf(binding.star1, binding.star2, binding.star3, binding.star4, binding.star5)
        stars.forEach { it.setImageResource(R.drawable.ic_star_off) }
        binding.tvNetworkStrategy.text = getString(R.string.network_unknown)
        binding.tvNetworkUpgrade.visibility = View.GONE
        applyDot(binding.dotIPv6, false)
        applyDot(binding.dotP2P, false)
        applyDot(binding.dotRelay, false)
    }

    private fun applyDot(view: View, on: Boolean) {
        view.setBackgroundResource(if (on) R.drawable.circle_online else R.drawable.circle_offline)
    }

    // --- Real-time WebSocket events ---

    private fun setupDashboardWebSocket() {
        if (dashboardWebSocket != null) return
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        dashboardWebSocket = HomeCenterWebSocket(
            client = mainActivity.container.okHttpClient,
            wsUrl = mainActivity.container.getWsUrl(),
            token = token,
            listener = object : WsEventListener {
                override fun onConnected() {
                    dashboardWebSocket?.subscribe("device")
                    dashboardWebSocket?.subscribe("camera")
                    dashboardWebSocket?.subscribe("camera.motion")
                }

                override fun onMessage(message: WsMessage) {
                    activity?.runOnUiThread {
                        if (_binding != null) handleWebSocketMessage(message)
                    }
                }

                override fun onDisconnected(code: Int, reason: String?) = Unit

                override fun onError(throwable: Throwable, reconnectAttempt: Int) {
                    android.util.Log.w(
                        "Dashboard",
                        "WebSocket error, reconnect #$reconnectAttempt: ${throwable.message}",
                    )
                }
            },
        )
    }

    private fun connectDashboardWebSocket() {
        setupDashboardWebSocket()
        dashboardWebSocket?.connect()
    }

    private fun handleWebSocketMessage(message: WsMessage) {
        when {
            message.type == WsMessageType.ONLINE_LIST -> applyOnlineList(message)
            message.type != WsMessageType.EVENT -> Unit
            message.topic == "device.status" -> applyDeviceStatus(message)
            message.topic == "camera.online" || message.topic == "camera.offline" -> {
                loadSystemStatus()
            }
            message.topic == "camera.motion" -> showLiveDetection(message)
        }
    }

    private fun applyOnlineList(message: WsMessage) {
        val payload = message.payload ?: return
        val ids = (payload["device_ids"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.longOrNull }
            ?: return
        val current = latestSystemStatus ?: return
        updateStats(
            current.copy(
                onlineDeviceCount = ids.size,
                onlineDeviceIds = ids,
            ),
        )
    }

    private fun applyDeviceStatus(message: WsMessage) {
        val payload = message.payload ?: return
        val deviceId = payload["device_id"]?.jsonPrimitive?.longOrNull ?: return
        val status = payload["status"]?.jsonPrimitive?.contentOrNull ?: return
        val current = latestSystemStatus ?: return
        val ids = current.onlineDeviceIds.orEmpty().toMutableSet()
        if (status == "online" || status == "heartbeat") ids.add(deviceId) else ids.remove(deviceId)
        updateStats(
            current.copy(
                onlineDeviceCount = ids.size,
                onlineDeviceIds = ids.toList(),
            ),
        )
    }

    private fun showLiveDetection(message: WsMessage) {
        val payload = message.payload ?: return
        if (payload["type"]?.jsonPrimitive?.contentOrNull != "detection") return

        val timestamp = payload["ts"]?.jsonPrimitive?.doubleOrNull
            ?: (System.currentTimeMillis() / 1000.0)
        val alert = Alert(
            id = payload["event_id"]?.jsonPrimitive?.contentOrNull
                ?: timestamp.toLong().toString(),
            cameraSlug = payload["camera_slug"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            cameraId = payload["camera_id"]?.jsonPrimitive?.longOrNull,
            cameraName = payload["camera_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            label = payload["label"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            confidence = payload["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            startTime = timestamp,
            endTime = 0.0,
            zones = (payload["zones"] as? JsonArray)?.mapNotNull {
                it.jsonPrimitive.contentOrNull
            }.orEmpty(),
            hasClip = payload["has_clip"]?.jsonPrimitive?.booleanOrNull ?: false,
            hasSnapshot = payload["has_snapshot"]?.jsonPrimitive?.booleanOrNull ?: false,
        )

        binding.tvLiveAlertLabel.text = formatLabel(alert.label)
        binding.tvLiveAlertConfidence.text = "${(alert.confidence * 100).toInt()}%"
        binding.tvLiveAlertCamera.text = buildString {
            append(
                alert.cameraName.ifBlank {
                    alert.cameraSlug.ifBlank {
                        getString(R.string.live_detection_camera_unknown)
                    }
                },
            )
            if (alert.zones.isNotEmpty()) append(" · ${alert.zones.joinToString(", ")}")
        }
        binding.liveAlertBanner.visibility = View.VISIBLE
        AnimationHelper.fadeIn(binding.liveAlertBanner, 250)

        // v1.6.0: cache the alert so the banner's click handler can
        // jump to its recording at the exact timestamp. Also wire
        // the click handler here (idempotent — setOnClickListener
        // replaces any previous listener, so re-binding is safe).
        lastLiveAlert = alert
        binding.liveAlertBanner.setOnClickListener {
            lastLiveAlert?.let { jumpToCamerasWithAlert(it) }
        }

        // Prepend to the alerts list (deduplicated)
        val merged = listOf(alert) + alertAdapter.currentList.filterNot { it.id == alert.id }
        alertAdapter.submitList(merged.take(5))
        binding.tvAlertsEmpty.visibility = View.GONE
        binding.rvAlerts.visibility = View.VISIBLE

        // Auto-dismiss after 8 seconds
        liveAlertDismissJob?.cancel()
        liveAlertDismissJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(8_000L)
            if (_binding != null) {
                binding.liveAlertBanner.visibility = View.GONE
                // v1.6.0: clear the cached alert so a stale click
                // doesn't jump to an outdated timestamp.
                lastLiveAlert = null
            }
        }
    }

    private fun formatLabel(label: String): String {
        val res = when (label.lowercase(Locale.getDefault())) {
            "person" -> R.string.detection_label_person
            "car" -> R.string.detection_label_car
            "truck" -> R.string.detection_label_truck
            "bus" -> R.string.detection_label_bus
            "bicycle" -> R.string.detection_label_bicycle
            "motorcycle" -> R.string.detection_label_motorcycle
            "dog" -> R.string.detection_label_dog
            "cat" -> R.string.detection_label_cat
            "bird" -> R.string.detection_label_bird
            else -> 0
        }
        return if (res != 0) getString(res) else label
    }

    // --- Snapshot modal ---

    private fun showSnapshotDialog(alert: Alert) {
        val mainActivity = activity as? MainActivity ?: return
        val baseUrl = mainActivity.container.getApiBaseUrl()
        val token = mainActivity.container.prefsManager.token
        val client = mainActivity.container.okHttpClient
        val dialog = AlertSnapshotDialogFragment.newInstance(alert, baseUrl, token, client)
        dialog.show(parentFragmentManager, AlertSnapshotDialogFragment.TAG)
    }

    /**
     * v1.6.0: jump from a recent alert row directly to the camera's
     * recording page at the alert's exact timestamp. Fetches the
     * camera list (uses cache), finds the matching camera by id or
     * name, then launches CameraDetailActivity with the camera JSON +
     * alert.startTime as EXTRA_INITIAL_TIMESTAMP so the user lands
     * at the alert's exact moment without manual scrubbing.
     *
     * On failure (no matching camera, network error), shows a toast
     * and falls back to the old behavior of just switching to the
     * cameras tab.
     */
    private fun jumpToCamerasWithAlert(alert: Alert) {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token
        if (token.isNullOrEmpty()) {
            android.widget.Toast.makeText(requireContext(),
                R.string.not_logged_in,
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val startTs = alert.startTime.toLong()

        lifecycleScope.launch {
            try {
                val cameras = mainActivity.container.getRepository()
                    .listCameras(token, useCache = true)
                val cam = cameras.firstOrNull { it.id == alert.cameraId }
                    ?: cameras.firstOrNull { alert.cameraName.isNotEmpty() && it.name == alert.cameraName }
                    ?: cameras.firstOrNull { alert.cameraSlug.isNotEmpty() && it.stream?.streamName == alert.cameraSlug }
                if (cam == null) {
                    android.widget.Toast.makeText(requireContext(),
                        R.string.alert_jump_camera_not_found,
                        android.widget.Toast.LENGTH_SHORT).show()
                    mainActivity.binding.bottomNav.selectedItemId = R.id.nav_cameras
                    return@launch
                }
                val cameraJson = NetworkFactory.json.encodeToString(
                    com.homedatacenter.app.data.model.Camera.serializer(), cam)
                val intent = Intent(requireContext(), CameraDetailActivity::class.java).apply {
                    putExtra(CameraDetailActivity.EXTRA_CAMERA_JSON, cameraJson)
                    putExtra(CameraDetailActivity.EXTRA_INITIAL_TIMESTAMP, startTs)
                }
                startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.w("DashboardFragment",
                    "jumpToCamerasWithAlert failed: ${e.message}", e)
                android.widget.Toast.makeText(requireContext(),
                    R.string.alert_jump_failed,
                    android.widget.Toast.LENGTH_SHORT).show()
                mainActivity.binding.bottomNav.selectedItemId = R.id.nav_cameras
            }
        }
    }

    // --- Recent alerts (last 5) ---

    private fun loadRecentAlerts() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return

        lifecycleScope.launch {
            try {
                val resp = mainActivity.container.getApi().listAlerts("Bearer $token", limit = 5)
                val alerts = if (resp.isSuccess) {
                    resp.decodeData<AlertListData>()?.alerts ?: emptyList()
                } else {
                    emptyList()
                }
                alertAdapter.submitList(alerts)
                binding.tvAlertsEmpty.visibility = if (alerts.isEmpty()) View.VISIBLE else View.GONE
                binding.rvAlerts.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE
                AnimationHelper.fadeIn(binding.rvAlerts, 300)
            } catch (_: Exception) {
                binding.tvAlertsEmpty.visibility = View.VISIBLE
                binding.rvAlerts.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        stopStatusPolling()
        liveAlertDismissJob?.cancel()
        liveAlertDismissJob = null
        dashboardWebSocket?.disconnect()
        dashboardWebSocket = null
        binding.rvAlerts.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
