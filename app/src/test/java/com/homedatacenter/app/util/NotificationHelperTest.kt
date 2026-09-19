package com.homedatacenter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NotificationHelperTest {

    @Test
    fun `channel constants are correctly configured`() {
        assertEquals("security_alerts_channel", NotificationHelper.CHANNEL_SECURITY_ALERTS)
        assertEquals("system_alerts_channel", NotificationHelper.CHANNEL_SYSTEM_ALERTS)
    }

    @Test
    fun `extra intent keys are defined`() {
        assertNotNull(NotificationHelper.EXTRA_NAVIGATE_TAB)
        assertNotNull(NotificationHelper.EXTRA_ALERT_CAMERA_ID)
        assertNotNull(NotificationHelper.EXTRA_ALERT_START_TS)
        assertEquals("extra_navigate_tab", NotificationHelper.EXTRA_NAVIGATE_TAB)
        assertEquals("extra_alert_camera_id", NotificationHelper.EXTRA_ALERT_CAMERA_ID)
        assertEquals("extra_alert_start_ts", NotificationHelper.EXTRA_ALERT_START_TS)
    }
}
