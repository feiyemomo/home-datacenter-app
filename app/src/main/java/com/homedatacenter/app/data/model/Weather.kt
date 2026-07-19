package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * wttr.in JSON (format=j1) response model. Only fields we actually
 * display are modeled; everything else is ignored via
 * NetworkFactory.json { ignoreUnknownKeys = true }.
 */
@Serializable
data class WeatherResponse(
    @SerialName("current_condition") val currentCondition: List<WeatherCurrent> = emptyList(),
    @SerialName("nearest_area") val nearestArea: List<WeatherArea> = emptyList()
)

@Serializable
data class WeatherCurrent(
    @SerialName("temp_C") val tempC: String = "",
    @SerialName("FeelsLikeC") val feelsLikeC: String = "",
    val humidity: String = "",
    @SerialName("windspeedKmph") val windSpeedKmph: String = "",
    @SerialName("weatherCode") val weatherCode: String = "",
    @SerialName("weatherDesc") val weatherDesc: List<WeatherDesc> = emptyList(),
    @SerialName("weatherIconUrl") val weatherIconUrl: List<WeatherIcon> = emptyList()
)

@Serializable
data class WeatherDesc(val value: String = "")

@Serializable
data class WeatherIcon(val value: String = "")

@Serializable
data class WeatherArea(
    val areaName: List<WeatherAreaValue> = emptyList(),
    val region: List<WeatherAreaValue> = emptyList(),
    val country: List<WeatherAreaValue> = emptyList()
)

@Serializable
data class WeatherAreaValue(val value: String = "")
