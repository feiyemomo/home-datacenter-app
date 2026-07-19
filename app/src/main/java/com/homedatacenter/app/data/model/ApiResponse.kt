package com.homedatacenter.app.data.model

import com.homedatacenter.app.data.api.NetworkFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class ApiResponse(
    val code: Int = 0,
    val message: String = "",
    val data: JsonElement? = null
) {
    val isSuccess: Boolean get() = code == 0

    inline fun <reified T> decodeData(): T? {
        if (!isSuccess) throw ApiException(code, message)
        return data?.let { NetworkFactory.json.decodeFromJsonElement<T>(it) }
    }

    inline fun <reified T> decodeDataOrThrow(): T {
        return decodeData<T>() ?: throw ApiException(code, "data is null")
    }
}

class ApiException(val code: Int, message: String) : RuntimeException("API $code: $message")
