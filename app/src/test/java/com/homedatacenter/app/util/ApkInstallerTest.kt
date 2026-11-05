package com.homedatacenter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun compareVersions_handlesPrereleaseTagsCorrectly() {
        // Blocker 2 regression test: 1.17.1-rc1 must be strictly newer than 1.17.0!
        assertTrue(ApkInstaller.compareVersions("1.17.1-rc1", "1.17.0") > 0)
        assertTrue(ApkInstaller.compareVersions("1.17.0", "1.17.1-rc1") < 0)

        // Release is newer than prerelease of same major.minor.patch
        assertTrue(ApkInstaller.compareVersions("1.17.1", "1.17.1-rc1") > 0)
        assertTrue(ApkInstaller.compareVersions("1.17.1-rc1", "1.17.1") < 0)

        // Newer rc is greater than older rc
        assertTrue(ApkInstaller.compareVersions("1.17.1-rc2", "1.17.1-rc1") > 0)
        assertTrue(ApkInstaller.compareVersions("1.17.1-rc1", "1.17.1-rc2") < 0)

        // Identical
        assertEquals(0, ApkInstaller.compareVersions("1.17.1-rc1", "1.17.1-rc1"))
    }

    @Test
    fun extractVersionFromFileName_matchesValidConventions() {
        assertEquals("1.17.0", ApkInstaller.extractVersionFromFileName("app-debug-v1.17.0.apk"))
        assertEquals("1.6.11", ApkInstaller.extractVersionFromFileName("app-release-v1.6.11.apk"))
        assertEquals("1.15.0", ApkInstaller.extractVersionFromFileName("home-datacenter-app-1.15.0.apk"))
        assertEquals("1.8.2", ApkInstaller.extractVersionFromFileName("app-debug_v1.8.2.apk"))
        assertEquals("2.0.0-rc1", ApkInstaller.extractVersionFromFileName("app-debug-v2.0.0-rc1.apk"))

        assertNull(ApkInstaller.extractVersionFromFileName("app-debug.apk"))
        assertNull(ApkInstaller.extractVersionFromFileName("test.txt"))
        assertNull(ApkInstaller.extractVersionFromFileName(""))
        assertNull(ApkInstaller.extractVersionFromFileName("app-debug-v.apk"))
    }

    @Test
    fun pruneStaleDownloads_scenarioA_noTarget_purgesOlderApksAndPreservesNewerFiles() {
        val downloadsDir = tempFolder.newFolder("downloads_a")

        val oldApk = File(downloadsDir, "app-debug-v1.16.0.apk").apply {
            writeBytes(ByteArray(100) { 1 })
        }
        val sameApk = File(downloadsDir, "app-debug-v1.17.0.apk").apply {
            writeBytes(ByteArray(150) { 2 })
        }
        val futureApk = File(downloadsDir, "app-debug-v1.18.0.apk").apply {
            writeBytes(ByteArray(200) { 3 })
        }
        val futurePart = File(downloadsDir, "app-debug-v1.18.0.apk.part").apply {
            writeBytes(ByteArray(250) { 4 })
        }
        val oldPart = File(downloadsDir, "app-debug-v1.16.0.apk.part").apply {
            writeBytes(ByteArray(50) { 5 })
        }
        val orphanPart = File(downloadsDir, "incomplete.part").apply {
            writeBytes(ByteArray(30) { 6 })
        }

        val freedBytes = ApkInstaller.pruneStaleDownloads(
            downloadsDir = downloadsDir,
            installedVersionName = "1.17.0",
            preserveTargetFile = null
        )

        // v1.16.0.apk (100) + v1.17.0.apk (150) + v1.16.0.part (50) + orphanPart (30) = 330 bytes freed
        assertEquals(330L, freedBytes)
        assertFalse(oldApk.exists())
        assertFalse(sameApk.exists())
        assertFalse(oldPart.exists())
        assertFalse(orphanPart.exists())

        // v1.18.0 pending APK and its resumable .part file MUST BE PRESERVED!
        assertTrue(futureApk.exists())
        assertEquals(200L, futureApk.length())
        assertTrue(futurePart.exists())
        assertEquals(250L, futurePart.length())
    }

    @Test
    fun pruneStaleDownloads_scenarioB_withPreserveTarget_preservesTargetAndItsPartFile() {
        val downloadsDir = tempFolder.newFolder("downloads_b")

        val targetApk = File(downloadsDir, "app-debug-v1.19.0.apk").apply {
            writeBytes(ByteArray(500) { 1 })
        }
        val targetPart = File(downloadsDir, "app-debug-v1.19.0.apk.part").apply {
            writeBytes(ByteArray(250) { 2 })
        }
        val oldApk = File(downloadsDir, "app-debug-v1.18.0.apk").apply {
            writeBytes(ByteArray(400) { 3 })
        }
        val orphanPart = File(downloadsDir, "test.part").apply {
            writeBytes(ByteArray(80) { 4 })
        }

        val freedBytes = ApkInstaller.pruneStaleDownloads(
            downloadsDir = downloadsDir,
            installedVersionName = "1.17.0",
            preserveTargetFile = targetApk
        )

        // oldApk (400) and orphanPart (80) = 480 bytes deleted
        assertEquals(480L, freedBytes)
        assertFalse(oldApk.exists())
        assertFalse(orphanPart.exists())

        // Target APK and its companion .part must be preserved
        assertTrue(targetApk.exists())
        assertTrue(targetPart.exists())
    }

    @Test
    fun pruneStaleDownloads_nonExistentOrEmptyDir_returnsZero() {
        val nonExistentDir = File(tempFolder.root, "non_existent")
        assertEquals(0L, ApkInstaller.pruneStaleDownloads(nonExistentDir, "1.17.0"))

        val emptyDir = tempFolder.newFolder("empty_downloads")
        assertEquals(0L, ApkInstaller.pruneStaleDownloads(emptyDir, "1.17.0"))
    }

    @Test
    fun pruneStaleDownloads_ignoresSubdirectoriesAndCleansUnparseableApk() {
        val downloadsDir = tempFolder.newFolder("downloads_c")

        val subDir = File(downloadsDir, "nested_folder").apply { mkdir() }
        val nestedFile = File(subDir, "inner.apk").apply { writeBytes(ByteArray(50)) }

        val unparseableApk = File(downloadsDir, "unparseable.apk").apply {
            writeBytes(ByteArray(120) { 7 })
        }

        val freedBytes = ApkInstaller.pruneStaleDownloads(
            downloadsDir = downloadsDir,
            installedVersionName = "1.17.0",
            preserveTargetFile = null
        )

        assertEquals(120L, freedBytes)
        assertFalse(unparseableApk.exists())
        assertTrue(subDir.exists())
        assertTrue(nestedFile.exists())
    }
}
