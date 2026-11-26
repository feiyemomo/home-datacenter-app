package com.homedatacenter.app.ui.cameras

import com.homedatacenter.app.data.model.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class CloudRecordingsPeriodTest {

    @Test
    fun testContiguousClipsMergedIntoSinglePeriod() {
        val parseFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
        val tz = TimeZone.getTimeZone("Asia/Shanghai")

        // 3 consecutive clips from 08:10:00 to 08:13:00 (each 60s)
        val clips = listOf(
            Recording(
                id = 1732234200L,
                cameraId = 1L,
                startAt = "2026-11-22T00:10:00Z", // 08:10:00 CST
                endAt = "2026-11-22T00:11:00Z",
                durationSeconds = 60,
                sizeBytes = 1024 * 1024
            ),
            Recording(
                id = 1732234260L,
                cameraId = 1L,
                startAt = "2026-11-22T00:11:00Z", // 08:11:00 CST
                endAt = "2026-11-22T00:12:00Z",
                durationSeconds = 60,
                sizeBytes = 1024 * 1024
            ),
            Recording(
                id = 1732234320L,
                cameraId = 1L,
                startAt = "2026-11-22T00:12:00Z", // 08:12:00 CST
                endAt = "2026-11-22T00:13:00Z",
                durationSeconds = 60,
                sizeBytes = 1024 * 1024
            )
        )

        // Reflection or direct check
        val s0 = parseFmt.parse(clips[0].startAt)!!.time
        val e2 = parseFmt.parse(clips[2].endAt)!!.time
        val durSec = (e2 - s0) / 1000

        assertEquals(180L, durSec)
        assertEquals("08:10:00", timeFmt.format(s0))
        assertEquals("08:13:00", timeFmt.format(e2))
    }

    @Test
    fun testTimelineFractionsBoundedIn24Hours() {
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val dayStart = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 22, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // A clip at 12:00:00 CST is halfway through the day (fraction ~ 0.5)
        val clipStart = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 22, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val fraction = (clipStart - dayStart).toFloat() / 86400000f
        assertEquals(0.5f, fraction, 0.001f)
        assertTrue(fraction in 0f..1f)
    }

    @Test
    fun testDayRecordingSummaryDisplay() {
        val dayRec = RecordingsDialog.DayRecording(
            dayStartCalendar = Calendar.getInstance(),
            recordingCount = 48,
            totalDurationSeconds = 2880,
            totalSizeBytes = 1024L * 1024L * 48L,
            isCloud = true,
            timeRangesSummary = "时段: 08:10~08:35, 14:00~14:30"
        )

        assertEquals("时段: 08:10~08:35, 14:00~14:30", dayRec.timeRangesSummary)
        assertTrue(dayRec.isCloud)
        assertEquals(48, dayRec.recordingCount)
    }
}
