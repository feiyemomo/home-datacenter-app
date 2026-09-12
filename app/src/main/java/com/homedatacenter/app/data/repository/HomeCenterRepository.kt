package com.homedatacenter.app.data.repository

import com.homedatacenter.app.data.api.HomeCenterApi
import com.homedatacenter.app.data.model.ApiException
import com.homedatacenter.app.data.model.BindData
import com.homedatacenter.app.data.model.BindRequest
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.CreateUserRequest
import com.homedatacenter.app.data.model.CreateUserResponse
import com.homedatacenter.app.data.model.DeleteUserResult
import com.homedatacenter.app.data.model.Device
import com.homedatacenter.app.data.model.DeviceList
import com.homedatacenter.app.data.model.IceConfig
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.data.model.UpdateUserRequest
import com.homedatacenter.app.data.model.User
import com.homedatacenter.app.data.model.UserList
import com.homedatacenter.app.data.model.WeatherResponse
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
        scope: String = "mine",
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
                fetchDevicesFromNetwork(token, scope)
            }
        }

        return fetchDevicesFromNetwork(token, scope)
    }

    private suspend fun fetchDevicesFromNetwork(token: String, scope: String): List<Device> {
        val resp = api.listDevices(bearer(token), scope)
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

    suspend fun refreshDevicesInBackground(token: String, scope: String = "mine") {
        try {
            fetchDevicesFromNetwork(token, scope)
        } catch (_: Exception) {
        }
    }

    suspend fun revokeDevice(token: String, deviceId: Long) {
        val resp = api.revokeDevice(bearer(token), deviceId)
        ensureSuccess(resp)
        prefsManager.lastDevicesFetchTime = 0L
    }

    suspend fun deleteDevice(token: String, deviceId: Long) {
        val resp = api.deleteDevice(bearer(token), deviceId)
        ensureSuccess(resp)
        prefsManager.lastDevicesFetchTime = 0L
    }

    /**
     * Create a new auth device for the current user via POST /api/v1/device.
     * Returns the new device record and the plaintext access key, which is
     * shown ONCE — the server stores only the SHA-256 hash. Invalidates the
     * device cache so the next listDevices() call refetches.
     */
    suspend fun createDevice(
        token: String,
        name: String,
    ): com.homedatacenter.app.data.model.CreateDeviceResponse {
        val resp = api.createDevice(
            bearer(token),
            com.homedatacenter.app.data.model.CreateDeviceRequest(name),
        )
        ensureSuccess(resp)
        prefsManager.lastDevicesFetchTime = 0L
        return resp.decodeData<com.homedatacenter.app.data.model.CreateDeviceResponse>()
            ?: throw java.io.IOException("empty response")
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

    /**
     * Fetch weather for the fixed dashboard location.
     * Returns null on HTTP error or deserialization failure.
     */
    suspend fun getWeather(token: String): WeatherResponse? {
        val resp = api.getWeather(bearer(token))
        return if (resp.isSuccess) {
            resp.decodeData<WeatherResponse>()
        } else {
            null
        }
    }

    /**
     * Decode a SystemStatus from the cached JSON string in PrefsManager.
     * Used by [com.homedatacenter.app.ui.network.NetworkDetailActivity] to
     * render the system stats card from cache before the network refresh
     * completes.
     */
    fun decodeSystemStatus(raw: String): SystemStatus {
        return NetworkFactory.json.decodeFromString(SystemStatus.serializer(), raw)
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

    /**
     * Toggle live audio (PCMA -> AAC transcode). Re-pushes the go2rtc
     * stream on the server side; the client should re-fetch the camera
     * (or invalidate the camera cache) to pick up the new stream URL.
     */
    suspend fun updateCameraAudio(token: String, cameraId: Long, enabled: Boolean) {
        val resp = api.updateCameraAudio(
            bearer(token),
            cameraId,
            com.homedatacenter.app.data.model.UpdateAudioRequest(enabled),
        )
        ensureSuccess(resp)
        prefsManager.lastCamerasFetchTime = 0L
    }

    /**
     * Best-effort preheat: ask the backend to warm up the camera's
     * RTSP/go2rtc connection so the subsequent stream request
     * (WebRTC offer / MP4 / preview frame) skips the cold-start
     * handshake. Fire-and-forget — callers should NOT await this
     * before navigating; errors are swallowed on purpose.
     */
    suspend fun preheatCamera(token: String, cameraId: Long) {
        try {
            api.preheat(bearer(token), cameraId)
        } catch (_: Exception) {
            // best-effort, ignore errors
        }
    }

    // --- User management (admin) ---

    suspend fun listUsers(token: String): List<User> {
        val resp = api.listUsers(bearer(token))
        ensureSuccess(resp)
        return resp.decodeData<UserList>()?.users ?: emptyList()
    }

    suspend fun createUser(
        token: String,
        name: String,
        isAdmin: Boolean,
        password: String? = null,
    ): String? {
        val resp = api.createUser(
            bearer(token),
            CreateUserRequest(name = name, isAdmin = isAdmin, password = password),
        )
        ensureSuccess(resp)
        return resp.decodeData<CreateUserResponse>()?.accessKey
    }

    suspend fun getUser(token: String, userId: Long): User {
        val resp = api.getUser(bearer(token), userId)
        ensureSuccess(resp)
        return resp.decodeDataOrThrow<User>()
    }

    suspend fun updateUser(
        token: String,
        userId: Long,
        name: String? = null,
        isAdmin: Boolean? = null,
    ): User {
        val resp = api.updateUser(
            bearer(token),
            userId,
            UpdateUserRequest(name = name, isAdmin = isAdmin),
        )
        ensureSuccess(resp)
        return resp.decodeDataOrThrow<User>()
    }

    suspend fun deleteUser(token: String, userId: Long): Int {
        val resp = api.deleteUser(bearer(token), userId)
        ensureSuccess(resp)
        return resp.decodeData<DeleteUserResult>()?.deletedDevices ?: 0
    }

    // --- Camera sharing (admin or camera owner) ---

    /**
     * Share [cameraId] with [userId] via POST /api/v1/cameras/:id/shares.
     * The caller is responsible for authorization checks (admin or
     * camera owner); the server still enforces it.
     */
    suspend fun shareCamera(token: String, cameraId: Long, userId: Long) {
        val resp = api.shareCamera(
            bearer(token),
            cameraId,
            com.homedatacenter.app.data.model.ShareCameraRequest(userId),
        )
        ensureSuccess(resp)
    }

    /**
     * Revoke [userId]'s access to [cameraId] via
     * DELETE /api/v1/cameras/:id/shares/:user_id.
     */
    suspend fun unshareCamera(token: String, cameraId: Long, userId: Long) {
        val resp = api.unshareCamera(bearer(token), cameraId, userId)
        ensureSuccess(resp)
    }

    /**
     * List the users [cameraId] is currently shared with. Returns an
     * empty list when no shares exist (or when the backend returns no
     * data field for an empty collection).
     */
    suspend fun listShares(
        token: String,
        cameraId: Long,
    ): List<com.homedatacenter.app.data.model.CameraShare> {
        val resp = api.listShares(bearer(token), cameraId)
        ensureSuccess(resp)
        return resp.decodeData<List<com.homedatacenter.app.data.model.CameraShare>>()
            ?: emptyList()
    }

    // --- In-app self-update (v1.6.11) ---

    /**
     * Fetch metadata about the latest APK release. The caller
     * compares [com.homedatacenter.app.data.model.UpdateInfo.versionCode]
     * against the installed versionCode to decide if an update is
     * available. Network-only — no cache, since the user wants
     * up-to-the-minute accuracy when they tap "Check for updates".
     */
    suspend fun getLatestRelease(token: String): com.homedatacenter.app.data.model.UpdateInfo {
        val resp = api.getLatestRelease(bearer(token))
        ensureSuccess(resp)
        return resp.decodeDataOrThrow()
    }

    /**
     * Stream the latest APK to the caller. Returns the raw
     * ResponseBody — the caller is responsible for writing it to
     * disk (see ApkInstaller.downloadApk) and closing the body to
     * release the connection.
     *
     * v1.7.22: optional [range] header enables HTTP Range requests
     * for resumable downloads. Returns [retrofit2.Response] so the
     * caller can distinguish 200 (full) from 206 (partial/resume).
     */
    suspend fun downloadLatestApk(
        token: String,
        range: String? = null,
    ): retrofit2.Response<okhttp3.ResponseBody> {
        return api.downloadLatestApk(bearer(token), range)
    }

    private fun bearer(token: String): String = "Bearer $token"

    private fun ensureSuccess(resp: com.homedatacenter.app.data.model.ApiResponse) {
        if (!resp.isSuccess) throw ApiException(resp.code, resp.message)
    }
}
