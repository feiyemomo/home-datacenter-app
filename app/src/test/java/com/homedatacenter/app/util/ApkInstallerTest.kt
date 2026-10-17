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

    @Test
    fun parseVersionCode_matchesBackendFormula() {
        assertEquals(10612, ApkInstaller.parseVersionCode("1.6.12"))
        assertEquals(11408, ApkInstaller.parseVersionCode("1.14.8"))
        assertEquals(11400, ApkInstaller.parseVersionCode("1.14.0"))
        assertEquals(0, ApkInstaller.parseVersionCode("invalid"))
    }

    @Test
    fun hasUpdate_preventsPerpetualUpdateWhenScaleMismatched() {
        // Installed has flat integer versionCode 55, remote has synthetic derived versionCode 10612.
        // Both have identical versionName "1.6.12". Should NOT prompt for update!
        org.junit.Assert.assertFalse(
            ApkInstaller.hasUpdate(
                installedVersionCode = 55,
                installedVersionName = "1.6.12",
                remoteVersionCode = 10612,
                remoteVersionName = "1.6.12"
            )
        )

        // Same for current version: 194 vs 11408
        org.junit.Assert.assertFalse(
            ApkInstaller.hasUpdate(
                installedVersionCode = 194,
                installedVersionName = "1.14.8",
                remoteVersionCode = 11408,
                remoteVersionName = "1.14.8"
            )
        )
    }

    @Test
    fun hasUpdate_detectsNewerSemanticVersionName() {
        // Newer semantic version available
        assertTrue(
            ApkInstaller.hasUpdate(
                installedVersionCode = 194,
                installedVersionName = "1.14.8",
                remoteVersionCode = 11409,
                remoteVersionName = "1.14.9"
            )
        )

        // Even if remote versionCode is 0 or unpopulated
        assertTrue(
            ApkInstaller.hasUpdate(
                installedVersionCode = 194,
                installedVersionName = "1.14.8",
                remoteVersionCode = 0,
                remoteVersionName = "1.14.9"
            )
        )
    }

    @Test
    fun hasUpdate_detectsNewerVersionCodeOnSameScale() {
        // Hotfix build with same versionName, bumped flat versionCode (195 > 194)
        assertTrue(
            ApkInstaller.hasUpdate(
                installedVersionCode = 194,
                installedVersionName = "1.14.8",
                remoteVersionCode = 195,
                remoteVersionName = "1.14.8"
            )
        )

        // Older flat versionCode
        org.junit.Assert.assertFalse(
            ApkInstaller.hasUpdate(
                installedVersionCode = 195,
                installedVersionName = "1.14.8",
                remoteVersionCode = 194,
                remoteVersionName = "1.14.8"
            )
        )
    }

    @Test
    fun hasUpdate_rejectsOlderVersions() {
        org.junit.Assert.assertFalse(
            ApkInstaller.hasUpdate(
                installedVersionCode = 194,
                installedVersionName = "1.14.8",
                remoteVersionCode = 11407,
                remoteVersionName = "1.14.7"
            )
        )
    }
}
