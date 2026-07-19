package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NetworkStatus(
    val ipv6: IPv6Status = IPv6Status(),
    val nat: NatStatus = NatStatus(),
    val p2p: P2PStatus = P2PStatus(),
    val relay: RelayStatus = RelayStatus(),
    val initial: String = "relay",
    val strategy: String = "relay",
    val quality: Int = 1,
    @SerialName("checked_at") val checkedAt: String = "",
)

@Serializable
data class IPv6Status(
    val enabled: Boolean = false,
    val reachable: Boolean = false,
    val address: String? = null,
    @SerialName("checked_at") val checkedAt: String = "",
)

@Serializable
data class NatStatus(
    val type: String = "unknown",
    @SerialName("public_ip") val publicIp: String? = null,
    @SerialName("public_port") val publicPort: Int? = null,
    @SerialName("checked_at") val checkedAt: String = "",
)

@Serializable
data class P2PStatus(
    val supported: Boolean = false,
    val reason: String = "",
)

@Serializable
data class RelayStatus(
    val available: Boolean = false,
    val type: String = "",
)

@Serializable
data class ServerEndpoint(
    @SerialName("public_ip") val publicIp: String = "",
    @SerialName("public_port") val publicPort: Int = 0,
    val ipv6: String? = null,
    @SerialName("nat_type") val natType: String = "unknown",
    val strategy: String = "relay",
)
