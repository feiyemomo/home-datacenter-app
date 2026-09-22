package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AutomationRule(
    @SerialName("id") val id: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("trigger") val trigger: String = "",
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("fire_count") val fireCount: Long = 0,
    @SerialName("last_fire_at") val lastFireAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("condition") val condition: JsonElement? = null,
    @SerialName("action") val action: JsonElement? = null,
    @SerialName("throttle") val throttle: JsonElement? = null
)

@Serializable
data class UpdateAutomationRuleRequest(
    @SerialName("name") val name: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null
)
