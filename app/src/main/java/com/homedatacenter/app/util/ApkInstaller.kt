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

            val response = repo.downloadLatestApk(token, rangeHeader)
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
            // the server-reported size. If size_bytes is unknown
            // (<=0), skip the check and trust the stream completion.
            if (totalSize > 0 && partFile.length() != totalSize) {
                Log.e(TAG, "Download size mismatch: got ${partFile.length()}, expected $totalSize")
                // Keep the .part file so the next attempt can resume.
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
        // size_bytes <= 0 means the server didn't report a size —
        // can't verify integrity, so treat as not cached.
        return info.size_bytes > 0 && apkFile.exists() &&
            apkFile.length() == info.size_bytes
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
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va - vb
        }
        return 0
    }
}
