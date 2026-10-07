package com.homedatacenter.app.data.ws

import android.util.Log
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.WsMessage
import com.homedatacenter.app.data.model.WsMessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

interface WsEventListener {
    fun onConnected()
    fun onMessage(message: WsMessage)
    fun onDisconnected(code: Int, reason: String?)
    fun onError(throwable: Throwable, reconnectAttempt: Int)
}

class HomeCenterWebSocket(
    private val client: OkHttpClient,
    private val wsUrl: String,
    private val token: String,
    private val listener: WsEventListener,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val heartbeatIntervalMs: Long = 30_000L,
    private val wsUrlProvider: (() -> String)? = null,
    private val onConnectionFailed: ((String) -> Unit)? = null,
    // v1.14.3: when this returns false, the reconnect loop pauses
    // until reconnectNow() is called on recovery.
    private val isServerReachable: (() -> Boolean)? = null,
) {

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isConnected: Boolean = false
    @Volatile private var shouldReconnect: Boolean = true
    @Volatile private var activeWsUrl: String = wsUrl

    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0

    private val activeSubscriptions: MutableSet<String> = LinkedHashSet()

    fun connect() {
        shouldReconnect = true
        if (webSocket != null) return

        val targetUrl = wsUrlProvider?.invoke()?.ifBlank { null } ?: wsUrl
        activeWsUrl = targetUrl
        Log.i(TAG, "connecting to $targetUrl (attempt $reconnectAttempt)")

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .build()

        webSocket = client.newWebSocket(request, WsListener(targetUrl))
    }

    fun disconnect() {
        shouldReconnect = false
        cancelLoops()
        webSocket?.close(NORMAL_CLOSURE, "client disconnect")
        webSocket = null
        isConnected = false
    }

    fun send(message: WsMessage): Boolean {
        val ws = webSocket ?: return false
        val text = NetworkFactory.json.encodeToString(WsMessage.serializer(), message)
        return ws.send(text)
    }

    fun subscribe(topic: String) {
        synchronized(activeSubscriptions) { activeSubscriptions.add(topic) }
        send(WsMessage(type = WsMessageType.SUBSCRIBE, topic = topic))
    }

    fun unsubscribe(topic: String) {
        synchronized(activeSubscriptions) { activeSubscriptions.remove(topic) }
        send(WsMessage(type = WsMessageType.UNSUBSCRIBE, topic = topic))
    }

    fun sendHeartbeat(): Boolean = send(WsMessage(type = WsMessageType.HEARTBEAT))

    fun isConnected(): Boolean = isConnected

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                delay(heartbeatIntervalMs)
                if (!sendHeartbeat()) {
                    Log.w(TAG, "heartbeat send failed; connection likely dead")
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun cancelLoops() {
        stopHeartbeat()
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /**
     * v1.14.3: server came back (BaseUrlResolver reachability → true).
     * Drop any pending backoff and reconnect immediately.
     */
    fun reconnectNow() {
        if (!shouldReconnect) return
        if (isConnected) return
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        pausedForOffline = false
        webSocket?.cancel()
        webSocket = null
        Log.i(TAG, "reconnectNow: server reachable again")
        connect()
    }

    @Volatile private var pausedForOffline: Boolean = false

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        if (reconnectJob?.isActive == true) return

        // v1.14.3: while every path to the NAS is known dead, stop the
        // reconnect loop entirely; reconnectNow() resumes it as soon as
        // the resolver's backoff probe finds the server again.
        if (isServerReachable?.invoke() == false) {
            if (!pausedForOffline) Log.i(TAG, "server unreachable; pausing reconnect until it recovers")
            pausedForOffline = true
            return
        }

        reconnectAttempt += 1
        val delayMs = (1L shl (reconnectAttempt - 1).coerceAtMost(5)) * 1000L
        val cappedDelay = delayMs.coerceAtMost(30_000L)

        Log.i(TAG, "scheduling reconnect #$reconnectAttempt in $cappedDelay ms")
        reconnectJob = scope.launch {
            delay(cappedDelay)
            if (shouldReconnect) {
                webSocket = null
                isConnected = false
                connect()
            }
        }
    }

    private fun handleInbound(text: String) {
        val msg: WsMessage = try {
            NetworkFactory.json.decodeFromString(WsMessage.serializer(), text)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to parse inbound frame: ${t.message}")
            return
        }
        listener.onMessage(msg)
    }

    private inner class WsListener(private val connectingUrl: String) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            Log.i(TAG, "ws connected to $connectingUrl (http=${response.code})")
            isConnected = true
            reconnectAttempt = 0
            this@HomeCenterWebSocket.webSocket = webSocket

            val subs = synchronized(activeSubscriptions) { activeSubscriptions.toList() }
            subs.forEach { send(WsMessage(type = WsMessageType.SUBSCRIBE, topic = it)) }

            startHeartbeat()
            listener.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleInbound(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closed: $code $reason")
            isConnected = false
            stopHeartbeat()
            this@HomeCenterWebSocket.webSocket = null
            listener.onDisconnected(code, reason)
            if (shouldReconnect) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            Log.w(TAG, "ws failure on $connectingUrl: ${t.message}")
            isConnected = false
            stopHeartbeat()
            this@HomeCenterWebSocket.webSocket = null
            if (reconnectAttempt >= 1) {
                onConnectionFailed?.invoke(connectingUrl)
            }
            listener.onError(t, reconnectAttempt + 1)
            if (shouldReconnect) scheduleReconnect()
        }
    }

    companion object {
        private const val TAG = "HomeCenterWS"
        private const val NORMAL_CLOSURE = 1000
    }
}
