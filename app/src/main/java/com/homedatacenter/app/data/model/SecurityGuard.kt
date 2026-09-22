package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SecurityGuard(
    @SerialName("mode") val mode: String = "away", // away | home | disarmed
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("updated_by") val updatedBy: String = ""
) {
    val isAway: Boolean get() = mode == "away"
    val isHome: Boolean get() = mode == "home"
    val isDisarmed: Boolean get() = mode == "disarmed"

    val modeLabel: String
        get() = when (mode) {
            "away" -> "离家布防"
            "home" -> "在家守护"
            "disarmed" -> "撤防免打扰"
            else -> mode
        }
}

@Serializable
data class SetSecurityGuardRequest(
    @SerialName("mode") val mode: String
)
