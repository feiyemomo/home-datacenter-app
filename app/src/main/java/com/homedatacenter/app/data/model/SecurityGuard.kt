package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SecurityGuard(
    @SerialName("mode") val mode: String = "away", // away | home | disarmed
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("updated_by") val updatedBy: String = ""
) {
    val isAway: Boolean get() = mode == "away" || mode == "armed_away"
    val isHome: Boolean get() = mode == "home" || mode == "armed_home"
    val isDisarmed: Boolean get() = mode == "disarmed" || mode == "disarm"

    val modeLabel: String
        get() = when {
            isAway -> "离家布防"
            isHome -> "在家守护"
            isDisarmed -> "撤防免打扰"
            else -> mode
        }
}

@Serializable
data class SetSecurityGuardRequest(
    @SerialName("mode") val mode: String
)
