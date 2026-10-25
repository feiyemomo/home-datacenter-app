package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AISourceInfo(
    @SerialName("key") val key: String,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String = "",
    @SerialName("active") val active: Boolean = false,
)

@Serializable
data class AISourceStatus(
    @SerialName("current_source") val currentSource: String = "camera",
    @SerialName("motion_fallback") val motionFallback: Boolean = false,
    @SerialName("available_sources") val availableSources: List<AISourceInfo> = emptyList(),
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class SetAISourceRequest(
    @SerialName("source") val source: String? = null,
    @SerialName("motion_fallback") val motionFallback: Boolean? = null,
)

@Serializable
data class SetMotionFallbackRequest(
    @SerialName("enabled") val enabled: Boolean,
)
