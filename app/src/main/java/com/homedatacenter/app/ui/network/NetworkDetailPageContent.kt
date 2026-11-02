package com.homedatacenter.app.ui.network

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.NatStatus
import com.homedatacenter.app.data.model.NetworkStatus
import com.homedatacenter.app.data.model.P2PStatus
import com.homedatacenter.app.data.model.RelayStatus
import com.homedatacenter.app.data.model.ServerEndpoint
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.LiquidGlassCard
import com.homedatacenter.app.util.NetworkPathPreference

private val CyanAccent = Color(0xFF38BDF8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailPageContent(
    clientPath: String,
    isProbing: Boolean,
    preference: NetworkPathPreference,
    onPreferenceChange: (NetworkPathPreference) -> Unit,
    networkStatus: NetworkStatus?,
    systemStatus: SystemStatus?,
    serverEndpoint: ServerEndpoint?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBackClick: () -> Unit
) {
    val pullRefreshState = rememberPullToRefreshState()
    val tokens = GlassTheme.tokens
    val buttonBg = if (tokens.isLight) Color(0x14000000) else Color.White.copy(alpha = 0.08f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(buttonBg)
                        .clickable(onClick = onBackClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                        contentDescription = "返回",
                        tint = tokens.textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "网络连接质量",
                    color = tokens.textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(buttonBg)
                    .clickable(onClick = onRefresh),
                contentAlignment = Alignment.Center
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = CyanAccent,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = "刷新",
                        tint = tokens.textColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = pullRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullRefreshState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = if (tokens.isLight) Color.White else Color(0xFF1E293B),
                    color = CyanAccent
                )
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Client Path Card + Override Toggle
                ClientPathCard(
                    clientPath = clientPath,
                    isProbing = isProbing,
                    currentPreference = preference,
                    onPreferenceSelected = onPreferenceChange
                )

                // 2. Network Quality Card
                if (networkStatus != null) {
                    QualityCard(networkStatus = networkStatus)
                    NatCard(nat = networkStatus.nat)
                    P2PAndRelayCard(p2p = networkStatus.p2p, relay = networkStatus.relay)
                }

                // 3. Server Endpoint Card
                serverEndpoint?.let { ep ->
                    ServerEndpointCard(endpoint = ep)
                }

                // 4. Infrastructure Status Card
                systemStatus?.let { sys ->
                    InfrastructureCard(system = sys)
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}

@Composable
private fun ClientPathCard(
    clientPath: String,
    isProbing: Boolean,
    currentPreference: NetworkPathPreference,
    onPreferenceSelected: (NetworkPathPreference) -> Unit
) {
    val tokens = GlassTheme.tokens
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "客户端实际链路",
                    color = tokens.textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isProbing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = CyanAccent,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "链路探测中…",
                            color = CyanAccent,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = clientPath.ifBlank { "正在检测…" },
                color = CyanAccent,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "链路偏好设定",
                color = tokens.textSecondaryColor,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (tokens.isLight) Color(0x0A000000) else Color.White.copy(alpha = 0.05f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PreferenceOption(
                    title = "自动优选",
                    isSelected = currentPreference == NetworkPathPreference.AUTO,
                    modifier = Modifier.weight(1f)
                ) { onPreferenceSelected(NetworkPathPreference.AUTO) }

                PreferenceOption(
                    title = "强制局域网",
                    isSelected = currentPreference == NetworkPathPreference.LAN,
                    modifier = Modifier.weight(1f)
                ) { onPreferenceSelected(NetworkPathPreference.LAN) }

                PreferenceOption(
                    title = "中继/内网穿透",
                    isSelected = currentPreference == NetworkPathPreference.RELAY,
                    modifier = Modifier.weight(1f)
                ) { onPreferenceSelected(NetworkPathPreference.RELAY) }
            }
        }
    }
}

@Composable
private fun PreferenceOption(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tokens = GlassTheme.tokens
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) CyanAccent.copy(alpha = if (tokens.isLight) 0.18f else 0.22f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isSelected) CyanAccent else tokens.textSecondaryColor,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun QualityCard(networkStatus: NetworkStatus) {
    val tokens = GlassTheme.tokens
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "网络连接品质评分",
                color = tokens.textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val q = networkStatus.quality.coerceIn(0, 5)
                for (i in 1..5) {
                    Icon(
                        painter = painterResource(
                            if (i <= q) R.drawable.ic_star_on else R.drawable.ic_star_off
                        ),
                        contentDescription = null,
                        tint = if (i <= q) Color(0xFFFFB703) else tokens.textSecondaryColor.copy(alpha = 0.35f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${q}/5 颗星",
                    color = tokens.textColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            InfoRow("初始探测策略", networkStatus.initial)
            InfoRow("最终选定策略", networkStatus.strategy)
            InfoRow("最近一次检测", formatCheckedAt(networkStatus.checkedAt))
        }
    }
}

@Composable
private fun NatCard(nat: NatStatus) {
    val tokens = GlassTheme.tokens
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "NAT 穿透与公网地址",
                color = tokens.textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            InfoRow("NAT 类型", nat.type.ifBlank { "未知类型" })
            InfoRow("公网 IP", nat.publicIp ?: "未检测到")
            InfoRow("公网映射端口", nat.publicPort?.toString() ?: "-")
        }
    }
}

