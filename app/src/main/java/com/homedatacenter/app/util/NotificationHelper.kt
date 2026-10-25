package com.homedatacenter.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.ui.main.MainActivity
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object NotificationHelper {

    const val CHANNEL_SECURITY_ALERTS = "security_alerts_channel"
    const val CHANNEL_SYSTEM_ALERTS = "system_alerts_channel"
    const val CHANNEL_KEEPALIVE = "keepalive_service_channel"

    const val NOTIFICATION_ID_KEEPALIVE = 1001
    private const val NOTIFICATION_ID_SYSTEM_BASE = 2000
    private var systemNotificationCounter = 0

    // Notification Group Key for Android Notification Stacking / Bundling
    const val GROUP_KEY_ALERTS = "com.homedatacenter.ALERTS_GROUP"
    const val NOTIFICATION_ID_SUMMARY = 9990

    // Per-camera rate-limiting & burst suppression
    // If the same camera triggers multiple alerts within BURST_WINDOW_MS, we coalesce them
    // and update the notification with an aggregated summary (InboxStyle) rather than firing noisy new ones.
    private const val ALERT_DEDUP_WINDOW_MS = 6000L
    private const val BURST_WINDOW_MS = 30000L

    data class CameraAlertState(
        var count: Int = 1,
        var lastNotifiedTime: Long = 0L,
        var firstBurstTime: Long = 0L,
        val recentEvents: MutableList<String> = mutableListOf()
    )

    private val cameraAlertStateMap = ConcurrentHashMap<String, CameraAlertState>()

    // Cross-service duplicate alert deduplication (e.g. AlertKeepAliveService + DashboardFragment)
    private val processedAlertIds = object : java.util.LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 120
        }
    }

    data class AlertSummaryEntry(
        val cameraName: String,
        val label: String,
        val timeMs: Long
    )
    private val recentAlertHistory = java.util.Collections.synchronizedList(mutableListOf<AlertSummaryEntry>())

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            // 1. Security alerts channel (High priority, sound, heads-up)
            val securityChannel = NotificationChannel(
                CHANNEL_SECURITY_ALERTS,
                context.getString(R.string.notification_channel_security),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_security_desc)
                enableVibration(true)
                setShowBadge(true)
            }

            // 2. System maintenance channel (Default priority)
            val systemChannel = NotificationChannel(
                CHANNEL_SYSTEM_ALERTS,
                context.getString(R.string.notification_channel_system),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_system_desc)
                enableVibration(false)
                setShowBadge(false)
            }

            // 3. Keepalive foreground service channel (Low priority, silent)
            val keepaliveChannel = NotificationChannel(
                CHANNEL_KEEPALIVE,
                "后台服务保活与实时告警",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持后台安全与告警连接"
                enableVibration(false)
                setShowBadge(false)
            }

            manager.createNotificationChannel(securityChannel)
            manager.createNotificationChannel(systemChannel)
            manager.createNotificationChannel(keepaliveChannel)
        }
    }

    fun buildKeepAliveNotification(context: Context): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_KEEPALIVE,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(context, CHANNEL_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle("家庭数据中心 告警监控中")
            .setContentText("后台安全监控与实时告警连接已建立")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun showSecurityAlertNotification(context: Context, alert: Alert) {
        val prefs = PrefsManager(context)
        if (!prefs.shouldNotifyAlert(alert.label)) {
            return
        }

        val now = System.currentTimeMillis()
        if (alert.id.isNotEmpty()) {
            synchronized(processedAlertIds) {
                val prev = processedAlertIds[alert.id]
                if (prev != null && (now - prev < 15_000L)) {
                    // Suppress duplicate alert event already handled
                    return
                }
                processedAlertIds[alert.id] = now
            }
        }

        val key = alert.cameraId?.toString() ?: alert.cameraSlug.ifEmpty { alert.cameraName }
        val cameraTitle = alert.cameraName.ifBlank { alert.cameraSlug.ifBlank { "摄像头" } }
        val labelName = formatDetectionLabel(context, alert.label)

        val state = cameraAlertStateMap.compute(key) { _, current ->
            if (current == null || (now - current.lastNotifiedTime > BURST_WINDOW_MS)) {
                CameraAlertState(
                    count = 1,
                    lastNotifiedTime = now,
                    firstBurstTime = now,
                    recentEvents = mutableListOf(labelName)
                )
            } else {
                current.count++
                if (current.recentEvents.size >= 5) {
                    current.recentEvents.removeAt(0)
                }
                if (!current.recentEvents.contains(labelName) || current.recentEvents.size < 3) {
                    current.recentEvents.add(labelName)
                }
                current
            }
        } ?: CameraAlertState()

        val isThrottled = (state.count > 1) && (now - state.lastNotifiedTime < ALERT_DEDUP_WINDOW_MS)
        if (!isThrottled) {
            state.lastNotifiedTime = now
        }

        // Add to aggregated recent history for Group Summary
        synchronized(recentAlertHistory) {
            if (recentAlertHistory.size >= 20) {
                recentAlertHistory.removeAt(0)
            }
            recentAlertHistory.add(AlertSummaryEntry(cameraTitle, labelName, now))
        }

        val title = if (state.count > 1) {
            "$cameraTitle 持续活动告警 (${state.count}次检测)"
        } else {
            "$cameraTitle 发现 $labelName"
        }

        val contentText = buildString {
            if (state.count > 1) {
                append("最近检测到: ${state.recentEvents.joinToString("、")}")
                val elapsedSec = ((now - state.firstBurstTime) / 1000).coerceAtLeast(1)
                append(" · 持续 ${elapsedSec} 秒")
            } else {
                if (alert.confidence > 0.0) {
                    append("置信度: ${(alert.confidence * 100).toInt()}%")
                }
                if (alert.zones.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append("区域: ${alert.zones.joinToString(", ")}")
                }
            }
        }.ifBlank { "检测到活动，点击查看回放" }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TAB, R.id.nav_cameras)
            alert.cameraId?.let { putExtra(EXTRA_ALERT_CAMERA_ID, it) }
            putExtra(EXTRA_ALERT_START_TS, alert.startTime.toLong())
        }

        val notificationId = 1000 + (Math.abs(key.hashCode()) % 4000)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scope.launch {
            val builder = NotificationCompat.Builder(context, CHANNEL_SECURITY_ALERTS)
                .setSmallIcon(R.drawable.ic_camera)
                .setContentTitle(title)
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setGroup(GROUP_KEY_ALERTS)
                .setOnlyAlertOnce(isThrottled)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(
                    R.drawable.ic_video,
                    "查看录像",
                    pendingIntent
                )

            // v1.13.0: Attach snapshot picture if enabled
            if (prefs.notifyIncludeSnapshot) {
                var bitmap: android.graphics.Bitmap? = null
                val container = (context.applicationContext as? com.homedatacenter.app.HomeCenterApp)?.container
                val rawBaseUrl = container?.getApiBaseUrl() ?: prefs.baseUrl
                val baseUrl = rawBaseUrl?.trimEnd('/')
                val token = prefs.token
                if (!baseUrl.isNullOrEmpty()) {
                    // Try thumbnail first (~6-20KB, fast & low memory, ideal for notifications),
                    // fallback to snapshot, then camera frame.
                    val candidateUrls = mutableListOf<String>()
                    if (alert.id.isNotEmpty()) {
                        candidateUrls.add("$baseUrl/api/v1/cameras/alerts/${alert.id}/thumbnail")
                        candidateUrls.add("$baseUrl/api/v1/cameras/alerts/${alert.id}/snapshot")
                        candidateUrls.add("$baseUrl/api/v1/alerts/${alert.id}/thumbnail")
                    }
                    if (alert.cameraId != null) {
                        candidateUrls.add("$baseUrl/api/v1/cameras/${alert.cameraId}/frame?quality=30")
                    }

                    val client = container?.okHttpClient ?: okhttp3.OkHttpClient.Builder()
                        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    for (url in candidateUrls) {
                        try {
                            val req = okhttp3.Request.Builder()
                                .url(url)
                                .apply {
                                    if (!token.isNullOrEmpty()) {
                                        header("Authorization", "Bearer $token")
                                    }
                                }
                                .build()
                            client.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val bytes = resp.body?.bytes()
                                    if (bytes != null && bytes.isNotEmpty()) {
                                        val opts = android.graphics.BitmapFactory.Options().apply {
                                            inSampleSize = 2
                                        }
                                        bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                            ?: android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    }
                                }
                            }
                            if (bitmap != null) break
                        } catch (_: Exception) {}
                    }
                }

                if (bitmap != null) {
                    builder.setLargeIcon(bitmap)
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as android.graphics.Bitmap?)
                            .setSummaryText(contentText)
                    )
                }
            }

            try {
                val nm = NotificationManagerCompat.from(context)
                nm.notify(notificationId, builder.build())

                // Post or update Group Summary so multiple cameras/alerts bundle cleanly
                val summaryIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_NAVIGATE_TAB, R.id.nav_cameras)
                }
                val summaryPendingIntent = PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID_SUMMARY,
                    summaryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val inboxStyle = NotificationCompat.InboxStyle()
                    .setBigContentTitle("家庭安防报警汇总")
                val historySnapshot = synchronized(recentAlertHistory) {
                    recentAlertHistory.toList()
                }
                historySnapshot.takeLast(6).reversed().forEach { item ->
                    val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(item.timeMs))
                    inboxStyle.addLine("${item.cameraName}: 发现 ${item.label} ($timeStr)")
                }
                inboxStyle.setSummaryText("${historySnapshot.size} 条告警")

                val summary = NotificationCompat.Builder(context, CHANNEL_SECURITY_ALERTS)
                    .setSmallIcon(R.drawable.ic_camera)
                    .setStyle(inboxStyle)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setGroup(GROUP_KEY_ALERTS)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(summaryPendingIntent)
                    .build()
                nm.notify(NOTIFICATION_ID_SUMMARY, summary)

                if (!isThrottled) {
                    wakeScreen(context, 3000L)
                }
            } catch (_: SecurityException) {
                // Android 13+ permission might not be granted yet
            }
        }
    }

    /**
     * Wakes the screen for a brief duration (3 seconds) when a critical
     * alarm/fall/intrusion occurs while the device is locked/sleeping.
     */
    fun wakeScreen(context: Context, timeoutMs: Long = 3000L) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm != null && !pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wakeLock = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                    "HomeCenter:AlertWakeLock"
                )
                wakeLock.acquire(timeoutMs)
            }
        } catch (e: Exception) {
            android.util.Log.w("NotificationHelper", "wakeScreen error: ${e.message}")
        }
    }

    fun showSystemAlertNotification(context: Context, title: String, message: String) {
        val prefs = PrefsManager(context)
        if (!prefs.notificationsEnabled || !prefs.notifySystem || prefs.isDndActive()) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TAB, R.id.nav_logs)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_SYSTEM_BASE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_SYSTEM_ALERTS)
            .setSmallIcon(R.drawable.ic_alert_circle)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            val notificationId = NOTIFICATION_ID_SYSTEM_BASE + (systemNotificationCounter++ % 10)
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Android 13+ permission might not be granted yet
        }
    }

    fun showFallAlertNotification(context: Context, cameraSlug: String, cameraName: String) {
        val prefs = PrefsManager(context)
        if (!prefs.notificationsEnabled) return

        val title = "紧急告警：检测到人员摔倒！"
        val message = "监控设备【${cameraName.ifBlank { cameraSlug.ifBlank { "室内摄像头" } }}】检测到人员异常跌倒，请立即确认！"
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TAB, R.id.nav_cameras)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            9999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SECURITY_ALERTS)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(9999, notification)
            wakeScreen(context, 3000L)
        } catch (_: SecurityException) {
            // Android 13+ permission might not be granted yet
        }
    }

    fun showPersonRecognizedNotification(
        context: Context,
        name: String,
        cameraName: String,
        isIntrusion: Boolean = false,
        persons: List<String> = emptyList(),
        cameraId: Long? = null,
        cameraSlug: String = "",
        startTs: Long = 0L,
        alertId: String = "",
        isUpdate: Boolean = false,
        serverCount: Int = 0
    ) {
        val prefs = PrefsManager(context)
        if (!prefs.notificationsEnabled || !prefs.notifyPerson || prefs.isDndActive()) return

        val now = System.currentTimeMillis()
        if (alertId.isNotEmpty()) {
            synchronized(processedAlertIds) {
                val prev = processedAlertIds[alertId]
                if (prev != null && (now - prev < 15_000L)) {
                    return
                }
                processedAlertIds[alertId] = now
            }
        }

        val camDisplay = cameraName.ifBlank { cameraSlug.ifBlank { "安防监控" } }
        val title: String
        val message: String
        val priority: Int

        when {
            isIntrusion -> {
                title = "入侵告警"
                message = "摄像头【$camDisplay】检测到人员活动（离家布防模式）"
                priority = NotificationCompat.PRIORITY_MAX
            }
            name.isNotBlank() && name != "离家布防异常入侵人员" -> {
                title = "家庭成员识别"
                message = "摄像头【$camDisplay】识别到家庭成员【$name】"
                priority = NotificationCompat.PRIORITY_HIGH
            }
            persons.isNotEmpty() -> {
                val joined = persons.joinToString("、")
                title = "人员检测通知"
                message = "摄像头【$camDisplay】检测到人员：$joined"
                priority = NotificationCompat.PRIORITY_HIGH
            }
            else -> {
                title = "人员检测通知"
                message = "摄像头【$camDisplay】发现人员活动"
                priority = NotificationCompat.PRIORITY_DEFAULT
            }
        }

        val camKey = cameraId?.toString() ?: cameraSlug.ifEmpty { cameraName }
        val notifId = 1000 + (Math.abs(camKey.hashCode()) % 4000)

        // Per-camera burst coalescing (mirrors showSecurityAlertNotification).
        // A server-side update (isUpdate) or a repeat within the min-interval is
        // folded into the existing per-camera notification and refreshed silently.
        val mergeEnabled = prefs.notifyMergeEnabled
        val mergeWindowMs = prefs.notifyMergeWindowSec * 1000L
        val minIntervalMs = prefs.notifyMinIntervalSec * 1000L

        val state = if (mergeEnabled) {
            cameraAlertStateMap.compute(camKey) { _, current ->
                if (current == null || (now - current.lastNotifiedTime > mergeWindowMs)) {
                    CameraAlertState(
                        count = 1,
                        lastNotifiedTime = now,
                        firstBurstTime = now,
                        recentEvents = mutableListOf(title)
                    )
                } else {
                    current.count++
                    current
                }
            } ?: CameraAlertState()
        } else null

        val isThrottled = mergeEnabled &&
            (isUpdate || (state != null && state.count > 1 && (now - state.lastNotifiedTime < minIntervalMs)))
        if (state != null && !isThrottled) {
            state.lastNotifiedTime = now
        }
        val effectiveCount = maxOf(serverCount, state?.count ?: 0)

        val finalTitle = if (effectiveCount > 1) "$title (${effectiveCount}次)" else title
        val finalMessage = if (effectiveCount > 1) {
            val elapsedSec = ((now - (state?.firstBurstTime ?: now)) / 1000).coerceAtLeast(1)
            "$message · 持续 ${elapsedSec} 秒"
        } else {
            message
        }

        // Add to aggregated recent history for Group Summary
        synchronized(recentAlertHistory) {
            if (recentAlertHistory.size >= 20) {
                recentAlertHistory.removeAt(0)
            }
            recentAlertHistory.add(AlertSummaryEntry(camDisplay, title, now))
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TAB, R.id.nav_cameras)
            cameraId?.let { putExtra(EXTRA_ALERT_CAMERA_ID, it) }
            if (startTs > 0L) {
                putExtra(EXTRA_ALERT_START_TS, startTs)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scope.launch {
            val builder = NotificationCompat.Builder(context, CHANNEL_SECURITY_ALERTS)
                .setSmallIcon(R.drawable.ic_camera)
                .setContentTitle(finalTitle)
                .setContentText(finalMessage)
                .setPriority(priority)
                .setGroup(GROUP_KEY_ALERTS)
                .setOnlyAlertOnce(isThrottled)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(
                    R.drawable.ic_video,
                    "查看录像",
                    pendingIntent
                )

            if (isIntrusion && !isThrottled) {
                builder.setCategory(NotificationCompat.CATEGORY_ALARM)
                builder.setVibrate(longArrayOf(0, 500, 200, 500))
            } else if (isIntrusion) {
                builder.setCategory(NotificationCompat.CATEGORY_ALARM)
            }

            // Attach snapshot picture if available
            if (prefs.notifyIncludeSnapshot) {
                var bitmap: android.graphics.Bitmap? = null
                val container = (context.applicationContext as? com.homedatacenter.app.HomeCenterApp)?.container
                val rawBaseUrl = container?.getApiBaseUrl() ?: prefs.baseUrl
                val baseUrl = rawBaseUrl?.trimEnd('/')
                val token = prefs.token
                if (!baseUrl.isNullOrEmpty()) {
                    val candidateUrls = mutableListOf<String>()
                    if (alertId.isNotEmpty()) {
                        candidateUrls.add("$baseUrl/api/v1/cameras/alerts/$alertId/thumbnail")
                        candidateUrls.add("$baseUrl/api/v1/cameras/alerts/$alertId/snapshot")
                        candidateUrls.add("$baseUrl/api/v1/alerts/$alertId/thumbnail")
                    }
                    if (cameraId != null) {
                        candidateUrls.add("$baseUrl/api/v1/cameras/$cameraId/frame?quality=30")
                    }

                    val client = container?.okHttpClient ?: okhttp3.OkHttpClient.Builder()
                        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    for (url in candidateUrls) {
                        try {
                            val req = okhttp3.Request.Builder()
                                .url(url)
                                .apply {
                                    if (!token.isNullOrEmpty()) {
                                        header("Authorization", "Bearer $token")
                                    }
                                }
                                .build()
                            client.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val bytes = resp.body?.bytes()
                                    if (bytes != null && bytes.isNotEmpty()) {
                                        val opts = android.graphics.BitmapFactory.Options().apply {
                                            inSampleSize = 2
                                        }
                                        bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                            ?: android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    }
                                }
                            }
                            if (bitmap != null) break
                        } catch (_: Exception) {}
                    }
                }

                if (bitmap != null) {
                    builder.setLargeIcon(bitmap)
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as android.graphics.Bitmap?)
                            .setSummaryText(finalMessage)
                    )
                }
            }

            try {
                val nm = NotificationManagerCompat.from(context)
                nm.notify(notifId, builder.build())

                // Post or update Group Summary
                val summaryIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_NAVIGATE_TAB, R.id.nav_cameras)
                }
                val summaryPendingIntent = PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID_SUMMARY,
                    summaryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val inboxStyle = NotificationCompat.InboxStyle()
                    .setBigContentTitle("家庭安防报警汇总")
                val historySnapshot = synchronized(recentAlertHistory) {
                    recentAlertHistory.toList()
                }
                historySnapshot.takeLast(6).reversed().forEach { item ->
                    val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(item.timeMs))
                    inboxStyle.addLine("${item.cameraName}: ${item.label} ($timeStr)")
                }
                inboxStyle.setSummaryText("${historySnapshot.size} 条告警")

                val summary = NotificationCompat.Builder(context, CHANNEL_SECURITY_ALERTS)
                    .setSmallIcon(R.drawable.ic_camera)
                    .setStyle(inboxStyle)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setGroup(GROUP_KEY_ALERTS)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(summaryPendingIntent)
                    .build()
                nm.notify(NOTIFICATION_ID_SUMMARY, summary)

                if (!isThrottled && priority >= NotificationCompat.PRIORITY_HIGH) {
                    wakeScreen(context, 3000L)
                }
            } catch (_: SecurityException) {
                // Android 13+ permission might not be granted yet
            }
        }
    }

    private fun formatDetectionLabel(context: Context, label: String): String {
        val res = when (label.lowercase()) {
            "person" -> R.string.detection_label_person
            "car" -> R.string.detection_label_car
            "truck" -> R.string.detection_label_truck
            "bus" -> R.string.detection_label_bus
            "bicycle" -> R.string.detection_label_bicycle
            "motorcycle" -> R.string.detection_label_motorcycle
            "dog" -> R.string.detection_label_dog
            "cat" -> R.string.detection_label_cat
            "bird" -> R.string.detection_label_bird
            else -> 0
        }
        return if (res != 0) context.getString(res) else label
    }

    const val EXTRA_NAVIGATE_TAB = "extra_navigate_tab"
    const val EXTRA_ALERT_CAMERA_ID = "extra_alert_camera_id"
    const val EXTRA_ALERT_START_TS = "extra_alert_start_ts"
}
