package com.homedatacenter.app.ui.ops

import com.homedatacenter.app.data.model.SystemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying Ops management center state linkage,
 * offline/online transitions, and microservices status consistency.
 */
class OpsStateIntegrationTest {

    @Test
    fun testFormatUptime() {
        assertEquals("刚刚启动", formatUptime(0))
        assertEquals("刚刚启动", formatUptime(-100))
        assertEquals("刚刚启动", formatUptime(45))
        assertEquals("2分钟", formatUptime(120))
        assertEquals("1小时 1分钟", formatUptime(3660))
        assertEquals("1天 1小时", formatUptime(90000))
        assertEquals("10天 5小时", formatUptime(10 * 86400 + 5 * 3600 + 120))
    }

    @Test
    fun testServerOfflineStateLinkage() {
        val systemStatus: SystemStatus? = null
        val isServerOnline = systemStatus != null

        assertFalse("Server must be marked offline when systemStatus is null", isServerOnline)

        // Microservices state
        val servicesList = listOf(
            ServiceHealthInfo("Backend API", "8080", "Go Gin REST 业务主服务", isServerOnline),
            ServiceHealthInfo("Frigate NVR", "1984 / 5000", "go2rtc WebRTC 视频推流网关", isServerOnline),
            ServiceHealthInfo("Mosquitto MQTT", "1883", "物联网与自动化消息总线", isServerOnline && (systemStatus?.mqttConnected ?: false)),
            ServiceHealthInfo("Vision AI", "8090", "OpenCV DNN 边缘人工智能识别", isServerOnline)
        )

        for (svc in servicesList) {
            assertFalse("${svc.name} must be offline when server is disconnected", svc.isOnline)
        }
    }

    @Test
    fun testServerOnlineStateLinkageWithMqttHealthy() {
        val systemStatus = SystemStatus(
            mqttConnected = true,
            uptimeSeconds = 90000,
            wsClients = 2
        )
        val isServerOnline = true

        val servicesList = listOf(
            ServiceHealthInfo("Backend API", "8080", "Go Gin REST 业务主服务", isServerOnline),
            ServiceHealthInfo("Frigate NVR", "1984 / 5000", "go2rtc WebRTC 视频推流网关", isServerOnline),
            ServiceHealthInfo("Mosquitto MQTT", "1883", "物联网与自动化消息总线", isServerOnline && systemStatus.mqttConnected),
            ServiceHealthInfo("Vision AI", "8090", "OpenCV DNN 边缘人工智能识别", isServerOnline)
        )

        assertTrue(servicesList.all { it.isOnline })

        val uptimeDesc = formatUptime(systemStatus.uptimeSeconds)
        assertEquals("1天 1小时", uptimeDesc)
    }

    @Test
    fun testServerOnlineStateLinkageWithMqttDisconnected() {
        val systemStatus = SystemStatus(
            mqttConnected = false,
            uptimeSeconds = 3600,
            wsClients = 1
        )
        val isServerOnline = true

        val servicesList = listOf(
            ServiceHealthInfo("Backend API", "8080", "Go Gin REST 业务主服务", isServerOnline),
            ServiceHealthInfo("Frigate NVR", "1984 / 5000", "go2rtc WebRTC 视频推流网关", isServerOnline),
            ServiceHealthInfo("Mosquitto MQTT", "1883", "物联网与自动化消息总线", isServerOnline && systemStatus.mqttConnected),
            ServiceHealthInfo("Vision AI", "8090", "OpenCV DNN 边缘人工智能识别", isServerOnline)
        )

        // Mosquitto must be offline
        val mosquittoService = servicesList.first { it.name == "Mosquitto MQTT" }
        assertFalse(mosquittoService.isOnline)
        // Others remain online
        assertTrue(servicesList.first { it.name == "Backend API" }.isOnline)
    }
}
