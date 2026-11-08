package com.homedatacenter.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.homedatacenter.app.data.model.UpdateInfo
import com.homedatacenter.app.data.repository.HomeCenterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * v1.6.11: in-app APK self-update. Downloads the latest APK from
 * /api/v1/release/latest/apk to the app's private storage, then
 * launches the system PackageInstaller via ACTION_INSTALL_PACKAGE.
 *
 * Flow:
 *   1. Caller (SettingsFragment or AppContainer startup check)
 *      fetches UpdateInfo via HomeCenterRepository.getLatestRelease.
 *   2. If server versionCode > installed versionCode, caller shows
 *      a confirmation dialog.
 *   3. On confirm, [downloadAndInstall] is invoked with the token
 *      and an onProgress callback (0..100).
 *   4. The APK streams to filesDir/downloads/<file_name>.apk.
 *   5. A content:// URI is generated via FileProvider and passed
 *      to ACTION_INSTALL_PACKAGE.
 *   6. The system PackageInstaller takes over — the user confirms
 *      install (and on first run grants "install unknown apps"
 *      permission for our app).
 *
 * Note: the install Intent requires an Activity context (not
 * application context) on most OEM ROMs, so [downloadAndInstall]
 * takes an Activity.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /**
     * Download the APK from /api/v1/release/latest/apk to private
     * storage only — does NOT launch the installer. This is the
     * background-download half of the v1.6.28 update flow:
     * AppContainer.startBackgroundDownload calls this as soon as a
     * new version is detected, so the APK is ready on disk by the
     * time the user visits the settings page.
     *
     * Must be called from a coroutine — the network I/O runs on
     * Dispatchers.IO.
     *
     * @param context   any context — only used to resolve
     *                  filesDir/downloads for the saved APK. Does
     *                  NOT need to be an Activity.
     * @param repo      HomeCenterRepository — used for the streaming
     *                  download call.
     * @param token     JWT — the /release/latest/apk endpoint is
     *                  JWT-protected (registered under /api/v1 with
     *                  JWTAuth middleware in cmd/main.go).
     * @param info      UpdateInfo (from getLatestRelease) — used for
     *                  the local file name so each version saves to
     *                  its own file and an interrupted download of
     *                  v1.6.12 doesn't corrupt a working v1.6.11.
     * @param onProgress  optional callback receiving download
     *                  progress as 0..100. Called from the IO
     *                  dispatcher — caller is responsible for
     *                  hopping to main if updating UI.
     *
     * @return the downloaded File on success, null on any error
     *         (network, disk, FileProvider).
     */
    suspend fun downloadOnly(
        context: Context,
        repo: HomeCenterRepository,
        token: String,
        info: UpdateInfo,
        onProgress: ((Int) -> Unit)? = null,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val fileName = if (info.file_name.isNotEmpty()) info.file_name
                else "app-debug-v${info.version_name}.apk"
            val downloadsDir = File(context.filesDir, "downloads").apply {
                if (!exists()) mkdirs()
            }
            val apkFile = File(downloadsDir, fileName)

            // Prune any older APKs and abandoned .part files before downloading,
            // preserving only the active target file and its .part companion.
            pruneStaleDownloads(downloadsDir, installedVersionName(context), apkFile)

            // v1.7.22: resumable download. If a partial file from a
            // previous interrupted attempt exists AND the server
            // reports a size for this release, send an HTTP Range
            // request to continue from where we left off instead of
            // starting over. The server (Go http.ServeFile behind
            // Gin's c.File) natively supports Range — it returns
            // 206 Partial Content with the remaining bytes.
            //
            // We track the partial file alongside a ".part" suffix
            // so a completed-but-unverified file doesn't get
            // mistaken for a partial download on the next run.
            val partFile = File(downloadsDir, "$fileName.part")
            val existingBytes = if (partFile.exists()) partFile.length() else 0L
            val totalSize = info.size_bytes
            val canResume = existingBytes > 0 && totalSize > 0 && existingBytes < totalSize

            val rangeHeader = if (canResume) "bytes=$existingBytes-" else null
            if (canResume) {
                Log.d(TAG, "Resuming download: $existingBytes/$totalSize bytes (${(existingBytes * 100 / totalSize)}%)")
            }

            val flavorParam = if (info.isRelease) "release" else if (info.isDebug) "debug" else null
            val response = repo.downloadLatestApk(token, rangeHeader, flavorParam)
            if (!response.isSuccessful) {
                Log.e(TAG, "Download request failed: HTTP ${response.code()}")
                // 416 Range Not Satisfiable can happen if the part
                // file is larger than the actual APK (e.g. the file
                // on the server was replaced with a smaller one).
                // Delete the stale part file and fall through to a
                // full re-download on the next attempt — for now
                // just return null.
                if (response.code() == 416 && partFile.exists()) {
                    Log.w(TAG, "Range not satisfiable — deleting stale part file")
                    partFile.delete()
                }
                return@withContext null
            }

            val body = response.body() ?: return@withContext null
            val code = response.code()
            val isResume = code == 206

            // Determine total size for progress reporting:
            //   200 OK       → Content-Length is the full APK size
            //   206 Partial  → Content-Length is the remaining bytes;
            //                   use Content-Range header for total.
            // Either way, fall back to info.size_bytes if the header
            // is missing or unparsable.
            val total = when {
                isResume -> {
                    val contentRange = response.headers()["Content-Range"]
                    parseTotalFromContentRange(contentRange) ?: totalSize
                }
                else -> body.contentLength().let { if (it > 0) it else totalSize }
            }.let { if (it > 0) it else totalSize }

            // Decide write mode:
            //   resume (206) → append to the existing .part file
            //   full (200)   → truncate the .part file and start fresh
            val appendMode = isResume
            var downloaded = if (appendMode) existingBytes else 0L
            var lastReportedPercent = if (total > 0) ((downloaded * 100) / total).toInt() else -1

            body.use { responseBody ->
                responseBody.byteStream().use { input ->
                    FileOutputStream(partFile, appendMode).use { output ->
                        val buf = ByteArray(64 * 1024) // 64KB read buffer
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = ((downloaded * 100) / total).toInt()
                                if (percent != lastReportedPercent && onProgress != null) {
                                    lastReportedPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
            }

            // Integrity check: verify the downloaded file matches
            // the server-reported size, SHA-256 checksum (if provided), and is a valid APK package.
            if (!verifyApkIntegrity(context, partFile, info.sha256, totalSize)) {
                Log.e(TAG, "Download integrity verification failed for ${partFile.name}")
                partFile.delete()
                return@withContext null
            }

            // Download complete — rename .part → final name. This
            // is atomic on POSIX and near-atomic on Windows (POSIX
            // rename semantics via Files.move with REPLACE_EXISTING).
            // After rename, isApkCached() (which checks the final
            // name) returns true and startBackgroundDownload skips
            // re-downloading.
            if (apkFile.exists()) apkFile.delete()
            if (!partFile.renameTo(apkFile)) {
                Log.e(TAG, "Failed to rename ${partFile.name} → ${apkFile.name}")
                return@withContext null
            }

            // Post-rename verification: full PackageArchive parse on the final .apk file
            if (!verifyApkIntegrity(context, apkFile, info.sha256, totalSize)) {
                Log.e(TAG, "Post-rename integrity verification failed for ${apkFile.name}")
                apkFile.delete()
                return@withContext null
            }

            Log.d(TAG, "Downloaded ${apkFile.length()} bytes to ${apkFile.absolutePath} " +
                "(resumed=$isResume)")
            apkFile
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            // Keep the .part file for resume on the next attempt.
            null
        }
    }

    /**
     * Parse the total file size from an HTTP Content-Range header
     * of the form "bytes 5242880-104857599/104857600". Returns the
     * total (the part after '/') or null if the header is missing
     * or malformed.
     */
    private fun parseTotalFromContentRange(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        // Format: "bytes <start>-<end>/<total>"
        val slashIdx = contentRange.lastIndexOf('/')
        if (slashIdx < 0 || slashIdx == contentRange.length - 1) return null
        return contentRange.substring(slashIdx + 1).toLongOrNull()
    }

    /**
     * Download the APK and immediately launch the system
     * PackageInstaller. Convenience wrapper around [downloadOnly] +
     * [launchInstaller] kept for the legacy one-shot flow.
     *
     * The install Intent requires an Activity context (not
     * application context) on most OEM ROMs, so this takes an
     * Activity.
     *
     * @return true if the install Intent was launched, false on
     *         any error (network, disk, FileProvider).
     */
    suspend fun downloadAndInstall(
        activity: Activity,
        repo: HomeCenterRepository,
        token: String,
        info: UpdateInfo,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean {
        val apkFile = downloadOnly(activity, repo, token, info, onProgress) ?: return false
        // Hop to main thread for startActivity — the install
        // Intent must be launched from an Activity context on
        // the UI thread.
        withContext(Dispatchers.Main) {
            launchInstaller(activity, apkFile)
        }
        return true
    }

    /**
     * v1.6.28: check whether the APK for [info] is already on disk
     * and complete (file exists AND its byte length matches
     * [UpdateInfo.size_bytes]). Used by AppContainer.startBackground
     * Download to skip re-downloading an APK that was already
     * fetched in a previous session.
     *
     * If [UpdateInfo.file_name] is empty, falls back to
     * "app-debug-v<version_name>.apk" — same convention as
     * [downloadOnly].
     */
    fun isApkCached(context: Context, info: UpdateInfo): Boolean {
        val fileName = if (info.file_name.isNotEmpty()) info.file_name
            else "app-debug-v${info.version_name}.apk"
        val apkFile = File(File(context.filesDir, "downloads"), fileName)
        return verifyApkIntegrity(context, apkFile, info.sha256, info.size_bytes)
    }

    /**
     * Extracts and normalizes a 64-character hex SHA-256 string, tolerating
     * protocol prefixes ("sha256:") and standard sha256sum output format ("<hash>  <filename>" or "<hash> *<filename>").
     */
    fun cleanSha256(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim()
            .removePrefix("sha256:")
            .removePrefix("SHA256:")
            .trim()
        val token = normalized.split(Regex("\\s+")).firstOrNull()?.trim() ?: return null
        return if (token.length == 64 && token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            token.lowercase()
        } else {
            raw.trim().lowercase()
        }
    }

    /**
     * Verifies that the given APK file exists, matches expected size / SHA-256
     * (if specified), and can be parsed by Android's PackageManager.
     */
    fun verifyApkIntegrity(
        context: Context,
        file: File,
        expectedSha256: String? = null,
        expectedSize: Long = 0L,
    ): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return false
        if (expectedSize > 0L && file.length() != expectedSize) {
            Log.e(TAG, "Integrity check failed: file size ${file.length()} != expected $expectedSize")
            return false
        }
        val targetHash = cleanSha256(expectedSha256)
        if (!targetHash.isNullOrBlank()) {
            val actualHash = computeSha256(file)
            if (!actualHash.equals(targetHash, ignoreCase = true)) {
                Log.e(TAG, "Integrity check failed: sha256 mismatch (actual=$actualHash vs expected=$targetHash)")
                return false
            }
        }
        val pm = runCatching { context.packageManager }.getOrNull()
        if (pm != null) {
            val archiveInfo = runCatching { pm.getPackageArchiveInfo(file.absolutePath, 0) }.getOrNull()
            if (archiveInfo == null) {
                // If checking an in-progress .part file on certain OEM devices, package parsing might fail
                // solely due to the non-.apk extension. If size and/or sha256 passed, permit the .part file
                // to proceed to rename, where post-rename verification on .apk will enforce full PackageArchive parsing.
                val hasSizeOrHashCheck = expectedSize > 0L || !targetHash.isNullOrBlank()
                if (!file.name.endsWith(".part") || !hasSizeOrHashCheck) {
                    Log.e(TAG, "Integrity check failed: failed to parse APK archive (corrupt or invalid package)")
                    return false
                }
            } else {
                val expectedBase = context.packageName.removeSuffix(".debug")
                val actualBase = archiveInfo.packageName?.removeSuffix(".debug")
                if (actualBase != expectedBase) {
                    Log.e(TAG, "Integrity check failed: package name mismatch (${archiveInfo.packageName} != ${context.packageName})")
                    return false
                }
            }
        }
        return true
    }

    /**
     * Computes the SHA-256 hex digest of the given file.
     */
    fun computeSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Launch ACTION_INSTALL_PACKAGE for the downloaded APK. Uses
     * FileProvider to generate a content:// URI (file:// URIs are
     * rejected on API 24+).
     *
     * On Android 8.0+ the user must grant "install unknown apps"
     * permission for our app the first time. The system handles
     * this prompt automatically when ACTION_INSTALL_PACKAGE is
     * launched — if permission isn't granted yet, the user is sent
     * to the settings screen, then returns to our install Intent
     * after granting.
     */
    fun launchInstaller(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            // EXTRA_NOT_UNKNOWN_SOURCE: tell the installer we're not
            // a random source — the user explicitly requested this
            // update from inside our app. Skips the secondary
            // "are you sure?" prompt on some OEM ROMs.
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            // EXTRA_RETURN_RESULT: we'd like a result code, but
            // the system PackageInstaller doesn't reliably honor
            // this on all ROMs. We don't currently wait for a
            // result — the user just sees the standard install
            // confirmation screen.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }
        }

        // Grant read permission to the system PackageInstaller so
        // it can read the APK from our private storage. Most ROMs
        // honor FLAG_GRANT_READ_URI_PERMISSION already, but
        // explicitly granting to the installer package as well
        // covers a few OEM-specific edge cases.
        val installerPkg = "com.android.packageinstaller"
        context.grantUriPermission(
            installerPkg, uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        context.startActivity(intent)
    }

    /**
     * Get the installed app's versionCode — used by the caller to
     * compare against [UpdateInfo.versionCode] and decide whether
     * to show the update prompt.
     */
    fun installedVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read installed versionCode: ${e.message}")
            0
        }
    }

    /**
     * Get the installed app's versionName (e.g. "1.6.11") for
     * display in the update dialog ("1.6.10 → 1.6.11").
     */
    fun installedVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * v1.6.14: Compare two semantic version strings of the form
     * "MAJOR.MINOR.PATCH" (e.g. "1.6.13" vs "1.6.12").
     *
     * Returns a negative integer if `a` is older, zero if equal,
     * positive if `a` is newer. Non-numeric components are treated
     * as 0. Missing components are treated as 0 (so "1.6" == "1.6.0").
     *
     * Why this exists: the backend's `/api/v1/release/latest`
     * endpoint derives `version_code` from the APK filename via
     * `parseVersionCode("1.6.12") = 1*10000 + 6*100 + 12 = 10612`,
     * but the Android app's `versionCode` in build.gradle.kts is a
     * flat integer that increments by 1 per release (e.g. 55, 56).
     * The two numbering schemes are on completely different scales,
     * so `info.version_code > installed` is ALWAYS true — the app
     * keeps prompting to "update" from 1.6.12 to 1.6.12.
     *
     * Comparing versionName strings instead sidesteps the
     * mismatch entirely — both client and server agree on the
     * "X.Y.Z" naming convention.
     */
    fun compareVersions(a: String, b: String): Int {
        val cleanA = a.trim().removePrefix("v").removePrefix("V")
        val cleanB = b.trim().removePrefix("v").removePrefix("V")
        val pa = cleanA.split(".").map { it.substringBefore("-").toIntOrNull() ?: 0 }
        val pb = cleanB.split(".").map { it.substringBefore("-").toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va - vb
        }
        // Numeric components are identical (e.g. "1.17.1-rc1" vs "1.17.1")
        val preA = if (cleanA.contains("-")) cleanA.substringAfter("-") else null
        val preB = if (cleanB.contains("-")) cleanB.substringAfter("-") else null
        return when {
            preA == null && preB == null -> 0
            preA == null && preB != null -> 1   // 1.17.1 (release) > 1.17.1-rc1 (prerelease)
            preA != null && preB == null -> -1  // 1.17.1-rc1 < 1.17.1
            else -> preA!!.compareTo(preB!!)    // 1.17.1-rc2 > 1.17.1-rc1
        }
    }

    /**
     * Parse semantic version string to backend-derived synthetic code:
     * "1.6.12" -> 1*10000 + 6*100 + 12 = 10612.
     * Returns 0 if string is not in MAJOR.MINOR.PATCH format.
     */
    fun parseVersionCode(versionName: String): Int {
        val parts = versionName.trim().removePrefix("v").removePrefix("V").split(".")
        if (parts.size != 3) return 0
        val major = parts[0].toIntOrNull() ?: return 0
        val minor = parts[1].toIntOrNull() ?: return 0
        val patch = parts[2].substringBefore("-").toIntOrNull() ?: return 0
        return major * 10000 + minor * 100 + patch
    }

    /**
     * Determines whether an update is available comparing both versionCode and versionName.
     *
     * 1. If remoteVersionCode is positive and both versions are positive:
     *    Checks if remoteVersionCode was derived from versionName (major*10000 + minor*100 + patch)
     *    while installed is a flat integer (< 10000). If so, uses semantic versionName comparison
     *    to avoid perpetual update prompts.
     *    Otherwise, uses versionCode as the primary monotonic check (e.g. build hotfix 195 > 194
     *    with identical versionName).
     * 2. Uses semantic versionName comparison as companion/fallback when versionCodes are equal
     *    or unavailable.
     */
    fun hasUpdate(
        installedVersionCode: Int,
        installedVersionName: String,
        remoteVersionCode: Int,
        remoteVersionName: String,
    ): Boolean {
        val nameCmp = compareVersions(remoteVersionName, installedVersionName)

        if (remoteVersionCode > 0 && installedVersionCode > 0) {
            val derived = parseVersionCode(remoteVersionName)
            val isRemoteDerived = derived > 0 && remoteVersionCode == derived
            val isInstalledFlat = installedVersionCode < 10000

            // If backend returned derived versionCode (scale mismatch with flat installed versionCode)
            if (isRemoteDerived && isInstalledFlat) {
                // Use semantic versionName comparison to avoid perpetual update prompts
                return nameCmp > 0
            }

            // Otherwise, versionCode is on a consistent scale: use as primary monotonic check
            if (remoteVersionCode > installedVersionCode) {
                return true
            } else if (remoteVersionCode < installedVersionCode) {
                return false
            }
            // Same versionCode: fall back to semantic versionName comparison
            return nameCmp > 0
        }

        // Companion / fallback: semantic versionName comparison
        return nameCmp > 0
    }

    fun hasUpdate(context: Context, info: UpdateInfo): Boolean {
        val installedCode = installedVersionCode(context)
        val installedName = installedVersionName(context)
        return hasUpdate(installedCode, installedName, info.version_code, info.version_name)
    }

    /**
     * Extracts semantic version string from standard APK file names:
     * e.g. "app-debug-v1.17.0.apk" -> "1.17.0"
     *      "app-release-v1.6.11.apk" -> "1.6.11"
     *      "home-datacenter-app-1.15.0.apk" -> "1.15.0"
     */
    fun extractVersionFromFileName(fileName: String): String? {
        val regex = Regex("""[-_vV](\d+\.\d+(?:\.\d+)?(?:-[a-zA-Z0-9.]+)?)\.apk$""")
        val match = regex.find(fileName)
        return match?.groupValues?.get(1)
    }

    /**
     * Clean up stale APK and .part files from the downloads directory to prevent
     * storage accumulation over repeated updates.
     *
     * 1. If [preserveTargetFile] is specified, it and its companion .part file are preserved.
     *    All other files in the directory are considered obsolete and purged.
     * 2. If [preserveTargetFile] is null:
     *    - All .part and .tmp files are purged (interrupted or orphaned downloads).
     *    - Any APK whose version is <= [installedVersionName] is purged (already installed).
     *    - Any unparseable APK is purged.
     *    - Future pending updates (version > installed) are kept.
     *
     * @param downloadsDir directory holding the update files
     * @param installedVersionName currently installed versionName (e.g. "1.17.0")
     * @param preserveTargetFile optional File that should not be touched
     * @return Total bytes freed
     */
    fun pruneStaleDownloads(
        downloadsDir: File,
        installedVersionName: String,
        preserveTargetFile: File? = null,
    ): Long {
        if (!downloadsDir.exists() || !downloadsDir.isDirectory) return 0L
        var reclaimedBytes = 0L

        val targetName = preserveTargetFile?.name
        val targetPartName = if (targetName != null) "$targetName.part" else null

        val files = downloadsDir.listFiles() ?: return 0L
        for (file in files) {
            if (!file.isFile) continue

            // 1. Preserve active target file and its partial download companion
            if (targetName != null && (file.name == targetName || file.name == targetPartName)) {
                continue
            }

            // 2. Stale or orphaned partial/temporary files
            if (file.name.endsWith(".part") || file.name.endsWith(".tmp")) {
                val shouldDeletePart = if (preserveTargetFile != null) {
                    // An explicit pending target update is specified, so any other partial download is obsolete
                    true
                } else if (installedVersionName.isNotBlank()) {
                    val baseName = if (file.name.endsWith(".part")) file.name.removeSuffix(".part") else file.name.removeSuffix(".tmp")
                    val partVer = extractVersionFromFileName(baseName)
                    if (partVer != null) {
                        // Delete if part belongs to an already installed or older version; preserve if newer for resume
                        compareVersions(partVer, installedVersionName) <= 0
                    } else {
                        // Orphaned temporary file without identifiable newer version
                        true
                    }
                } else {
                    false
                }

                if (shouldDeletePart) {
                    val len = file.length()
                    if (file.delete()) {
                        reclaimedBytes += len
                        Log.d(TAG, "Deleted stale partial download: ${file.name} ($len bytes)")
                    }
                }
                continue
            }

            // 3. Obsolete APK files
            if (file.name.endsWith(".apk")) {
                val shouldDelete = if (preserveTargetFile != null) {
                    // An explicit pending target update is specified, so all other historical APKs are stale
                    true
                } else if (installedVersionName.isNotBlank()) {
                    val fileVer = extractVersionFromFileName(file.name)
                    if (fileVer != null) {
                        compareVersions(fileVer, installedVersionName) <= 0
                    } else {
                        // Unparseable APK name with no target preservation
                        true
                    }
                } else {
                    false
                }

                if (shouldDelete) {
                    val len = file.length()
                    if (file.delete()) {
                        reclaimedBytes += len
                        Log.d(TAG, "Deleted obsolete APK: ${file.name} ($len bytes)")
                    }
                }
            }
        }

        return reclaimedBytes
    }

    /**
     * Convenience context-aware wrapper for [pruneStaleDownloads].
     */
    fun pruneStaleDownloads(context: Context, preserveTargetFile: File? = null): Long {
        val downloadsDir = File(context.filesDir, "downloads")
        val installedName = installedVersionName(context)
        return pruneStaleDownloads(downloadsDir, installedName, preserveTargetFile)
    }
}