@Composable
private fun P2PAndRelayCard(p2p: P2PStatus, relay: RelayStatus) {
    val tokens = GlassTheme.tokens
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "P2P 打洞与中继服务",
                color = tokens.textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            InfoRow("P2P 直连可行性", if (p2p.supported) "可行 (UDP打洞)" else "不可行")
            if (!p2p.supported && p2p.reason.isNotBlank()) {
                InfoRow("打洞受限原因", p2p.reason)
            }
            InfoRow("中继服务器状态", if (relay.available) "可用" else "不可用")
            InfoRow("中继通道类型", relay.type.ifBlank { "直连/默认" })
        }
    }
}

@Composable
private fun ServerEndpointCard(endpoint: ServerEndpoint) {
    val tokens = GlassTheme.tokens
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "NAS 服务端公开入口",
                color = tokens.textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            InfoRow("公网 IPv4", endpoint.publicIp.ifBlank { "-" })
            InfoRow("映射端口", if (endpoint.publicPort != 0) endpoint.publicPort.toString() else "-")
            InfoRow("服务端策略", endpoint.strategy.ifBlank { "-" })
        }
    }
}

@Composable
private fun InfrastructureCard(system: SystemStatus) {
    val tokens = GlassTheme.tokens
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "基础设施与长连接",
                color = tokens.textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            InfoRow("MQTT Broker 状态", if (system.mqttConnected) "在线 (就绪)" else "离线")
            InfoRow("WebSocket 在线客户端", "${system.wsClients} 个")
            InfoRow("系统连续运行时间", formatUptime(system.uptimeSeconds))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = tokens.textSecondaryColor,
            fontSize = 13.sp
        )
        Text(
            text = value,
            color = tokens.textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatCheckedAt(iso: String): String {
    if (iso.isBlank()) return "-"
    return try {
        val tIdx = iso.indexOf('T')
        if (tIdx > 0) {
            val date = iso.substring(0, tIdx)
            val timePart = iso.substring(tIdx + 1)
            val secEnd = timePart.indexOf('.').takeIf { it > 0 }
                ?: timePart.indexOf('+').takeIf { it > 0 }
                ?: timePart.indexOf('Z').takeIf { it > 0 }
                ?: timePart.length
            val time = timePart.substring(0, secEnd.coerceAtMost(8))
            "$date $time"
        } else {
            iso.take(19).replace('T', ' ')
        }
    } catch (_: Exception) {
        iso
    }
}

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
