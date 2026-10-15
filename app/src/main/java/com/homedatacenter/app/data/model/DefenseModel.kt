package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BanEntry(
    val ip: String,
    val reason: String = "",
    @SerialName("banned_at")
    val bannedAt: String = "",
    @SerialName("expires_at")
    val expiresAt: String = "",
    val permanent: Boolean = false
)

@Serializable
data class DefenseOverview(
    @SerialName("total_banned_count")
    val totalBannedCount: Int = 0,
    @SerialName("total_blocked_hits")
    val totalBlockedHits: Long = 0L,
    @SerialName("active_bans")
    val activeBans: List<BanEntry> = emptyList(),
    @SerialName("smtp_enabled")
    val smtpEnabled: Boolean = false,
    val recipient: String = ""
)

@Serializable
data class ManualBanRequest(
    val ip: String,
    @SerialName("duration_minutes")
    val durationMinutes: Int = 30,
    val reason: String = ""
)
