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
     * storage, then launch the system PackageInstaller.
     *
     * Must be called from a coroutine — the network I/O runs on
     * Dispatchers.IO. The Intent launch hops back to the main
     * thread because startActivity must be called from the UI
     * thread.
     *
     * @param activity  required for startActivity (PackageInstaller
     *                  needs an Activity context, not Application).
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
     * @return true if the install Intent was launched, false on
     *         any error (network, disk, FileProvider).
     */
    suspend fun downloadAndInstall(
        activity: Activity,
        repo: HomeCenterRepository,
        token: String,
        info: UpdateInfo,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = if (info.file_name.isNotEmpty()) info.file_name
                else "app-debug-v${info.version_name}.apk"
            val downloadsDir = File(activity.filesDir, "downloads").apply {
                if (!exists()) mkdirs()
            }
            val apkFile = File(downloadsDir, fileName)

            // Stream the APK to disk. ResponseBody.source() returns
            // a BufferedSource that reads in 8KB chunks by default;
            // we report progress per 1% delta so the caller can
            // update a progress bar without flooding the main thread.
            val body = repo.downloadLatestApk(token)
            body.use { responseBody ->
                val total = responseBody.contentLength().let { if (it > 0) it else info.size_bytes }
                var lastReportedPercent = -1
                responseBody.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024) // 64KB read buffer
                        var read: Int
                        var downloaded = 0L
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

            Log.d(TAG, "Downloaded ${apkFile.length()} bytes to ${apkFile.absolutePath}")

            // Hop to main thread for startActivity — the install
            // Intent must be launched from an Activity context on
            // the UI thread.
            withContext(Dispatchers.Main) {
                launchInstaller(activity, apkFile)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed: ${e.message}", e)
            false
        }
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
    private fun launchInstaller(context: Context, apkFile: File) {
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
