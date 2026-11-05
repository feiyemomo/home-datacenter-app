package com.homedatacenter.app.ui

import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemLogLevel
import com.homedatacenter.app.ui.glass.DarkGlassTokens
import com.homedatacenter.app.ui.glass.LightGlassTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying State, Models and Liquid Glass specifications
 * for Dashboard, Ops, Users, and Dialog glassification.
 */
class LiquidGlassModulesStateTest {

    @Test
    fun testGlassTokensMicrocrystallineBorderSpecs() {
        // Microcrystalline border width must be at least 1.2dp
        assertTrue("Dark glass border width must be >= 1.2dp", DarkGlassTokens.borderWidth.value >= 1.2f)
        assertTrue("Light glass border width must be >= 1.2dp", LightGlassTokens.borderWidth.value >= 1.2f)

        // Specular border colors must contain highlight layers
        assertTrue(DarkGlassTokens.borderColors.size >= 2)
        assertTrue(LightGlassTokens.borderColors.size >= 2)

        // Card corner radius standard is 24dp for modern Compose liquid glass cards
        assertEquals(24f, DarkGlassTokens.cornerRadius.value, 0.01f)
        assertEquals(24f, LightGlassTokens.cornerRadius.value, 0.01f)
    }

    @Test
    fun testSystemLogLevelSeverity() {
        val criticalLog = SystemLog(id = 1, level = SystemLogLevel.CRITICAL, message = "Camera offline")
        val warningLog = SystemLog(id = 2, level = SystemLogLevel.WARNING, message = "Disk 90%")
        val normalLog = SystemLog(id = 3, level = SystemLogLevel.NORMAL, message = "User login")
        val infoLog = SystemLog(id = 4, level = SystemLogLevel.INFO, message = "Stream started")

        assertEquals(SystemLogLevel.CRITICAL, criticalLog.level)
        assertEquals(SystemLogLevel.WARNING, warningLog.level)
        assertEquals(SystemLogLevel.NORMAL, normalLog.level)
        assertEquals(SystemLogLevel.INFO, infoLog.level)
    }

    @Test
    fun testAlertModelConfidenceAndKey() {
        val alert = Alert(
            id = "alert-101",
            cameraId = 1L,
            cameraName = "Living Room",
            label = "person",
            confidence = 0.95,
            startTime = 1700000000.0,
            endTime = 1700000010.0
        )

        assertEquals("alert-101", alert.id)
        assertEquals("person", alert.label)
        assertEquals(95, (alert.confidence * 100).toInt())
    }

    @Test
    fun testSpecularSheenOrthogonalMath() {
        // Normal vector N at 35 degrees: (cos 35°, -sin 35°)
        val angleDeg = 35.0
        val angleRad = Math.toRadians(angleDeg)
        val cosVal = Math.cos(angleRad).toFloat()
        val sinVal = Math.sin(angleRad).toFloat()

        // Beam direction vector V = (sin 35°, cos 35°)
        // Dot product of N and V must be zero
        val dot = (cosVal * sinVal) + (-sinVal * cosVal)
        assertEquals(0f, dot, 0.00001f)

        // Tangent represents slope
        val slope = sinVal / cosVal
        assertEquals(Math.tan(angleRad).toFloat(), slope, 0.00001f)
    }
}
