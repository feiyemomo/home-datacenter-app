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
data class DefenseConfigSummary(
    @SerialName("max_auth_failures")
    val maxAuthFailures: Int = 5,
    @SerialName("failure_window_mins")
    val failureWindowMins: Int = 5,
    @SerialName("ban_duration_mins")
    val banDurationMins: Int = 30,
    val whitelist: List<String> = emptyList()
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
    @SerialName("sender_email")
    val senderEmail: String = "",
    @SerialName("smtp_host")
    val smtpHost: String = "",
    val recipient: String = "",
    val recipients: List<String> = emptyList(),
    val config: DefenseConfigSummary? = null
) {
    val allRecipients: List<String>
        get() {
            if (recipients.isNotEmpty()) return recipients
            return recipient.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
}

@Serializable
data class ManualBanRequest(
    val ip: String,
    @SerialName("duration_minutes")
    val durationMinutes: Int = 30,
    val reason: String = ""
)

@Serializable
data class UpdateBanRequest(
    @SerialName("duration_minutes")
    val durationMinutes: Int = 30,
    val permanent: Boolean = false
)

@Serializable
data class SecurityEventItem(
    val id: Long = 0L,
    val ip: String = "",
    val type: String = "",
    val detail: String = "",
    val action: String = "",
    @SerialName("created_at")
    val createdAt: String = ""
)

@Serializable
data class SecurityWhitelistEntry(
    val id: Long = 0L,
    val entry: String = "",
    val note: String = "",
    @SerialName("created_at")
    val createdAt: String = ""
)

@Serializable
data class AddWhitelistRequest(
    val entry: String,
    val note: String = ""
)

@Serializable
data class UpdateSecurityConfigRequest(
    @SerialName("max_auth_failures")
    val maxAuthFailures: Int,
    @SerialName("failure_window_mins")
    val failureWindowMins: Int,
    @SerialName("ban_duration_mins")
    val banDurationMins: Int
)

@Serializable
data class HostAuthLogEntry(
    val ip: String = "",
    val user: String = "",
    val port: String = "",
    val status: String = "",
    val service: String = "",
    @SerialName("raw_message")
    val rawMessage: String = ""
)

@Serializable
data class HostLogStatus(
    val enabled: Boolean = false,
    @SerialName("source_path")
    val sourcePath: String = "",
    @SerialName("export_path")
    val exportPath: String = "",
    val entries: List<HostAuthLogEntry> = emptyList()
)
