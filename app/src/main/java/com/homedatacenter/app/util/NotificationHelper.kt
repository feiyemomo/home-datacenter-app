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

    fun showSecurityAlertNotification(context: Context, alert: Alert) {
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

        val notification = NotificationCompat.Builder(context, CHANNEL_SECURITY_ALERTS)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            val notificationId = 1000 + (alert.cameraId?.toInt() ?: (alert.id.hashCode() % 1000))
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Android 13+ permission might not be granted yet
        }
    }

    fun showSystemAlertNotification(context: Context, title: String, message: String) {
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
