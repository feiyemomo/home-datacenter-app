package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class WsMessage(
    val type: String,
    val topic: String? = null,
    val payload: JsonObject? = null,
    val ts: Long = 0
)

object WsMessageType {
    const val HEARTBEAT = "heartbeat"
    const val EVENT = "event"
    const val SUBSCRIBE = "subscribe"
    const val UNSUBSCRIBE = "unsubscribe"
    const val BROADCAST = "broadcast"
    const val ONLINE_LIST = "online_list"
    const val ERROR = "error"
}
