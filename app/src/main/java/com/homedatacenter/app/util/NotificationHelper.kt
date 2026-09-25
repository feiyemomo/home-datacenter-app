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

    private const val NOTIFICATION_ID_SYSTEM_BASE = 2000
    private var systemNotificationCounter = 0

    // 5-second deduplication map by camera ID or slug to avoid spamming user
    private val lastAlertTimeMap = ConcurrentHashMap<String, Long>()
    private const val ALERT_DEDUP_WINDOW_MS = 5000L

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

            manager.createNotificationChannel(securityChannel)
            manager.createNotificationChannel(systemChannel)
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun showSecurityAlertNotification(context: Context, alert: Alert) {
        val prefs = PrefsManager(context)
        if (!prefs.shouldNotifyAlert(alert.label)) {
            return
        }

        val key = alert.cameraId?.toString() ?: alert.cameraSlug.ifEmpty { alert.cameraName }
        val now = System.currentTimeMillis()
        val last = lastAlertTimeMap[key] ?: 0L
        if (now - last < ALERT_DEDUP_WINDOW_MS) {
            return
        }
        lastAlertTimeMap[key] = now

        val cameraTitle = alert.cameraName.ifBlank { alert.cameraSlug.ifBlank { "摄像头" } }
        val labelName = formatDetectionLabel(context, alert.label)
        val title = "$cameraTitle 发现 $labelName"

        val contentText = buildString {
            if (alert.confidence > 0.0) {
                append("置信度: ${(alert.confidence * 100).toInt()}%")
            }
            if (alert.zones.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append("区域: ${alert.zones.joinToString(", ")}")
            }
        }.ifBlank { "检测到活动，点击查看回放" }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TAB, R.id.nav_cameras)
            alert.cameraId?.let { putExtra(EXTRA_ALERT_CAMERA_ID, it) }
            putExtra(EXTRA_ALERT_START_TS, alert.startTime.toLong())
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            (alert.cameraId?.toInt() ?: alert.hashCode()),
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
                val baseUrl = prefs.baseUrl?.trimEnd('/')
                val token = prefs.token
                val snapshotUrl = if (alert.id.isNotEmpty() && !baseUrl.isNullOrEmpty()) {
                    "$baseUrl/api/v1/alerts/${alert.id}/snapshot"
                } else if (alert.cameraId != null && !baseUrl.isNullOrEmpty()) {
                    "$baseUrl/api/v1/cameras/${alert.cameraId}/frame?quality=40"
                } else null

                if (!snapshotUrl.isNullOrEmpty()) {
                    try {
                        val req = okhttp3.Request.Builder()
                            .url(snapshotUrl)
                            .apply {
                                if (!token.isNullOrEmpty()) {
                                    header("Authorization", "Bearer $token")
                                }
                            }
                            .build()
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                resp.body?.byteStream()?.use { stream ->
                                    bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (bitmap != null) {
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .setSummaryText(contentText)
                    )
                }
            }

            try {
                val notificationId = 1000 + (alert.cameraId?.toInt() ?: (alert.id.hashCode() % 1000))
                NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            } catch (_: SecurityException) {
                // Android 13+ permission might not be granted yet
            }
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
        } catch (_: SecurityException) {
            // Android 13+ permission might not be granted yet
        }
    }

    fun showPersonRecognizedNotification(context: Context, name: String, cameraName: String) {
        val prefs = PrefsManager(context)
        if (!prefs.notificationsEnabled || !prefs.notifyPerson || prefs.isDndActive()) return

        val title = "视觉识别通知"
        val message = "摄像头【${cameraName.ifBlank { "安防监控" }}】识别到家庭成员【$name】"
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TAB, R.id.nav_cameras)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            9998,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SECURITY_ALERTS)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(9998, notification)
        } catch (_: SecurityException) {
            // Android 13+ permission might not be granted yet
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
