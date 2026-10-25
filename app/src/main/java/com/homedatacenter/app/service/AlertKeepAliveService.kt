package com.homedatacenter.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemLogLevel
import com.homedatacenter.app.data.model.WsMessage
import com.homedatacenter.app.data.ws.HomeCenterWebSocket
import com.homedatacenter.app.data.ws.WsEventListener
import com.homedatacenter.app.util.NotificationHelper
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground service to maintain WebSocket connection and handle real-time notifications
 * when the app is in background or device enters idle state.
 */
class AlertKeepAliveService : Service() {

    private var webSocket: HomeCenterWebSocket? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        val notification = NotificationHelper.buildKeepAliveNotification(this)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NotificationHelper.NOTIFICATION_ID_KEEPALIVE, notification, type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
        }
        ensureWebSocketConnected()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureWebSocketConnected()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // v1.15.2: auto-restart foreground service via AlarmManager when task is cleared
        val app = application as? HomeCenterApp
        if (app?.container?.prefsManager?.keepAliveEnabled == true) {
            val restartIntent = Intent(applicationContext, AlertKeepAliveService::class.java)
            val pendingIntent = android.app.PendingIntent.getService(
                applicationContext,
                1,
                restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(android.content.Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmManager?.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 1000L,
                pendingIntent
            )
        }
    }

    override fun onDestroy() {
        if (reachabilityRegistered) {
            try {
                (application as? HomeCenterApp)?.container?.baseUrlResolver
                    ?.removeReachabilityListener(reachabilityListener)
            } catch (_: Exception) {
            }
            reachabilityRegistered = false
        }
        webSocket?.disconnect()
        webSocket = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun ensureWebSocketConnected() {
        val app = application as? HomeCenterApp ?: return
        val container = app.container
        val token = container.prefsManager.token ?: return
        val wsUrl = container.getWsUrl()
        if (wsUrl.isBlank()) return

        if (webSocket?.isConnected() == true) return

        webSocket?.disconnect()
        webSocket = HomeCenterWebSocket(
            client = container.okHttpClient,
            wsUrl = wsUrl,
            token = token,
            listener = object : WsEventListener {
                override fun onConnected() {
                    Log.i(TAG, "Keepalive WS connected, subscribing to topics")
                    webSocket?.subscribe("device")
                    webSocket?.subscribe("camera")
                    webSocket?.subscribe("camera.motion")
                    webSocket?.subscribe("camera.fall_detected")
                    webSocket?.subscribe("camera.person_recognized")
                    webSocket?.subscribe("system")
                    webSocket?.subscribe("system.alert")
                    webSocket?.subscribe("system.log")
                }

                override fun onMessage(message: WsMessage) {
                    handleGlobalNotification(message)
                    // Dispatch to registered UI listeners on the Main Thread
                    mainHandler.post {
                        for (listener in uiListeners) {
                            try {
                                listener(message)
                            } catch (e: Exception) {
                                Log.w(TAG, "UI listener error: ${e.message}")
                            }
                        }
                    }
                }

                override fun onDisconnected(code: Int, reason: String?) {
                    Log.i(TAG, "Keepalive WS disconnected: code=$code reason=$reason")
                }

                override fun onError(throwable: Throwable, reconnectAttempt: Int) {
                    Log.w(TAG, "Keepalive WS error, reconnect attempt #$reconnectAttempt: ${throwable.message}")
                }
            },
            wsUrlProvider = { container.getWsUrl() },
            onConnectionFailed = { failedWsUrl ->
                // Do NOT call notifyUrlFailed on transient WebSocket drops!
                // WebSocket is an event stream and drops regularly on mobile data due to carrier TCP idle timeout.
                // Base URL failover is managed by RetryInterceptor when actual REST APIs fail.
                Log.w(TAG, "Keepalive WS connection dropped on $failedWsUrl; will retry WS")
            },
            isServerReachable = { container.baseUrlResolver.serverReachable }
        )
        if (!reachabilityRegistered) {
            container.baseUrlResolver.addReachabilityListener(reachabilityListener)
            reachabilityRegistered = true
        }
        webSocket?.connect()
    }

    // v1.14.3: resume the WS immediately when the NAS becomes reachable again.
    @Volatile private var reachabilityRegistered = false
    private val reachabilityListener: (Boolean) -> Unit = { reachable ->
        if (reachable) {
            mainHandler.post {
                try { webSocket?.reconnectNow() } catch (e: Exception) {
                    Log.w(TAG, "reconnectNow failed: ${e.message}")
                }
            }
        }
    }

    private fun handleGlobalNotification(message: WsMessage) {
        val app = application as? HomeCenterApp ?: return
        val container = app.container
        try {
            when (message.topic) {
                "camera.motion" -> {
                    val payload = message.payload ?: return
                    val isMuted = payload["muted"]?.jsonPrimitive?.booleanOrNull == true
                    if (!isMuted) {
                        val alert = NetworkFactory.json.decodeFromJsonElement(
                            Alert.serializer(),
                            payload
                        )
                        NotificationHelper.showSecurityAlertNotification(applicationContext, alert)
                    }
                }
                "camera.fall_detected" -> {
                    val payload = message.payload ?: return
                    val isMuted = payload["muted"]?.jsonPrimitive?.booleanOrNull == true
                    if (isMuted) return
                    val camSlug = payload["camera_slug"]?.jsonPrimitive?.contentOrNull ?: ""
                    val camName = payload["camera_name"]?.jsonPrimitive?.contentOrNull ?: camSlug
                    NotificationHelper.showFallAlertNotification(applicationContext, camSlug, camName)
                }
                "camera.person_recognized" -> {
                    val payload = message.payload ?: return
                    val isMuted = payload["muted"]?.jsonPrimitive?.booleanOrNull == true
                    if (isMuted) return
                    val isIntrusion = payload["is_intrusion"]?.jsonPrimitive?.booleanOrNull == true
                    val camName = payload["camera_name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val camSlug = payload["camera_slug"]?.jsonPrimitive?.contentOrNull ?: ""
                    val camId = payload["camera_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    val startTs = payload["ts"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: payload["start_time"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong()
                        ?: System.currentTimeMillis() / 1000L
                    val alertId = payload["id"]?.jsonPrimitive?.contentOrNull
                        ?: payload["event_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val personsArray = payload["persons"]?.let { el ->
                        try {
                            (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                                it.jsonPrimitive.contentOrNull
                            }
                        } catch (_: Exception) { null }
                    } ?: emptyList()
                    val name = payload["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val isUpdate = payload["is_update"]?.jsonPrimitive?.booleanOrNull == true
                    val serverCount = payload["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    NotificationHelper.showPersonRecognizedNotification(
                        applicationContext, name, camName, isIntrusion, personsArray,
                        cameraId = camId,
                        cameraSlug = camSlug,
                        startTs = startTs,
                        alertId = alertId,
                        isUpdate = isUpdate,
                        serverCount = serverCount
                    )
                }
                "system.alert" -> {
                    val payload = message.payload ?: return
                    val title = payload["title"]?.jsonPrimitive?.contentOrNull ?: "系统告警"
                    val msg = payload["message"]?.jsonPrimitive?.contentOrNull
                        ?: payload["msg"]?.jsonPrimitive?.contentOrNull
                        ?: "收到系统告警通知"
                    NotificationHelper.showSystemAlertNotification(applicationContext, title, msg)
                }
                "system.log" -> {
                    if (container.prefsManager.isAdmin) {
                        val payload = message.payload ?: return
                        val log = NetworkFactory.json.decodeFromJsonElement(
                            SystemLog.serializer(),
                            payload
                        )
                        if (log.level == SystemLogLevel.CRITICAL || log.level == SystemLogLevel.WARNING) {
                            val title = when {
                                log.event_type.startsWith("system.backup") -> "异地备份告警"
                                log.event_type.startsWith("system.recordings") -> "存储配额告警"
                                log.event_type.startsWith("system.disk") -> "磁盘空间告警"
                                log.event_type.startsWith("camera.offline") -> "摄像头离线告警"
                                log.event_type.startsWith("camera.rtsp") -> "摄像头视频流断开"
                                log.level == SystemLogLevel.CRITICAL -> "系统紧急告警"
                                else -> "系统运行告警"
                            }
                            NotificationHelper.showSystemAlertNotification(
                                applicationContext,
                                title,
                                log.message.ifBlank { "系统发生异常事件" }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle global notification", e)
        }
    }

    companion object {
        private const val TAG = "AlertKeepAliveService"
        const val ACTION_START = "com.homedatacenter.app.service.START"
        const val ACTION_STOP = "com.homedatacenter.app.service.STOP"

        private val mainHandler = Handler(Looper.getMainLooper())
        private val uiListeners = CopyOnWriteArrayList<(WsMessage) -> Unit>()

        fun registerUiListener(listener: (WsMessage) -> Unit) {
            if (!uiListeners.contains(listener)) {
                uiListeners.add(listener)
            }
        }

        fun unregisterUiListener(listener: (WsMessage) -> Unit) {
            uiListeners.remove(listener)
        }

        fun start(context: Context) {
            val intent = Intent(context, AlertKeepAliveService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start AlertKeepAliveService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AlertKeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop AlertKeepAliveService: ${e.message}")
            }
        }
    }
}
