package com.homedatacenter.app.ui.network

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.IPv6Status
import com.homedatacenter.app.data.model.NatStatus
import com.homedatacenter.app.data.model.NetworkStatus
import com.homedatacenter.app.data.model.P2PStatus
import com.homedatacenter.app.data.model.RelayStatus
import com.homedatacenter.app.data.model.ServerEndpoint
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.databinding.ActivityNetworkDetailBinding
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.util.NetworkPathPreference
import kotlinx.coroutines.launch

/**
 * Network detail page — opens when the user taps the network quality card
 * on the Dashboard.
 *
 * Renders the full NetworkStatus (IPv6 / NAT / P2P / Relay / quality /
 * strategy), the P2P server endpoint from /api/v1/network/p2p/server-endpoint,
 * and the MQTT / WebSocket status from SystemStatus. Swipe-down or the
 * toolbar refresh icon forces a refresh=true request on both endpoints.
 *
 * v1.6.26: also shows the CLIENT's actual path (LAN / IPv6 direct /
 * Tunnel, read from BaseUrlResolver — independent of the backend's
 * strategy field which reflects the SERVER's path) plus the measured
 * RTT, and a 4-way toggle (Auto / LAN / IPv6 / Tunnel) so the user
 * can manually override the path selection. The preference is
 * persisted in SharedPreferences by the resolver and honored on the
 * next probe.
 */
class NetworkDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNetworkDetailBinding
    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.swipeRefresh.setOnRefreshListener { loadAll(forceRefresh = true) }

        // v1.6.26: wire the manual path preference toggle. Doing this
        // in onCreate (not onResume) so the listener is attached once;
        // the button CHECK state is re-synced in onResume.
        setupNetworkPreferenceToggle()

        // Initial render from cache so the user sees something immediately.
        renderFromCache()
        loadAll(forceRefresh = false)
    }

    override fun onResume() {
        super.onResume()
        // v1.6.26: refresh both the toggle selection and the client
        // path display every time the user returns to this page —
        // the preference may have been reset elsewhere (or the
        // resolver may have switched paths due to a network change
        // while the activity was paused).
        syncNetworkPreferenceToggle()
        refreshClientPath()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_refresh -> {
                loadAll(forceRefresh = true)
                // Also refresh the client path chip — forceProbe is
                // async, but currentMethodLabel() reads the cached
                // value immediately and the next onResume will pick
                // up the post-probe value.
                refreshClientPath()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun renderFromCache() {
        container.getRepository().getCachedNetworkStatus()?.let { renderNetwork(it) }
        container.prefsManager.cachedSystemStatus?.let { raw ->
            try {
                val status = container.getRepository().decodeSystemStatus(raw)
                renderSystem(status)
            } catch (_: Exception) {
            }
        }
    }

    private fun loadAll(forceRefresh: Boolean) {
        val token = container.prefsManager.token ?: run {
            binding.swipeRefresh.isRefreshing = false
            binding.tvError.visibility = View.VISIBLE
            return
        }

        lifecycleScope.launch {
            var failed = false
            try {
                val status = container.getRepository().getNetworkStatus(
                    token,
                    refresh = forceRefresh,
                )
                renderNetwork(status)
            } catch (_: Exception) {
                failed = true
            }

            try {
                val system = container.getRepository().getSystemStatus(
                    token,
                    useCache = false,
                    refreshCache = true,
                )
                renderSystem(system)
            } catch (_: Exception) {
                failed = true
            }

            try {
                val endpoint = container.getRepository().getServerEndpoint(token)
                renderServerEndpoint(endpoint)
            } catch (_: Exception) {
                // Server endpoint is only available on LAN (WebRTC probe),
                // suppress the error silently on Remote path.
                renderServerEndpoint(null)
            }

            binding.tvError.visibility = if (failed) View.VISIBLE else View.GONE
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun renderNetwork(s: NetworkStatus) {
        val stars = listOf(binding.star1, binding.star2, binding.star3, binding.star4, binding.star5)
        val q = s.quality.coerceIn(0, 5)
        stars.forEachIndexed { idx, iv ->
            iv.setImageResource(if (idx < q) R.drawable.ic_star_on else R.drawable.ic_star_off)
        }
        binding.tvQualityValue.text = getString(R.string.network_quality_format, q)

        binding.tvInitial.text = "${getString(R.string.network_detail_initial)}: ${strategyLabel(s.initial)}"
        binding.tvActual.text = "${getString(R.string.network_detail_actual)}: ${strategyLabel(s.strategy)}"
        binding.tvCheckedAt.text = "${getString(R.string.network_detail_checked_at)}: ${s.checkedAt.ifBlank { "-" }}"

        renderIPv6(s.ipv6)
        renderNat(s.nat)
        renderP2P(s.p2p)
        renderRelay(s.relay)
    }

    private fun renderIPv6(v6: IPv6Status) {
        binding.tvIpv6Enabled.text = "${getString(R.string.network_detail_ipv6_enabled)}: ${yesNo(v6.enabled)}"
        binding.tvIpv6Reachable.text = "${getString(R.string.network_detail_ipv6_reachable)}: ${yesNo(v6.reachable)}"
        binding.tvIpv6Address.text = "${getString(R.string.network_detail_ipv6_address)}: ${v6.address ?: "-"}"
    }

    private fun renderNat(nat: NatStatus) {
        binding.tvNatType.text = "${getString(R.string.network_detail_nat_type)}: ${nat.type.ifBlank { getString(R.string.network_detail_unknown) }}"
        binding.tvNatPublicIp.text = "${getString(R.string.network_detail_public_ip)}: ${nat.publicIp ?: "-"}"
        binding.tvNatPublicPort.text = "${getString(R.string.network_detail_public_port)}: ${nat.publicPort?.toString() ?: "-"}"
    }

    private fun renderP2P(p2p: P2PStatus) {
        binding.tvP2pSupported.text = "${getString(R.string.network_detail_p2p_supported)}: ${yesNo(p2p.supported)}"
        binding.tvP2pReason.text = "${getString(R.string.network_detail_p2p_reason)}: ${p2p.reason.ifBlank { "-" }}"
    }

    private fun renderRelay(relay: RelayStatus) {
        binding.tvRelayAvailable.text = "${getString(R.string.network_detail_relay_available)}: ${yesNo(relay.available)}"
        binding.tvRelayType.text = "${getString(R.string.network_detail_relay_type)}: ${relay.type.ifBlank { "-" }}"
    }

    private fun renderSystem(system: SystemStatus) {
        val mqttLabel = if (system.mqttConnected) getString(R.string.status_online)
                        else getString(R.string.status_offline)
        binding.tvMqttConnected.text = "${getString(R.string.network_detail_mqtt_connected)}: $mqttLabel"
        binding.tvWsClients.text = "${getString(R.string.network_detail_ws_clients)}: ${system.wsClients}"
        binding.tvUptime.text = "${getString(R.string.network_detail_uptime)}: ${formatUptime(system.uptimeSeconds)}"
    }

    private fun renderServerEndpoint(endpoint: ServerEndpoint?) {
        if (endpoint == null) {
            binding.tvServerIp.text = "${getString(R.string.network_detail_server_ip)}: -"
            binding.tvServerPort.text = "${getString(R.string.network_detail_server_port)}: -"
            binding.tvServerIpv6.text = "${getString(R.string.network_detail_server_ipv6)}: -"
            binding.tvServerNatType.text = "${getString(R.string.network_detail_nat_type)}: -"
            binding.tvServerStrategy.text = "${getString(R.string.network_detail_actual)}: -"
            return
        }
        binding.tvServerIp.text = "${getString(R.string.network_detail_server_ip)}: ${endpoint.publicIp.ifBlank { "-" }}"
        binding.tvServerPort.text = "${getString(R.string.network_detail_server_port)}: ${if (endpoint.publicPort != 0) endpoint.publicPort.toString() else "-"}"
        binding.tvServerIpv6.text = "${getString(R.string.network_detail_server_ipv6)}: ${endpoint.ipv6 ?: "-"}"
        binding.tvServerNatType.text = "${getString(R.string.network_detail_nat_type)}: ${endpoint.natType.ifBlank { getString(R.string.network_detail_unknown) }}"
        binding.tvServerStrategy.text = "${getString(R.string.network_detail_actual)}: ${strategyLabel(endpoint.strategy)}"
    }

    private fun strategyLabel(value: String): String = when (value) {
        "ipv6_direct" -> "IPv6 Direct"
        "p2p" -> "P2P"
        "relay" -> getString(R.string.network_relay)
        else -> value.ifBlank { getString(R.string.network_detail_unknown) }
    }

    private fun yesNo(value: Boolean): String =
        if (value) getString(R.string.network_detail_yes) else getString(R.string.network_detail_no)

    private fun formatUptime(seconds: Long): String {
        if (seconds <= 0) return "-"
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val mins = (seconds % 3600) / 60
        return when {
            days >= 1 -> "${days}天 ${hours}小时"
            hours >= 1 -> "${hours}小时 ${mins}分"
            else -> "${mins}分"
        }
    }

    // --- v1.6.26: client actual path + manual preference toggle ---

    /**
     * Refresh the "客户端实际路径" row from the resolver's current
     * state. Reads [BaseUrlResolver.currentMethodLabel] which returns
     * a string like "局域网 (12ms)" / "IPv6 直连 (45ms)" / "远程 (1400ms)".
     *
     * Safe to call from the main thread — currentMethodLabel() just
     * reads @Volatile fields, no I/O. Called from onResume and after
     * the user changes the preference (the new preference triggers an
     * async forceProbe; this call shows the cached pre-probe value
     * immediately, and the next onResume picks up the post-probe value).
     */
    private fun refreshClientPath() {
        binding.tvClientPath.text = container.baseUrlResolver.currentMethodLabel()
    }

    /**
     * Attach the button-click listener for the preference toggle.
     * Called once from onCreate. The CHECK state of the buttons is
     * synced separately by [syncNetworkPreferenceToggle] in onResume
     * — we don't do that here to avoid clobbering the user's
     * in-progress selection during activity recreation.
     *
     * Listener contract: MaterialButtonToggleGroup fires
     * onButtonChecked for BOTH the button being unchecked AND the
     * button being checked (because singleSelection=true). We only
     * act when isChecked=true (the newly-selected button) to avoid
     * double-applying.
     */
    private fun setupNetworkPreferenceToggle() {
        binding.toggleNetworkPreference.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newPref = when (checkedId) {
                R.id.btnPrefAuto -> NetworkPathPreference.AUTO
                R.id.btnPrefLan -> NetworkPathPreference.LAN
                R.id.btnPrefIpv6 -> NetworkPathPreference.IPV6_DIRECT
                R.id.btnPrefRelay -> NetworkPathPreference.RELAY
                else -> return@addOnButtonCheckedListener
            }
            // Persist + force a re-probe. The probe runs async (~1-2s
            // for LAN, longer for Tunnel) so the "客户端实际路径" row
            // won't update immediately — refreshClientPath() below
            // shows the cached pre-probe value, and the next onResume
            // (or pull-to-refresh) picks up the post-probe value.
            container.baseUrlResolver.setPreference(newPref)
            refreshClientPath()
        }
    }

    /**
     * Sync the toggle's checked button to match the resolver's current
     * preference. Called from onResume so the UI reflects any external
     * preference change (e.g. the user cleared app data, or a future
     * quick-settings tile changed it).
     *
     * Uses [MaterialButtonToggleGroup.check] which both selects the
     * button AND fires the onButtonChecked listener — but our listener
     * is a no-op when setPreference is called with the same value (it
     * early-returns), so there's no infinite loop / spurious re-probe.
     */
    private fun syncNetworkPreferenceToggle() {
        val currentPref = container.baseUrlResolver.getPreference()
        val btnId = when (currentPref) {
            NetworkPathPreference.AUTO -> R.id.btnPrefAuto
            NetworkPathPreference.LAN -> R.id.btnPrefLan
            NetworkPathPreference.IPV6_DIRECT -> R.id.btnPrefIpv6
            NetworkPathPreference.RELAY -> R.id.btnPrefRelay
        }
        // Only call check() if the button isn't already checked —
        // otherwise MaterialButtonToggleGroup logs a benign warning
        // and the listener would fire (and re-persist the same value,
        // which is harmless but wasteful).
        if (binding.toggleNetworkPreference.checkedButtonId != btnId) {
            binding.toggleNetworkPreference.check(btnId)
        }
    }
}
