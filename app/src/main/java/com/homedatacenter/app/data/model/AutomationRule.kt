package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AutomationRulesData(
    @SerialName("rules") val rules: List<AutomationRule> = emptyList(),
    @SerialName("total") val total: Int = 0
)

@Serializable
data class AutomationRule(
    @SerialName("id") val id: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("trigger") val trigger: String = "",
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("fire_count") val fireCount: Long = 0,
    @SerialName("last_fire_at") val lastFireAt: Long? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("condition") val condition: RuleCondition? = null,
    @SerialName("action") val action: RuleAction? = null,
    @SerialName("throttle") val throttle: RuleThrottle? = null
)

@Serializable
data class NumberOp(
    @SerialName("op") val op: String = ">=",
    @SerialName("val") val value: Double = 0.0
)

@Serializable
data class RuleCondition(
    @SerialName("time_gte") val timeGte: String? = null,
    @SerialName("time_lte") val timeLte: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("payload_eq") val payloadEq: Map<String, String>? = null,
    @SerialName("threshold") val threshold: Map<String, NumberOp>? = null,
    @SerialName("any") val any: Boolean? = null
) {
    val startTime: String? get() = timeGte
    val endTime: String? get() = timeLte
    val label: String? get() = payloadEq?.get("label")
    val confidence: Double? get() = threshold?.get("confidence")?.value
}

@Serializable
data class RuleAction(
    @SerialName("type") val type: String = "notify",
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("topic") val topic: String? = null,
    @SerialName("payload") val payload: String? = null,
    @SerialName("qos") val qos: Int? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("method") val method: String? = null
) {
    val message: String? get() = body
}

@Serializable
data class RuleThrottle(
    @SerialName("cooldown_s") val cooldownS: Int? = null,
    @SerialName("rate_per_min") val ratePerMin: Int? = null,
    @SerialName("dedup") val dedup: Boolean? = null
) {
    val cooldownSeconds: Int get() = cooldownS ?: 0
}

@Serializable
data class CreateAutomationRuleRequest(
    @SerialName("name") val name: String,
    @SerialName("trigger") val trigger: String,
    @SerialName("condition") val condition: RuleCondition? = null,
    @SerialName("action") val action: RuleAction,
    @SerialName("throttle") val throttle: RuleThrottle? = null,
    @SerialName("enabled") val enabled: Boolean = true
)

@Serializable
data class UpdateAutomationRuleRequest(
    @SerialName("name") val name: String? = null,
    @SerialName("trigger") val trigger: String? = null,
    @SerialName("condition") val condition: RuleCondition? = null,
    @SerialName("action") val action: RuleAction? = null,
    @SerialName("throttle") val throttle: RuleThrottle? = null,
    @SerialName("enabled") val enabled: Boolean? = null
)

@Serializable
data class TestRuleResponse(
    @SerialName("id") val id: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("action") val action: String = "",
    @SerialName("test_fired") val testFired: Boolean = false
)

@Serializable
data class AutomationMetrics(
    @SerialName("total_events") val totalEvents: Long = 0,
    @SerialName("total_matches") val totalMatches: Long = 0,
    @SerialName("total_fires") val totalFires: Long = 0,
    @SerialName("total_dropped_cooldown") val totalDroppedCooldown: Long = 0,
    @SerialName("total_dropped_rate") val totalDroppedRate: Long = 0,
    @SerialName("total_dropped_dedup") val totalDroppedDedup: Long = 0,
    @SerialName("total_errors") val totalErrors: Long = 0
)
