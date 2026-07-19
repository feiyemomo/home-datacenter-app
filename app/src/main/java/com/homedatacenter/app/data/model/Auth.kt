package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BindRequest(
    @SerialName("user_id") val userId: Long,
    @SerialName("access_key") val accessKey: String
)

@Serializable
data class BindData(
    val token: String
)
