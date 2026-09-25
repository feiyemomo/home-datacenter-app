package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VisionStatus(
    val online: Boolean = false,
    @SerialName("face_engine_ready") val faceEngineReady: Boolean = false,
    @SerialName("pose_engine_ready") val poseEngineReady: Boolean = false,
    @SerialName("registered_persons") val registeredPersons: Int = 0,
    @SerialName("cpu_usage_percent") val cpuUsagePercent: Double = 0.0,
    @SerialName("cpu_gate") val cpuGate: String = "normal",
    val error: String? = null
)

@Serializable
data class VisionPerson(
    val name: String
)

@Serializable
data class RegisterPersonRequest(
    val name: String,
    val image: String
)
