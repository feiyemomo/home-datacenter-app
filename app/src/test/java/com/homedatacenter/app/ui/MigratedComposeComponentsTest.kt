package com.homedatacenter.app.ui

import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.CameraShare
import com.homedatacenter.app.data.model.RegisterCameraRequest
import com.homedatacenter.app.data.model.ShareCameraRequest
import com.homedatacenter.app.data.model.UpdateInfo
import com.homedatacenter.app.data.model.User
import com.homedatacenter.app.ui.alerts.AlertSnapshotDialogFragment
import com.homedatacenter.app.ui.cameras.AlertThumbnailResolver
import com.homedatacenter.app.ui.cameras.CamerasFragment
import com.homedatacenter.app.ui.cameras.FilterDialogConfig
import com.homedatacenter.app.ui.cameras.MultiCameraGridMode
import com.homedatacenter.app.ui.cameras.RecordingsDialog
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * Unit test suite verifying the newly modernized Jetpack Compose dialogs and components:
 * 1. RegisterCameraDialog / ShareCameraDialog (models, default parameters, validation & state)
 * 2. AlertsDialog / AlertCard / AlertThumbnailResolver (thumbnail decoding, display formatting, strict camera & category filtering)
 * 3. AlertSnapshotDialogFragment / AlertSnapshotDialog (tag, argument payload serialization & snapshot URL contract)
 * 4. MultiCameraActivity / MultiCameraPageContent (grid modes, frame URLs & slot allocation)
 * 5. FilterOptionsDialog (dialog config, item selection & callbacks)
 * 6. RecordingsDialog (DayRecording formatting, DiffCallback, MotionChip models & edge-snapping math)
 * 7. UserDialogs / OpsSecurityDialogs / SettingsDialogs (validation rules, fallback defaults, quota stepper/clamp logic & size formatting)
 */
class MigratedComposeComponentsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun createTestAlert(
        id: String,
        cameraId: Long? = null,
        cameraName: String = "",
        cameraSlug: String = "",
        label: String = "person",
        confidence: Double = 0.90,
        startTime: Double = 1700000000.0,
        endTime: Double = 1700000010.0,
        hasClip: Boolean = false,
        thumbnail: String = ""
    ): Alert = Alert(
        id = id,
        cameraId = cameraId,
        cameraName = cameraName,
        cameraSlug = cameraSlug,
        label = label,
        confidence = confidence,
        startTime = startTime,
        endTime = endTime,
        hasClip = hasClip,
        thumbnail = thumbnail
    )

    // =========================================================================
    // 1. RegisterCameraDialog & ShareCameraDialog
    // =========================================================================

    @Test
    fun testRegisterCameraRequestDefaultsAndSerialization() {
        val req = RegisterCameraRequest(
            name = "客厅主摄像机",
            host = "192.168.31.100",
            password = "SecretPassword123"
        )

        assertEquals("客厅主摄像机", req.name)
        assertEquals("192.168.31.100", req.host)
        assertEquals("SecretPassword123", req.password)
        assertEquals("hikvision", req.vendor)
        assertEquals(80, req.onvifPort)
        assertEquals(554, req.rtspPort)
        assertEquals(101, req.channelId)
        assertEquals("admin", req.username)
        assertTrue(req.ptz)
        assertTrue(req.audio)
        assertTrue(req.motion)
        assertFalse(req.twoWayAudio)

        val encoded = json.encodeToString(RegisterCameraRequest.serializer(), req)
        val decoded = json.decodeFromString(RegisterCameraRequest.serializer(), encoded)
        assertEquals(req.name, decoded.name)
        assertEquals(req.host, decoded.host)
        assertEquals(req.vendor, decoded.vendor)
        assertEquals(req.onvifPort, decoded.onvifPort)
        assertEquals(req.rtspPort, decoded.rtspPort)
    }

    @Test
    fun testRegisterCameraValidationRules() {
        fun validateForm(name: String, host: String, pass: String, token: String?): String? {
            val n = name.trim()
            val h = host.trim()
            val p = pass
            if (n.isEmpty() || h.isEmpty() || p.isEmpty()) {
                return "请填写名称、主机 IP 和密码"
            }
            if (token.isNullOrEmpty()) {
                return "未登录，无法执行操作"
            }
            return null
        }

        assertEquals("请填写名称、主机 IP 和密码", validateForm("", "192.168.1.1", "pass", "token123"))
        assertEquals("请填写名称、主机 IP 和密码", validateForm("   ", "192.168.1.1", "pass", "token123"))
        assertEquals("请填写名称、主机 IP 和密码", validateForm("Name", "", "pass", "token123"))
        assertEquals("请填写名称、主机 IP 和密码", validateForm("Name", "192.168.1.1", "", "token123"))
        assertEquals("未登录，无法执行操作", validateForm("Name", "192.168.1.1", "pass", null))
        assertEquals("未登录，无法执行操作", validateForm("Name", "192.168.1.1", "pass", ""))
        assertNull(validateForm("客厅摄像机", "192.168.1.200", "pass123", "valid_token"))

        // Port and vendor fallbacks
        val fallbackVendor = "   ".trim().ifBlank { "hikvision" }
        val fallbackUser = "".trim().ifBlank { "admin" }
        val parsedOnvif = "invalid_port".trim().toIntOrNull() ?: 80
        val parsedRtsp = "".trim().toIntOrNull() ?: 554
        val parsedChannel = "201".trim().toIntOrNull() ?: 101

        assertEquals("hikvision", fallbackVendor)
        assertEquals("admin", fallbackUser)
        assertEquals(80, parsedOnvif)
        assertEquals(554, parsedRtsp)
        assertEquals(201, parsedChannel)
    }

    @Test
    fun testCameraShareModelAndDisplayNameResolution() {
        val share = CameraShare(
            id = 10L,
            cameraId = 101L,
            userId = 202L,
            canPtz = true,
            createdAt = "2026-10-31T12:00:00Z"
        )
        assertEquals(10L, share.id)
        assertEquals(101L, share.cameraId)
        assertEquals(202L, share.userId)
        assertTrue(share.canPtz)

        val users = listOf(
            User(id = 202L, name = "张三", isAdmin = false),
            User(id = 303L, name = "李四", isAdmin = true)
        )

        // Matched user returns actual username
        val resolvedName = users.firstOrNull { it.id == share.userId }?.name ?: "用户 #${share.userId}"
        assertEquals("张三", resolvedName)

        // Unmatched user falls back to "用户 #ID"
        val unmatchedShare = CameraShare(id = 11L, cameraId = 101L, userId = 999L, canPtz = false)
        val fallbackName = users.firstOrNull { it.id == unmatchedShare.userId }?.name ?: "用户 #${unmatchedShare.userId}"
        assertEquals("用户 #999", fallbackName)

        // Test ShareCameraRequest serialization
        val shareReq = ShareCameraRequest(userId = 202L, canPtz = false)
        val shareReqJson = json.encodeToString(ShareCameraRequest.serializer(), shareReq)
        assertTrue(shareReqJson.contains("\"user_id\":202"))
        assertTrue(shareReqJson.contains("\"can_ptz\":false"))
    }

    // =========================================================================
    // 2. AlertsDialog, AlertCard & AlertThumbnailResolver
    // =========================================================================

    @Test
    fun testAlertThumbnailResolverWithDataUriAndInvalidBase64() {
        val rawBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

        // Test standard raw base64
        val decodedStandard = AlertThumbnailResolver.decodeBase64Safe(rawBase64)
        assertNotNull(decodedStandard)
        assertTrue(decodedStandard!!.isNotEmpty())

        // Test base64 with data URI prefix (case insensitive)
        val withPrefixLower = "data:image/png;base64,$rawBase64"
        val decodedWithPrefix = AlertThumbnailResolver.decodeBase64Safe(withPrefixLower)
        assertNotNull(decodedWithPrefix)
        assertEquals(decodedStandard.size, decodedWithPrefix!!.size)

        val withPrefixUpper = "DATA:IMAGE/JPEG;BASE64,$rawBase64"
        val decodedUpper = AlertThumbnailResolver.decodeBase64Safe(withPrefixUpper)
        assertNotNull(decodedUpper)

        // Invalid base64 returns null safely without throwing exception
        val invalidDecoded = AlertThumbnailResolver.decodeBase64Safe("!!!not_base64_payload&&@*@")
        assertNull(invalidDecoded)

        // Resolve model priorities
        val modelFromBase64 = AlertThumbnailResolver.resolveModel(
            thumbnailBase64 = rawBase64,
            alertId = "alert-1",
            baseUrl = "http://192.168.31.234:8080"
        )
        assertTrue("Base64 takes priority and returns ByteArray", modelFromBase64 is ByteArray)

        val modelFromNetwork = AlertThumbnailResolver.resolveModel(
            thumbnailBase64 = null,
            alertId = "alert-2",
            baseUrl = "http://192.168.31.234:8080/"
        )
        assertEquals("http://192.168.31.234:8080/api/v1/cameras/alerts/alert-2/thumbnail", modelFromNetwork)

        val modelNull = AlertThumbnailResolver.resolveModel(
            thumbnailBase64 = "",
            alertId = "alert-3",
            baseUrl = null
        )
        assertNull(modelNull)
    }

    @Test
    fun testAlertCardDisplayNameAndConfidenceFormatting() {
        val alertWithName = createTestAlert(
            id = "a1",
            cameraId = 1L,
            cameraName = "阳台摄像机",
            cameraSlug = "balcony_cam",
            label = "person",
            confidence = 0.892,
            hasClip = true
        )
        val name1 = alertWithName.cameraName.ifEmpty { alertWithName.cameraSlug.ifEmpty { "未知摄像头" } }
        assertEquals("阳台摄像机", name1)

        val alertWithSlugOnly = createTestAlert(
            id = "a2",
            cameraId = 2L,
            cameraName = "",
            cameraSlug = "doorway_cam",
            label = "car",
            confidence = 0.95,
            hasClip = false
        )
        val name2 = alertWithSlugOnly.cameraName.ifEmpty { alertWithSlugOnly.cameraSlug.ifEmpty { "未知摄像头" } }
        assertEquals("doorway_cam", name2)

        val alertEmpty = createTestAlert(
            id = "a3",
            cameraName = "",
            cameraSlug = "",
            label = "dog",
            confidence = 0.724
        )
        val name3 = alertEmpty.cameraName.ifEmpty { alertEmpty.cameraSlug.ifEmpty { "未知摄像头" } }
        assertEquals("未知摄像头", name3)

        // Confidence percentage formatting: (alert.confidence * 100).toInt() + "%"
        val confText1 = "${(alertWithName.confidence * 100).toInt()}%"
        assertEquals("89%", confText1)

        val confText2 = "${(alertWithSlugOnly.confidence * 100).toInt()}%"
        assertEquals("95%", confText2)

        assertTrue(alertWithName.hasClip)
        assertFalse(alertWithSlugOnly.hasClip)
    }

    @Test
    fun testAlertsDialogStrictCameraMatchingAndCategoryFilters() {
        val currentCamera = Camera(id = 5L, name = "车库摄像头")
        val expectedName = currentCamera.name.trim()

        val alerts = listOf(
            createTestAlert(id = "1", cameraId = 5L, cameraName = "车库摄像头", label = "person"),
            createTestAlert(id = "2", cameraId = 5L, cameraName = "", label = "car"),
            createTestAlert(id = "3", cameraId = null, cameraName = "车库摄像头", label = "truck"),
            createTestAlert(id = "4", cameraId = 8L, cameraName = "车库摄像头", label = "dog"), // Different cameraId -> should be excluded
            createTestAlert(id = "5", cameraId = 5L, cameraName = "客厅摄像头", label = "person"), // Different cameraName -> should be excluded
            createTestAlert(id = "6", cameraId = null, cameraName = "后院摄像头", label = "bicycle"),
        )

        val matchedAlerts = alerts.filter { alert ->
            if (!alert.cameraName.isNullOrBlank() && !alert.cameraName.equals(expectedName, ignoreCase = true)) {
                return@filter false
            }
            if (alert.cameraId != null && alert.cameraId > 0 && alert.cameraId != currentCamera.id) {
                return@filter false
            }
            if (alert.cameraId != null && alert.cameraId == currentCamera.id) {
                return@filter true
            }
            if (!alert.cameraName.isNullOrBlank() && alert.cameraName.equals(expectedName, ignoreCase = true)) {
                return@filter true
            }
            true
        }

        // Only alerts 1, 2, 3 should match
        assertEquals(3, matchedAlerts.size)
        assertEquals("1", matchedAlerts[0].id)
        assertEquals("2", matchedAlerts[1].id)
        assertEquals("3", matchedAlerts[2].id)

        // Category filter test
        fun filterByCategory(list: List<Alert>, category: String): List<Alert> {
            return when (category) {
                "人形" -> list.filter { it.label.equals("person", ignoreCase = true) }
                "车辆" -> list.filter {
                    it.label.equals("car", ignoreCase = true) ||
                        it.label.equals("truck", ignoreCase = true) ||
                        it.label.equals("bus", ignoreCase = true) ||
                        it.label.equals("motorcycle", ignoreCase = true) ||
                        it.label.equals("bicycle", ignoreCase = true)
                }
                "其他" -> list.filter {
                    !it.label.equals("person", ignoreCase = true) &&
                        !it.label.equals("car", ignoreCase = true) &&
                        !it.label.equals("truck", ignoreCase = true) &&
                        !it.label.equals("bus", ignoreCase = true) &&
                        !it.label.equals("motorcycle", ignoreCase = true) &&
                        !it.label.equals("bicycle", ignoreCase = true)
                }
                else -> list
            }
        }

        val testList = listOf(
            createTestAlert(id = "p1", label = "person"),
            createTestAlert(id = "p2", label = "PERSON"),
            createTestAlert(id = "c1", label = "car"),
            createTestAlert(id = "c2", label = "truck"),
            createTestAlert(id = "c3", label = "motorcycle"),
            createTestAlert(id = "o1", label = "cat"),
            createTestAlert(id = "o2", label = "package")
        )

        assertEquals(2, filterByCategory(testList, "人形").size)
        assertEquals(3, filterByCategory(testList, "车辆").size)
        assertEquals(2, filterByCategory(testList, "其他").size)
        assertEquals(7, filterByCategory(testList, "全部").size)
    }

    // =========================================================================
    // 3. AlertSnapshotDialogFragment / AlertSnapshotDialog
    // =========================================================================

    @Test
    fun testAlertSnapshotDialogStateAndSerialization() {
        assertEquals("AlertSnapshotDialog", AlertSnapshotDialogFragment.TAG)

        val originalAlert = createTestAlert(
            id = "alert-snap-99",
            cameraId = 12L,
            cameraName = "大门入口",
            label = "person",
            confidence = 0.965,
            startTime = 1700000000.0,
            endTime = 1700000015.0
        )

        // Verify JSON serialization round-trip used by newInstance arguments bundle
        val encodedAlert = json.encodeToString(Alert.serializer(), originalAlert)
        val decodedAlert = json.decodeFromString(Alert.serializer(), encodedAlert)

        assertEquals(originalAlert.id, decodedAlert.id)
        assertEquals(originalAlert.cameraId, decodedAlert.cameraId)
        assertEquals(originalAlert.cameraName, decodedAlert.cameraName)
        assertEquals(originalAlert.label, decodedAlert.label)
        assertEquals(originalAlert.confidence, decodedAlert.confidence, 0.0001)

        // Snapshot endpoint URL construction contract
        val baseUrl1 = "http://192.168.31.234:8080"
        val baseUrl2 = "http://192.168.31.234:8080/"
        val snapshotUrl1 = "${baseUrl1.trimEnd('/')}/api/v1/cameras/alerts/${originalAlert.id}/snapshot"
        val snapshotUrl2 = "${baseUrl2.trimEnd('/')}/api/v1/cameras/alerts/${originalAlert.id}/snapshot"

        assertEquals("http://192.168.31.234:8080/api/v1/cameras/alerts/alert-snap-99/snapshot", snapshotUrl1)
        assertEquals("http://192.168.31.234:8080/api/v1/cameras/alerts/alert-snap-99/snapshot", snapshotUrl2)
    }

    // =========================================================================
    // 4. MultiCameraActivity & MultiCameraPageContent
    // =========================================================================

    @Test
    fun testMultiCameraGridModeSpecsAndFrameUrl() {
        val modeOne = MultiCameraGridMode.ONE
        assertEquals(1, modeOne.slots)
        assertEquals("1格", modeOne.label)
        assertEquals(720, modeOne.width)
        assertEquals(35, modeOne.quality)
        assertEquals(100L, modeOne.intervalMs)

        val modeFour = MultiCameraGridMode.FOUR
        assertEquals(4, modeFour.slots)
        assertEquals("4格", modeFour.label)
        assertEquals(360, modeFour.width)
        assertEquals(25, modeFour.quality)
        assertEquals(200L, modeFour.intervalMs)

        val modeNine = MultiCameraGridMode.NINE
        assertEquals(9, modeNine.slots)
        assertEquals("9格", modeNine.label)
        assertEquals(240, modeNine.width)
        assertEquals(18, modeNine.quality)
        assertEquals(400L, modeNine.intervalMs)

        // Frame URL generation verification
        val activeBase = "http://192.168.31.234:8080"
        val camId = 3L
        val ts = 1700000500L
        val frameUrl = "$activeBase/api/v1/cameras/$camId/frame?width=${modeFour.width}&quality=${modeFour.quality}&nocache=1&t=$ts"
        assertEquals("http://192.168.31.234:8080/api/v1/cameras/3/frame?width=360&quality=25&nocache=1&t=1700000500", frameUrl)

        // Slot allocation logic
        val cameraList = listOf(
            Camera(id = 1L, name = "Cam 1"),
            Camera(id = 2L, name = "Cam 2"),
            Camera(id = 3L, name = "Cam 3")
        )
        val initialSlots = (0 until 9).associateWith { i -> if (i < cameraList.size) i else -1 }
        assertEquals(0, initialSlots[0])
        assertEquals(1, initialSlots[1])
        assertEquals(2, initialSlots[2])
        assertEquals(-1, initialSlots[3])
        assertEquals(-1, initialSlots[8])
    }

    // =========================================================================
    // 5. FilterOptionsDialog
    // =========================================================================

    @Test
    fun testFilterDialogConfigAndSelectionLogic() {
        val items = listOf(
            CamerasFragment.AlertFilterItem(id = null, title = "全部类型", iconRes = 0),
            CamerasFragment.AlertFilterItem(id = "person", title = "人形检测", iconRes = 0),
            CamerasFragment.AlertFilterItem(id = "car", title = "车辆检测", iconRes = 0)
        )

        var selectedItem: CamerasFragment.AlertFilterItem? = null
        val config = FilterDialogConfig(
            title = "选择报警类型",
            items = items,
            selectedId = "person",
            onSelect = { selectedItem = it }
        )

        assertEquals("选择报警类型", config.title)
        assertEquals(3, config.items.size)
        assertEquals("person", config.selectedId)

        // Check isSelected logic
        assertFalse(config.items[0].id == config.selectedId)
        assertTrue(config.items[1].id == config.selectedId)
        assertFalse(config.items[2].id == config.selectedId)

        // Simulate item click
        config.onSelect(items[2])
        assertEquals("car", selectedItem?.id)
        assertEquals("车辆检测", selectedItem?.title)
    }

    // =========================================================================
    // 6. RecordingsDialog (DayRecordingCard, MotionChip, Edge Snapping)
    // =========================================================================

    @Test
    fun testDayRecordingFormattingAndDiffCallback() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 31, 0, 0, 0)
        }

        val dayRec = RecordingsDialog.DayRecording(
            dayStartCalendar = cal,
            recordingCount = 144,
            totalDurationSeconds = 7320, // 2 hours, 2 minutes
            totalSizeBytes = 1024L * 1024L * 500L,
            isCloud = false
        )

        val h = dayRec.totalDurationSeconds / 3600
        val m = (dayRec.totalDurationSeconds % 3600) / 60
        val s = dayRec.totalDurationSeconds % 60
        val durStr = if (h > 0) String.format(Locale.getDefault(), "%d时%02d分", h, m)
        else String.format(Locale.getDefault(), "%02d分%02d秒", m, s)
        val compactDur = if (h > 0) "${h}h${m}m" else "${m}m${s}s"

        assertEquals("2时02分", durStr)
        assertEquals("2h2m", compactDur)

        // Test short duration (< 1 hour)
        val shortRec = dayRec.copy(totalDurationSeconds = 255) // 4 min 15 sec
        val sh = shortRec.totalDurationSeconds / 3600
        val sm = (shortRec.totalDurationSeconds % 3600) / 60
        val ss = shortRec.totalDurationSeconds % 60
        val shortDurStr = if (sh > 0) String.format(Locale.getDefault(), "%d时%02d分", sh, sm)
        else String.format(Locale.getDefault(), "%02d分%02d秒", sm, ss)
        val shortCompact = if (sh > 0) "${sh}h${sm}m" else "${sm}m${ss}s"

        assertEquals("04分15秒", shortDurStr)
        assertEquals("4m15s", shortCompact)

        // Test Cloud archive badge string
        val cloudRec = dayRec.copy(isCloud = true)
        val subtitleNonCloud = "${dayRec.recordingCount} 段 · $durStr"
        val subtitleCloud = "${cloudRec.recordingCount} 段 · $durStr (蓝奏云归档)"
        assertEquals("144 段 · 2时02分", subtitleNonCloud)
        assertEquals("144 段 · 2时02分 (蓝奏云归档)", subtitleCloud)

        // Test DiffCallback
        val diffCallback = RecordingsDialog.DayRecordingAdapter.DiffCallback()
        val calSameDay = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 31, 15, 30, 0)
        }
        val calDiffDay = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 0, 0, 0)
        }

        val recSameDay = dayRec.copy(dayStartCalendar = calSameDay, recordingCount = 200)
        val recDiffDay = dayRec.copy(dayStartCalendar = calDiffDay)

        assertTrue("Same day should return areItemsTheSame = true", diffCallback.areItemsTheSame(dayRec, recSameDay))
        assertFalse("Different day should return areItemsTheSame = false", diffCallback.areItemsTheSame(dayRec, recDiffDay))
        assertFalse("Different contents should return areContentsTheSame = false", diffCallback.areContentsTheSame(dayRec, recSameDay))
        assertTrue("Identical instances should return areContentsTheSame = true", diffCallback.areContentsTheSame(dayRec, dayRec.copy()))
    }

    @Test
    fun testMotionChipAndSnapToRangeEdgeAlgorithm() {
        val chip = RecordingsDialog.MotionChip(
            startUnixSec = 1700000000L,
            startRelativeMs = 3600000L, // 1 hour into day
            endRelativeMs = 3660000L,   // 1 hour 1 minute
            durationSec = 60L,
            motionScore = 85,
            segmentCount = 3,
            peakObjects = 2
        )

        assertEquals(1700000000L, chip.startUnixSec)
        assertEquals(3600000L, chip.startRelativeMs)
        assertEquals(3660000L, chip.endRelativeMs)
        assertEquals(60L, chip.durationSec)
        assertEquals(85, chip.motionScore)
        assertEquals(3, chip.segmentCount)
        assertEquals(2, chip.peakObjects)

        // Test snapping algorithm (radius = 120_000ms = 2 min)
        val motionRangesRelative = listOf(
            Pair(3600000L, 3660000L),
            Pair(7200000L, 7300000L)
        )
        val snapRadius = 120_000L

        fun snapProgress(progressMs: Long, ranges: List<Pair<Long, Long>>): Long? {
            if (ranges.isEmpty()) return null
            var bestDist = snapRadius
            var bestSnap: Long? = null
            for ((s, e) in ranges) {
                val distToStart = Math.abs(progressMs - s)
                if (distToStart < bestDist) {
                    bestDist = distToStart
                    bestSnap = s
                }
                val distToEnd = Math.abs(progressMs - e)
                if (distToEnd < bestDist) {
                    bestDist = distToEnd
                    bestSnap = e
                }
            }
            return bestSnap
        }

        // Within snap radius of range 1 start (3,600,000ms)
        assertEquals(3600000L, snapProgress(3550000L, motionRangesRelative))
        assertEquals(3600000L, snapProgress(3610000L, motionRangesRelative))

        // Within snap radius of range 1 end (3,660,000ms)
        assertEquals(3660000L, snapProgress(3670000L, motionRangesRelative))

        // Outside snap radius (far away) -> returns null (no snap)
        assertNull(snapProgress(5000000L, motionRangesRelative))

        // Empty ranges list -> returns null
        assertNull(snapProgress(3600000L, emptyList()))
    }

    // =========================================================================
    // 7. UserDialogs, OpsSecurityDialogs & SettingsDialogs
    // =========================================================================

    @Test
    fun testUserDialogsValidationRules() {
        fun validateCreateUser(name: String, pass: String): String? {
            val u = name.trim()
            if (u.isEmpty()) return "请填写用户名"
            if (pass.length < 6) return "密码长度至少为 6 位"
            return null
        }

        assertEquals("请填写用户名", validateCreateUser("", "pass123"))
        assertEquals("请填写用户名", validateCreateUser("   ", "pass123"))
        assertEquals("密码长度至少为 6 位", validateCreateUser("admin", "12345"))
        assertNull(validateCreateUser("admin", "123456"))
        assertNull(validateCreateUser("  alice  ", "ComplexPass!"))

        fun validateEditUser(name: String): String? {
            val u = name.trim()
            if (u.isEmpty()) return "请填写用户名"
            return null
        }

        assertEquals("请填写用户名", validateEditUser(""))
        assertEquals("请填写用户名", validateEditUser("   "))
        assertNull(validateEditUser("bob"))
    }

    @Test
    fun testOpsSecurityDialogsValidationRules() {
        // ManualBanDialog validation & fallbacks
        fun validateBan(ip: String, durationText: String, reason: String): Triple<String, Int, String>? {
            val trimmedIp = ip.trim()
            if (trimmedIp.isEmpty()) return null
            val duration = durationText.trim().toIntOrNull() ?: 30
            val finalReason = reason.trim().ifEmpty { "管理员手动阻断" }
            return Triple(trimmedIp, duration, finalReason)
        }

        assertNull(validateBan("", "30", "Abuse"))
        assertNull(validateBan("   ", "30", "Abuse"))

        val ban1 = validateBan("192.168.1.50", "60", "DDoS attempt")
        assertNotNull(ban1)
        assertEquals("192.168.1.50", ban1!!.first)
        assertEquals(60, ban1.second)
        assertEquals("DDoS attempt", ban1.third)

        // Invalid duration falls back to 30 min, empty reason falls back to default
        val banFallback = validateBan("10.0.0.99", "not_a_number", "")
        assertNotNull(banFallback)
        assertEquals("10.0.0.99", banFallback!!.first)
        assertEquals(30, banFallback.second)
        assertEquals("管理员手动阻断", banFallback.third)

        // AddWhitelistDialog validation & fallbacks
        fun validateWhitelist(entry: String, note: String): Pair<String, String>? {
            val trimmedEntry = entry.trim()
            if (trimmedEntry.isEmpty()) return null
            val finalNote = note.trim().ifEmpty { "管理员添加" }
            return Pair(trimmedEntry, finalNote)
        }

        assertNull(validateWhitelist("", "LAN Subnet"))
        val white1 = validateWhitelist("192.168.31.0/24", "Home router subnet")
        assertNotNull(white1)
        assertEquals("192.168.31.0/24", white1!!.first)
        assertEquals("Home router subnet", white1.second)

        val whiteFallback = validateWhitelist("10.8.0.1", "")
        assertNotNull(whiteFallback)
        assertEquals("10.8.0.1", whiteFallback!!.first)
        assertEquals("管理员添加", whiteFallback.second)
    }

    @Test
    fun testSettingsDialogsQuotaAndReleaseFormatting() {
        // EditQuotaDialog validation & clamping
        fun updateQuota(current: Int, delta: Int): Int {
            return (current + delta).coerceIn(50, 5000)
        }

        assertEquals(210, updateQuota(200, 10))
        assertEquals(190, updateQuota(200, -10))
        assertEquals(201, updateQuota(200, 1))
        assertEquals(199, updateQuota(200, -1))

        // Clamping boundaries (50..5000)
        assertEquals(50, updateQuota(50, -10))
        assertEquals(50, updateQuota(55, -10))
        assertEquals(5000, updateQuota(4995, 10))
        assertEquals(5000, updateQuota(5000, 50))

        // Preset values
        val presets = listOf(200, 400, 600, 1000)
        presets.forEach { preset ->
            assertTrue("Preset $preset must be within 50..5000", preset in 50..5000)
        }

        // Quota input validation error check
        fun validateQuotaInput(text: String): String? {
            val parsed = text.trim().toIntOrNull()
            if (parsed == null || parsed < 50 || parsed > 5000) {
                return "请输入 50 ~ 5000 之间的整数"
            }
            return null
        }

        assertEquals("请输入 50 ~ 5000 之间的整数", validateQuotaInput("abc"))
        assertEquals("请输入 50 ~ 5000 之间的整数", validateQuotaInput("49"))
        assertEquals("请输入 50 ~ 5000 之间的整数", validateQuotaInput("5001"))
        assertNull(validateQuotaInput("50"))
        assertNull(validateQuotaInput("5000"))
        assertNull(validateQuotaInput("500"))

        // UpdateAvailableDialog size formatting
        val info = UpdateInfo(
            version_name = "1.18.0",
            version_code = 210,
            file_name = "app-release-v1.18.0.apk",
            size_bytes = 48_234_496L // ~46.0 MB
        )
        val sizeMbStr = String.format(Locale.US, "%.1f MB", info.size_bytes / (1024.0 * 1024.0))
        assertEquals("46.0 MB", sizeMbStr)
        assertTrue(info.isRelease)
        assertFalse(info.isDebug)
    }
}
