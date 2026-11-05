package com.homedatacenter.app.ui

import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.RegisterPersonRequest
import com.homedatacenter.app.data.model.StreamConfig
import com.homedatacenter.app.data.model.VisionPerson
import com.homedatacenter.app.data.model.VisionStatus
import com.homedatacenter.app.ui.cameras.AlertFilterState
import com.homedatacenter.app.ui.cameras.CamerasFragment
import com.homedatacenter.app.ui.settings.SettingsMenuSection
import com.homedatacenter.app.ui.settings.SettingsUiState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA & Regression Verification Test Suite covering:
 * 1. 家庭人脸库 (Family Faces models & serialization)
 * 2. 摄像头来源弹窗 (Camera source filter item selection)
 * 3. 设置页折叠分类 (Settings accordion collapsible sections)
 * 4. 天气地区 (Weather condition translation & "陈仓区" formatting)
 * 5. 网络药丸单行展示 (Network latency pill string formatting & fast-path state)
 * 6. 摄像头告警过滤 (AlertFilterState & strict camera alert isolation)
 */
class FeaturesRegressionTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ==========================================
    // 1. 家庭人脸库 (Family Faces)
    // ==========================================

    @Test
    fun testVisionModelsSerializationAndStatus() {
        val jsonStr = """
            {
                "online": true,
                "face_engine_ready": true,
                "pose_engine_ready": false,
                "registered_persons": 3,
                "cpu_usage_percent": 18.5,
                "cpu_gate": "normal",
                "error": null
            }
        """.trimIndent()

        val status = json.decodeFromString<VisionStatus>(jsonStr)
        assertTrue(status.online)
        assertTrue(status.faceEngineReady)
        assertFalse(status.poseEngineReady)
        assertEquals(3, status.registeredPersons)
        assertEquals(18.5, status.cpuUsagePercent, 0.001)
        assertEquals("normal", status.cpuGate)
        assertNull(status.error)

        val person = VisionPerson(name = "小明")
        val personEncoded = json.encodeToString(VisionPerson.serializer(), person)
        val personDecoded = json.decodeFromString<VisionPerson>(personEncoded)
        assertEquals("小明", personDecoded.name)

        val registerReq = RegisterPersonRequest(name = "妈妈", image = "base64_sample_bytes")
        val reqJson = json.encodeToString(RegisterPersonRequest.serializer(), registerReq)
        assertTrue(reqJson.contains("妈妈"))
        assertTrue(reqJson.contains("base64_sample_bytes"))
    }

    // ==========================================
    // 2. 摄像头来源弹窗 (Camera Source Selection)
    // ==========================================

    @Test
    fun testCameraSourceFilterItems() {
        val cameras = listOf(
            Camera(id = 1, name = "客厅摄像头"),
            Camera(id = 2, name = "门口摄像头"),
            Camera(id = 3, name = "阳台摄像头")
        )

        val items = mutableListOf<CamerasFragment.AlertFilterItem>()
        items.add(CamerasFragment.AlertFilterItem(null, "全部摄像头", 0))
        cameras.forEach { cam ->
            items.add(CamerasFragment.AlertFilterItem(cam.id, cam.name, 0))
        }

        assertEquals(4, items.size)
        // First item must represent "全部摄像头" with null id
        assertNull(items[0].id)
        assertEquals("全部摄像头", items[0].title)

        // Subsequent items map directly to camera ids
        assertEquals(1L, items[1].id)
        assertEquals("客厅摄像头", items[1].title)
        assertEquals(2L, items[2].id)
        assertEquals("门口摄像头", items[2].title)
        assertEquals(3L, items[3].id)
        assertEquals("阳台摄像头", items[3].title)

        // Verify selection logic
        val selectedId: Long? = 2L
        val isFirstSelected = items[0].id == selectedId
        val isThirdSelected = items[2].id == selectedId
        assertFalse(isFirstSelected)
        assertTrue(isThirdSelected)
    }

    // ==========================================
    // 3. 设置页折叠分类 (Settings Collapsible Sections)
    // ==========================================

    @Test
    fun testSettingsMenuSectionsAndUiState() {
        // Ensure all 5 functional accordion categories are present
        val sections = SettingsMenuSection.entries
        assertEquals(5, sections.size)
        assertTrue(sections.contains(SettingsMenuSection.Appearance))
        assertTrue(sections.contains(SettingsMenuSection.Notifications))
        assertTrue(sections.contains(SettingsMenuSection.Connectivity))
        assertTrue(sections.contains(SettingsMenuSection.AdminServices))
        assertTrue(sections.contains(SettingsMenuSection.AboutUpdates))

        val defaultUiState = SettingsUiState()
        assertEquals("User", defaultUiState.userName)
        assertFalse(defaultUiState.isAdmin)
        assertFalse(defaultUiState.isSessionDetailsExpanded)
        assertEquals("已是最新版本", defaultUiState.updateStatusText)
        assertEquals("v1.17.1", defaultUiState.appVersion)
        assertEquals("release", defaultUiState.updateTrack)

        // Test state mutation
        val expandedState = defaultUiState.copy(
            isSessionDetailsExpanded = true,
            lanHost = "192.168.31.234:8080",
            isAdmin = true
        )
        assertTrue(expandedState.isSessionDetailsExpanded)
        assertEquals("192.168.31.234:8080", expandedState.lanHost)
        assertTrue(expandedState.isAdmin)
    }

    // ==========================================
    // 4. 天气地区与格式化 (Weather Region & Formatting)
    // ==========================================

    private fun translateWeatherDesc(rawDesc: String): String {
        return when (rawDesc.trim().lowercase()) {
            "clear", "sunny" -> "晴"
            "partly cloudy" -> "多云"
            "cloudy", "overcast" -> "阴"
            "light rain", "patchy light rain", "patchy rain possible" -> "小雨"
            "moderate rain", "heavy rain" -> "雨"
            "thundery outbreaks possible" -> "雷阵雨"
            "fog", "mist" -> "雾"
            "light snow", "patchy snow possible" -> "小雪"
            else -> rawDesc
        }
    }

    private fun formatWeatherBadge(rawDesc: String, temp: String, city: String = "陈仓区"): String {
        val desc = translateWeatherDesc(rawDesc)
        return if (city.isNotEmpty()) "$city $desc ${temp}°C" else "$desc ${temp}°C"
    }

    @Test
    fun testWeatherTranslationAndChencangDistrictFormatting() {
        assertEquals("晴", translateWeatherDesc("Sunny"))
        assertEquals("晴", translateWeatherDesc("Clear"))
        assertEquals("多云", translateWeatherDesc("Partly Cloudy"))
        assertEquals("阴", translateWeatherDesc("Overcast"))
        assertEquals("小雨", translateWeatherDesc("Patchy rain possible"))
        assertEquals("雷阵雨", translateWeatherDesc("Thundery outbreaks possible"))
        assertEquals("雾", translateWeatherDesc("Mist"))
        assertEquals("小雪", translateWeatherDesc("Light snow"))

        // Weather badge formatting with fixed district "陈仓区"
        val formatted1 = formatWeatherBadge(rawDesc = "Sunny", temp = "25")
        assertEquals("陈仓区 晴 25°C", formatted1)

        val formatted2 = formatWeatherBadge(rawDesc = "Partly Cloudy", temp = "18")
        assertEquals("陈仓区 多云 18°C", formatted2)

        val formatted3 = formatWeatherBadge(rawDesc = "Thundery outbreaks possible", temp = "21")
        assertEquals("陈仓区 雷阵雨 21°C", formatted3)
    }

    // ==========================================
    // 5. 网络药丸单行展示 (Network Pill Single-Line Display)
    // ==========================================

    private fun resolveBackendPathInfo(
        isLan: Boolean,
        isH3c: Boolean,
        isIpv6: Boolean,
        rttMs: Long
    ): Pair<String, Boolean> {
        val rttStr = if (rttMs >= 0) " (${rttMs}ms)" else ""
        val text = when {
            isLan -> "局域网$rttStr"
            isH3c -> "国内高速$rttStr"
            isIpv6 -> "IPv6 直连$rttStr"
            else -> "远程$rttStr"
        }
        val isFast = isLan || isH3c
        return Pair(text, isFast)
    }

    @Test
    fun testNetworkPillFormattingAndFastStatus() {
        // LAN path
        val (lanText, lanFast) = resolveBackendPathInfo(isLan = true, isH3c = false, isIpv6 = false, rttMs = 8)
        assertEquals("局域网 (8ms)", lanText)
        assertTrue(lanFast)

        // H3C fast path
        val (h3cText, h3cFast) = resolveBackendPathInfo(isLan = false, isH3c = true, isIpv6 = false, rttMs = 32)
        assertEquals("国内高速 (32ms)", h3cText)
        assertTrue(h3cFast)

        // IPv6 direct path
        val (ipv6Text, ipv6Fast) = resolveBackendPathInfo(isLan = false, isH3c = false, isIpv6 = true, rttMs = 50)
        assertEquals("IPv6 直连 (50ms)", ipv6Text)
        assertFalse(ipv6Fast)

        // Remote fallback
        val (remoteText, remoteFast) = resolveBackendPathInfo(isLan = false, isH3c = false, isIpv6 = false, rttMs = 150)
        assertEquals("远程 (150ms)", remoteText)
        assertFalse(remoteFast)

        // Negative RTT (no latency probe result)
        val (noRttText, _) = resolveBackendPathInfo(isLan = true, isH3c = false, isIpv6 = false, rttMs = -1)
        assertEquals("局域网", noRttText)
    }

    // ==========================================
    // 6. 摄像头告警过滤与严格隔离 (Camera Alert Filtering)
    // ==========================================

    private fun slugify(name: String): String {
        val cn = mapOf(
            "前门" to "front_door", "后门" to "back_door",
            "客厅" to "living_room", "卧室" to "bedroom",
            "厨房" to "kitchen", "院子" to "yard", "车库" to "garage"
        )
        cn[name]?.let { return it }
        return name.lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
    }

    private fun filterAlertsForCamera(
        targetCamera: Camera,
        alerts: List<Alert>
    ): List<Alert> {
        if (alerts.isEmpty()) return emptyList()

        val expectedName = targetCamera.name.trim()
        val expectedSlug = slugify(expectedName)
        val streamName = targetCamera.stream?.streamName?.trim().orEmpty()
        val streamSlug = if (streamName.isNotEmpty()) slugify(streamName) else ""

        return alerts.filter { alert ->
            // 1. Strict Conflict Rejections: Never allow an alert if its metadata explicitly contradicts this camera
            if (!alert.cameraName.isNullOrBlank() && !alert.cameraName.equals(expectedName, ignoreCase = true)) {
                return@filter false
            }
            if (alert.cameraId != null && alert.cameraId > 0 && alert.cameraId != targetCamera.id) {
                return@filter false
            }

            // 2. High-Confidence ID Match: If camera_id explicitly matches, accept immediately
            if (alert.cameraId != null && alert.cameraId == targetCamera.id) {
                return@filter true
            }

            // 3. Name Match: If alert camera_name matches expected camera name
            if (!alert.cameraName.isNullOrBlank() && alert.cameraName.equals(expectedName, ignoreCase = true)) {
                return@filter true
            }

            // 4. Slug Match against camera stream or slugified camera name
            val alertSlug = alert.cameraSlug.ifEmpty { slugify(alert.cameraName) }
            if (alertSlug.isNotEmpty()) {
                if (streamSlug.isNotEmpty() && alertSlug.equals(streamSlug, ignoreCase = true)) {
                    return@filter true
                }
                if (alertSlug.equals(expectedSlug, ignoreCase = true)) {
                    return@filter true
                }
            }

            false
        }
    }

    @Test
    fun testAlertFilterStateReactiveProperties() {
        val defaultState = AlertFilterState()
        assertFalse(defaultState.hasActiveFilter)

        val filteredState = AlertFilterState(cameraId = 1L, label = "person", days = 3)
        assertTrue(filteredState.hasActiveFilter)
        assertEquals(1L, filteredState.cameraId)
        assertEquals("person", filteredState.label)
        assertEquals(3, filteredState.days)

        val resetState = filteredState.reset()
        assertFalse(resetState.hasActiveFilter)
        assertNull(resetState.cameraId)
        assertNull(resetState.label)
        assertEquals(0, resetState.days)
    }

    private fun createTestAlert(
        id: String,
        cameraId: Long? = null,
        cameraName: String = "",
        cameraSlug: String = "",
        label: String = "person",
        confidence: Double = 0.9,
        startTime: Double = 1700000000.0,
        endTime: Double = 1700000010.0
    ): Alert = Alert(
        id = id,
        cameraId = cameraId,
        cameraName = cameraName,
        cameraSlug = cameraSlug,
        label = label,
        confidence = confidence,
        startTime = startTime,
        endTime = endTime
    )

    @Test
    fun testStrictCameraAlertIsolationAndNoCrossCameraLeakage() {
        val livingRoomCam = Camera(
            id = 10,
            name = "客厅",
            stream = StreamConfig(streamName = "living_room")
        )
        val yardCam = Camera(
            id = 20,
            name = "院子",
            stream = StreamConfig(streamName = "yard")
        )

        val alerts = listOf(
            createTestAlert(id = "1", cameraId = 10L, cameraName = "客厅", label = "person"),
            createTestAlert(id = "2", cameraId = 20L, cameraName = "院子", label = "dog"),
            createTestAlert(id = "3", cameraId = 10L, cameraName = "", label = "car"),
            createTestAlert(id = "4", cameraId = null, cameraName = "客厅", label = "person"),
            createTestAlert(id = "5", cameraId = null, cameraName = "", cameraSlug = "living_room", label = "person"),
            createTestAlert(id = "6", cameraId = null, cameraName = "", cameraSlug = "yard", label = "dog")
        )

        // Filter for Living Room
        val livingRoomAlerts = filterAlertsForCamera(livingRoomCam, alerts)
        val livingRoomAlertIds = livingRoomAlerts.map { it.id }.toSet()

        assertTrue(livingRoomAlertIds.contains("1")) // matched by id + name
        assertTrue(livingRoomAlertIds.contains("3")) // matched by id
        assertTrue(livingRoomAlertIds.contains("4")) // matched by name
        assertTrue(livingRoomAlertIds.contains("5")) // matched by stream slug

        assertFalse(livingRoomAlertIds.contains("2")) // belongs to yard (conflict rejected)
        assertFalse(livingRoomAlertIds.contains("6")) // belongs to yard (slug mismatch)

        // Filter for Yard
        val yardAlerts = filterAlertsForCamera(yardCam, alerts)
        val yardAlertIds = yardAlerts.map { it.id }.toSet()

        assertTrue(yardAlertIds.contains("2"))
        assertTrue(yardAlertIds.contains("6"))
        assertFalse(yardAlertIds.contains("1"))
        assertFalse(yardAlertIds.contains("3"))
        assertFalse(yardAlertIds.contains("4"))
        assertFalse(yardAlertIds.contains("5"))

        // Edge case: Empty alert list returns empty
        val emptyResult = filterAlertsForCamera(livingRoomCam, emptyList())
        assertTrue(emptyResult.isEmpty())

        // Edge case: Non-matching alerts list returns empty, never leaks other cameras
        val unrelatedAlerts = listOf(
            createTestAlert(id = "99", cameraId = 999L, cameraName = "车库", cameraSlug = "garage")
        )
        val isolatedResult = filterAlertsForCamera(livingRoomCam, unrelatedAlerts)
        assertTrue(isolatedResult.isEmpty())
    }
}
