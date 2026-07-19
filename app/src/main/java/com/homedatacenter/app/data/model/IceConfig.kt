package com.homedatacenter.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

@Serializable
data class IceConfig(
    val ice_servers: List<IceServer>,
    val webrtc_base: String
)