package com.homedatacenter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ApkInstaller.compareVersions]. This method is the
 * root fix behind the "updates forever" bug: the backend derives a
 * version_code from the filename ("1.6.12" -> 10612) while the app's
 * versionCode is a flat integer (55, 56, ...). Comparing versionName
 * strings sidesteps that mismatch.
 */
class ApkInstallerTest {

    @Test
    fun compareVersions_ordersNewerFirst() {
        assertTrue(ApkInstaller.compareVersions("1.9.0", "1.8.48") > 0)
        assertTrue(ApkInstaller.compareVersions("1.8.49", "1.8.48") > 0)
        assertTrue(ApkInstaller.compareVersions("1.6.13", "1.6.12") > 0)
    }

    @Test
    fun compareVersions_ordersOlderNegative() {
        assertTrue(ApkInstaller.compareVersions("1.8.48", "1.9.0") < 0)
        assertTrue(ApkInstaller.compareVersions("1.6.12", "1.6.13") < 0)
        assertTrue(ApkInstaller.compareVersions("1.0.0", "1.0.1") < 0)
    }

    @Test
    fun compareVersions_equalIsZero() {
        assertEquals(0, ApkInstaller.compareVersions("1.6.12", "1.6.12"))
        assertEquals(0, ApkInstaller.compareVersions("1.0.0", "1.0.0"))
    }

    @Test
    fun compareVersions_missingComponentsTreatedAsZero() {
        // "1.6" == "1.6.0", "1.6.0" > "1.6" is equal actually
        assertEquals(0, ApkInstaller.compareVersions("1.6", "1.6.0"))
        assertTrue(ApkInstaller.compareVersions("1.6.0", "1.6") == 0)
        assertTrue(ApkInstaller.compareVersions("1.6.1", "1.6") > 0)
    }

    @Test
    fun compareVersions_nonNumericTreatedAsZero() {
        // Non-numeric components are coerced to 0.
        assertEquals(0, ApkInstaller.compareVersions("1.x.3", "1.0.3"))
        assertTrue(ApkInstaller.compareVersions("1.9", "1.10") < 0)
        assertTrue(ApkInstaller.compareVersions("1.10", "1.9") > 0)
    }
}
