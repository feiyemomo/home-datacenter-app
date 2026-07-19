package com.homedatacenter.app.data.repository

import com.homedatacenter.app.data.api.HomeCenterApi
import com.homedatacenter.app.data.model.ApiException
import com.homedatacenter.app.data.model.BindData
import com.homedatacenter.app.data.model.BindRequest
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.Device
import com.homedatacenter.app.data.model.DeviceList
import com.homedatacenter.app.data.model.IceConfig
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.data.model.User
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.util.PrefsManager

class HomeCenterRepository(
    private val api: HomeCenterApi,
    private val prefsManager: PrefsManager
) {

    suspend fun bind(userId: Long, accessKey: String): String {
        val resp = api.bindDevice(BindRequest(userId, accessKey))
        ensureSuccess(resp)
        return resp.decodeData<BindData>()!!.token
    }

    suspend fun getMe(token: String): User {
        val resp = api.getMe(bearer(token))
        ensureSuccess(resp)
        return resp.decodeDataOrThrow()
    }

    suspend fun listDevices(
        token: String,
        useCache: Boolean = true,
        refreshCache: Boolean = false
    ): List<Device> {
        val now = System.currentTimeMillis()
        val cached = prefsManager.cachedDevices
        val lastFetch = prefsManager.lastDevicesFetchTime
        val cacheValid = cached != null &&
                (now - lastFetch) < PrefsManager.CACHE_DURATION_MEDIUM

        if (!refreshCache && useCache && cacheValid && cached != null) {
            return try {
                NetworkFactory.json.decodeFromString<DeviceList>(cached).devices
            } catch (_: Exception) {
                fetchDevicesFromNetwork(token)
            }
        }

        return fetchDevicesFromNetwork(token)
    }

    private suspend fun fetchDevicesFromNetwork(token: String): List<Device> {
        val resp = api.listDevices(bearer(token))
        ensureSuccess(resp)
        val devices = resp.decodeData<DeviceList>()?.devices ?: emptyList()
        try {
            prefsManager.cachedDevices = NetworkFactory.json.encodeToString(
                DeviceList.serializer(),
                DeviceList(devices)
            )
            prefsManager.lastDevicesFetchTime = System.currentTimeMillis()
        } catch (_: Exception) {
        }
        return devices
    }

    suspend fun refreshDevicesInBackground(token: String) {
        try {
            fetchDevicesFromNetwork(token)
        } catch (_: Exception) {
        }
    }

    suspend fun revokeDevice(token: String, deviceId: Long) {
        val resp = api.revokeDevice(bearer(token), deviceId)
        ensureSuccess(resp)
        prefsManager.lastDevicesFetchTime = 0L
    }

    suspend fun getSystemStatus(
        token: String,
        useCache: Boolean = true,
        refreshCache: Boolean = false
    ): SystemStatus {
        val now = System.currentTimeMillis()
        val cached = prefsManager.cachedSystemStatus
        val lastFetch = prefsManager.lastSystemStatusFetchTime
        val cacheValid = cached != null &&
                (now - lastFetch) < PrefsManager.CACHE_DURATION_SHORT

        if (!refreshCache && useCache && cacheValid && cached != null) {
            return try {
                NetworkFactory.json.decodeFromString(SystemStatus.serializer(), cached)
            } catch (_: Exception) {
                fetchSystemStatusFromNetwork(token)
            }
        }

        return fetchSystemStatusFromNetwork(token)
    }

    private suspend fun fetchSystemStatusFromNetwork(token: String): SystemStatus {
        val resp = api.getSystemStatus(bearer(token))
        ensureSuccess(resp)
        val status = resp.decodeDataOrThrow<SystemStatus>()
        try {
            prefsManager.cachedSystemStatus = NetworkFactory.json.encodeToString(
                SystemStatus.serializer(),
                status
            )
            prefsManager.lastSystemStatusFetchTime = System.currentTimeMillis()
        } catch (_: Exception) {
        }
        return status
    }

    suspend fun refreshSystemStatusInBackground(token: String) {
        try {
            fetchSystemStatusFromNetwork(token)
        } catch (_: Exception) {
        }
    }

    suspend fun listCameras(
        token: String,
        useCache: Boolean = true,
        refreshCache: Boolean = false
    ): List<Camera> {
        val now = System.currentTimeMillis()
        val cached = prefsManager.cachedCameras
        val lastFetch = prefsManager.lastCamerasFetchTime
        val cacheValid = cached != null &&
                (now - lastFetch) < PrefsManager.CACHE_DURATION_MEDIUM

        if (!refreshCache && useCache && cacheValid && cached != null) {
            return try {
                NetworkFactory.json.decodeFromString<List<Camera>>(cached)
            } catch (_: Exception) {
                fetchCamerasFromNetwork(token)
            }
        }

        return fetchCamerasFromNetwork(token)
    }

    private suspend fun fetchCamerasFromNetwork(token: String): List<Camera> {
        val resp = api.listCameras(bearer(token))
        ensureSuccess(resp)
        val cameras = resp.decodeData<List<Camera>>() ?: emptyList()
        try {
            prefsManager.cachedCameras = NetworkFactory.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(Camera.serializer()),
                cameras
            )
            prefsManager.lastCamerasFetchTime = System.currentTimeMillis()
        } catch (_: Exception) {
        }
        return cameras
    }

    suspend fun refreshCamerasInBackground(token: String) {
        try {
            fetchCamerasFromNetwork(token)
        } catch (_: Exception) {
        }
    }

    suspend fun getCamera(token: String, cameraId: Long): Camera {
        val resp = api.getCamera(bearer(token), cameraId)
        ensureSuccess(resp)
        return resp.decodeDataOrThrow()
    }

    suspend fun getIceConfig(token: String): IceConfig {
        val resp = api.getIceConfig(bearer(token))
        ensureSuccess(resp)
        return resp.decodeDataOrThrow()
    }

    suspend fun sendWebrtcOffer(token: String, cameraId: Long, sdp: String): String {
        return api.sendWebrtcOffer(bearer(token), cameraId, sdp)
    }

    suspend fun getNetworkStatus(token: String, refresh: Boolean = false): com.homedatacenter.app.data.model.NetworkStatus {
        val resp = api.getNetworkStatus(bearer(token), refresh)
        ensureSuccess(resp)
        val status = resp.decodeDataOrThrow<com.homedatacenter.app.data.model.NetworkStatus>()
        try {
            prefsManager.cachedNetworkStatus = NetworkFactory.json.encodeToString(
                com.homedatacenter.app.data.model.NetworkStatus.serializer(),
                status
            )
        } catch (_: Exception) {
        }
        return status
    }

    /** Read the cached network status (if any) without hitting the network. */
    fun getCachedNetworkStatus(): com.homedatacenter.app.data.model.NetworkStatus? {
        val cached = prefsManager.cachedNetworkStatus ?: return null
        return try {
            NetworkFactory.json.decodeFromString(
                com.homedatacenter.app.data.model.NetworkStatus.serializer(),
                cached
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getServerEndpoint(token: String): com.homedatacenter.app.data.model.ServerEndpoint {
        val resp = api.getServerEndpoint(bearer(token))
        ensureSuccess(resp)
        return resp.decodeDataOrThrow()
    }

    suspend fun registerCamera(
        token: String,
        request: com.homedatacenter.app.data.model.RegisterCameraRequest,
    ): Camera {
        val resp = api.registerCamera(bearer(token), request)
        ensureSuccess(resp)
        prefsManager.lastCamerasFetchTime = 0L
        return resp.decodeDataOrThrow()
    }

    suspend fun deleteCamera(token: String, cameraId: Long) {
        val resp = api.deleteCamera(bearer(token), cameraId)
        ensureSuccess(resp)
        prefsManager.lastCamerasFetchTime = 0L
    }

    suspend fun updateCameraCodec(token: String, cameraId: Long, codec: String = "h264") {
        val resp = api.updateCameraCodec(
            bearer(token),
            cameraId,
            com.homedatacenter.app.data.model.UpdateCodecRequest(codec),
        )
        ensureSuccess(resp)
        prefsManager.lastCamerasFetchTime = 0L
    }

    suspend fun moveCamera(
        token: String,
        cameraId: Long,
        command: String,
        speed: Double = 0.5,
        profileToken: String? = null,
    ) {
        val resp = api.moveCamera(
            bearer(token),
            cameraId,
            com.homedatacenter.app.data.model.PtzRequest(command, speed, profileToken),
        )
        ensureSuccess(resp)
    }

    suspend fun setRecordingPlan(
        token: String,
        cameraId: Long,
        enabled: Boolean,
        retentionDays: Int? = null,
    ) {
        val resp = api.setRecordingPlan(
            bearer(token),
            cameraId,
            com.homedatacenter.app.data.model.RecordingPlanRequest(
                enabled = enabled,
                retentionDays = retentionDays,
            ),
        )
        ensureSuccess(resp)
        prefsManager.lastCamerasFetchTime = 0L
    }

    suspend fun listCameraPresets(
        token: String,
        cameraId: Long,
    ): List<com.homedatacenter.app.data.model.CameraPreset> {
        val resp = api.listCameraPresets(bearer(token), cameraId)
        ensureSuccess(resp)
        return resp.decodeData<List<com.homedatacenter.app.data.model.CameraPreset>>() ?: emptyList()
    }

    suspend fun setCameraPreset(token: String, cameraId: Long, alias: String, presetToken: String) {
        val resp = api.setCameraPreset(
            bearer(token),
            cameraId,
            alias,
            com.homedatacenter.app.data.model.SetPresetRequest(presetToken),
        )
        ensureSuccess(resp)
    }

    suspend fun deleteCameraPreset(token: String, cameraId: Long, alias: String) {
        val resp = api.deleteCameraPreset(bearer(token), cameraId, alias)
        ensureSuccess(resp)
    }

    suspend fun gotoCameraPreset(
        token: String,
        cameraId: Long,
        alias: String,
        speed: Double = 0.5,
    ) {
        val resp = api.gotoCameraPreset(
            bearer(token),
            cameraId,
            alias,
            com.homedatacenter.app.data.model.GotoPresetRequest(speed),
        )
        ensureSuccess(resp)
    }

    private fun bearer(token: String): String = "Bearer $token"

    private fun ensureSuccess(resp: com.homedatacenter.app.data.model.ApiResponse) {
        if (!resp.isSuccess) throw ApiException(resp.code, resp.message)
    }
}
