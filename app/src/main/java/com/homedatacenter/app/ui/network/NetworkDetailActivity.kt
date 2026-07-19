package com.homedatacenter.app.ui.network

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch

/**
 * Network detail page — opens when the user taps the network quality card
 * on the Dashboard.
 *
 * Renders the full NetworkStatus (IPv6 / NAT / P2P / Relay / quality /
 * strategy), the P2P server endpoint from /api/v1/network/p2p/server-endpoint,
 * and the MQTT / WebSocket status from SystemStatus. Swipe-down or the
 * toolbar refresh icon forces a refresh=true request on both endpoints.
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

        // Initial render from cache so the user sees something immediately.
        renderFromCache()
        loadAll(forceRefresh = false)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_refresh -> {
                loadAll(forceRefresh = true)
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
}
