package com.homedatacenter.app.data.model

import kotlinx.serialization.Serializable

/**
 * v1.6.11: metadata about the latest Android APK release, returned
 * by GET /api/v1/release/latest. The Android in-app updater compares
 * [versionCode] against the installed PackageInfo.versionCode to
 * decide whether to prompt the user. Download URL is RELATIVE
 * (e.g. "/api/v1/release/latest/apk") — the client prepends its
 * resolved base URL so the same endpoint works on LAN and via
 * Cloudflare Tunnel.
 */
@Serializable
data class UpdateInfo(
    /** Human-readable version, e.g. "1.6.11". Shown in the update dialog. */
    val version_name: String = "",
    /** Monotonic integer version. The Android client compares this
     *  against PackageInfo.versionCode to decide if an update exists. */
    val version_code: Int = 0,
    /** Relative path to download the APK. Prepend the base URL. */
    val download_url: String = "",
    /** Original APK file name, e.g. "app-debug-v1.6.11.apk". Used
     *  as the saved filename in the app's cache dir. */
    val file_name: String = "",
    /** APK file size in bytes. Shown in the update dialog so the
     *  user knows how much data the download will consume. */
    val size_bytes: Long = 0L,
    /** Optional human-readable release notes. Currently empty — the
     *  backend scans files from disk and doesn't read release notes
     *  from anywhere. Reserved for future use. */
    val release_notes: String = "",
)
