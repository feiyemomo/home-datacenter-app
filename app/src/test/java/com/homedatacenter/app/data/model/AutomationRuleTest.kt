package com.homedatacenter.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRuleTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodeAutomationRulesData_decodesCorrectly() {
        val raw = """
        {
            "rules": [
                {
                    "id": 1,
                    "name": "夜间人形入侵告警",
                    "trigger": "detection",
                    "enabled": true,
                    "fire_count": 42,
                    "condition": {
                        "time_gte": "22:00",
                        "time_lte": "06:00",
                        "payload_eq": {"label": "person"},
                        "threshold": {"confidence": {"op": ">=", "val": 0.8}}
                    },
                    "action": {
                        "type": "notify",
                        "title": "夜间入侵告警",
                        "body": "监控摄像头检测到夜间有人走动"
                    },
                    "throttle": {
                        "cooldown_s": 60,
                        "rate_per_min": 5,
                        "dedup": true
                    }
                }
            ],
            "total": 1
        }
        """.trimIndent()

        val data = json.decodeFromString<AutomationRulesData>(raw)
        assertEquals(1, data.total)
        assertEquals(1, data.rules.size)

        val rule = data.rules.first()
        assertEquals(1L, rule.id)
        assertEquals("夜间人形入侵告警", rule.name)
        assertEquals("detection", rule.trigger)
        assertTrue(rule.enabled)
        assertEquals(42L, rule.fireCount)

        // Condition helpers
        assertNotNull(rule.condition)
        assertEquals("22:00", rule.condition?.startTime)
        assertEquals("06:00", rule.condition?.endTime)
        assertEquals("person", rule.condition?.label)
        assertEquals(0.8, rule.condition?.confidence ?: 0.0, 0.001)

        // Action helpers
        assertNotNull(rule.action)
        assertEquals("notify", rule.action?.type)
        assertEquals("夜间入侵告警", rule.action?.title)
        assertEquals("监控摄像头检测到夜间有人走动", rule.action?.message)

        // Throttle helpers
        assertNotNull(rule.throttle)
        assertEquals(60, rule.throttle?.cooldownSeconds)
    }

    @Test
    fun decodeTestRuleResponse_decodesCorrectly() {
        val raw = """
        {
            "id": 1,
            "name": "测试规则",
            "action": "notify",
            "test_fired": true
        }
        """.trimIndent()

        val resp = json.decodeFromString<TestRuleResponse>(raw)
        assertEquals(1L, resp.id)
        assertEquals("测试规则", resp.name)
        assertEquals("notify", resp.action)
        assertTrue(resp.testFired)
    }

    @Test
    fun decodeAutomationMetrics_decodesCorrectly() {
        val raw = """
        {
            "total_events": 100,
            "total_matches": 20,
            "total_fires": 18,
            "total_dropped_cooldown": 2,
            "total_dropped_rate": 0,
            "total_dropped_dedup": 0,
            "total_errors": 0
        }
        """.trimIndent()

        val metrics = json.decodeFromString<AutomationMetrics>(raw)
        assertEquals(100L, metrics.totalEvents)
        assertEquals(20L, metrics.totalMatches)
        assertEquals(18L, metrics.totalFires)
        assertEquals(2L, metrics.totalDroppedCooldown)
    }
}
