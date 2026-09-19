package com.homedatacenter.app.data.api

import com.homedatacenter.app.data.model.ApiResponse
import com.homedatacenter.app.data.model.BindRequest
import com.homedatacenter.app.data.model.CreateUserRequest
import com.homedatacenter.app.data.model.DeviceList
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.data.model.UpdateUserRequest
import com.homedatacenter.app.data.model.User
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface HomeCenterApi {

    @POST("api/v1/auth/bind")
    suspend fun bindDevice(@Body req: BindRequest): ApiResponse

    /** POST /api/v1/auth/refresh — re-issue a fresh 365-day JWT for the caller. */
    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(@Header("Authorization") auth: String): ApiResponse

    @GET("api/v1/user/me")
    suspend fun getMe(@Header("Authorization") auth: String): ApiResponse

    @GET("api/v1/device/list")
    suspend fun listDevices(
        @Header("Authorization") auth: String,
        @Query("scope") scope: String = "mine"
    ): ApiResponse

    @POST("api/v1/device")
    suspend fun createDevice(
        @Header("Authorization") auth: String,
        @Body request: com.homedatacenter.app.data.model.CreateDeviceRequest,
    ): ApiResponse

    @DELETE("api/v1/device/{id}")
    suspend fun revokeDevice(
        @Header("Authorization") auth: String,
        @Path("id") id: Long
    ): ApiResponse

    @DELETE("api/v1/device/{id}/hard")
    suspend fun deleteDevice(
        @Header("Authorization") auth: String,
        @Path("id") id: Long
    ): ApiResponse

    @GET("api/v1/system/status")
    suspend fun getSystemStatus(@Header("Authorization") auth: String): ApiResponse

    @GET("api/v1/system/logs")
    suspend fun listSystemLogs(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("event_type") eventType: String? = null,
    ): ApiResponse

    // v1.8.14: delete a single system log entry after manual
    // verification. Used by the "核查并删除" workflow.
    @DELETE("api/v1/system/logs/{id}")
    suspend fun deleteSystemLog(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
    ): ApiResponse

    // v1.8.21: verify (downgrade) a critical log to normal level.
    // The log stays in the audit trail but is removed from the
    // "pending" section. Replaces the old DELETE-based workflow.
    @PATCH("api/v1/system/logs/{id}")
    suspend fun verifySystemLog(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
    ): ApiResponse

    @GET("api/v1/cameras")
    suspend fun listCameras(@Header("Authorization") auth: String): ApiResponse

    @GET("api/v1/cameras/{id}")
    suspend fun getCamera(
        @Header("Authorization") auth: String,
        @Path("id") id: Long
    ): ApiResponse

    @GET("api/v1/cameras/ice")
    suspend fun getIceConfig(@Header("Authorization") auth: String): ApiResponse

    @Headers("Content-Type: application/sdp", "Accept: application/sdp")
    @POST("api/v1/cameras/{id}/webrtc")
    suspend fun sendWebrtcOffer(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
        @Body sdp: String
    ): String

    @GET("api/v1/cameras/{id}/recordings")
    suspend fun listRecordings(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
        // v1.8.48: optional unix-seconds window so the client can page
        // recordings older than the default 7-day retention (the backend
        // defaults to now-7d..now when both are omitted).
        @Query("after") after: Long? = null,
        @Query("before") before: Long? = null,
    ): ApiResponse

    @GET("api/v1/cameras/alerts")
    suspend fun listAlerts(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 50,
    ): ApiResponse

    // v1.6.0: motion ranges for the day-playback SeekBar overlay.
    // Replaces alerts-as-overlay-source — motion fires on any
    // pixel-diff activity, alerts only fire on AI detection (person/car).
    @GET("api/v1/cameras/{id}/motion-ranges")
    suspend fun listMotionRanges(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
        @Query("after") after: Long,
        @Query("before") before: Long,
    ): ApiResponse

    @GET("api/v1/network/status")
    suspend fun getNetworkStatus(
        @Header("Authorization") auth: String,
        @Query("refresh") refresh: Boolean = false,
    ): ApiResponse

    @GET("api/v1/network/p2p/server-endpoint")
    suspend fun getServerEndpoint(
        @Header("Authorization") auth: String,
    ): ApiResponse

    @POST("api/v1/cameras")
    suspend fun registerCamera(
        @Header("Authorization") auth: String,
        @Body request: com.homedatacenter.app.data.model.RegisterCameraRequest,
    ): ApiResponse

    @DELETE("api/v1/cameras/{id}")
    suspend fun deleteCamera(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
    ): ApiResponse

    @PUT("api/v1/cameras/{id}/codec")
    suspend fun updateCameraCodec(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
        @Body request: com.homedatacenter.app.data.model.UpdateCodecRequest,
    ): ApiResponse

    @POST("api/v1/cameras/{id}/ptz")
    suspend fun moveCamera(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
        @Body request: com.homedatacenter.app.data.model.PtzRequest,
    ): ApiResponse

    @PUT("api/v1/cameras/{id}/recording")
    suspend fun setRecordingPlan(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
        @Body request: com.homedatacenter.app.data.model.RecordingPlanRequest,
    ): ApiResponse

    @GET("api/v1/cameras/{id}/presets/discover")
    suspend fun listCameraPresets(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
    ): ApiResponse

    @PUT("api/v1/cameras/{id}/presets/{alias}")
    suspend fun setCameraPreset(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
        @Path("alias") alias: String,
        @Body request: com.homedatacenter.app.data.model.SetPresetRequest,
    ): ApiResponse

    @DELETE("api/v1/cameras/{id}/presets/{alias}")
    suspend fun deleteCameraPreset(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
        @Path("alias") alias: String,
    ): ApiResponse

    @POST("api/v1/cameras/{id}/preset/{alias}")
    suspend fun gotoCameraPreset(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
        @Path("alias") alias: String,
        @Body request: com.homedatacenter.app.data.model.GotoPresetRequest,
    ): ApiResponse

    @GET("api/v1/weather")
    suspend fun getWeather(@Header("Authorization") auth: String): ApiResponse

    // --- In-app self-update (v1.6.11) ---

    /** GET /api/v1/release/latest → UpdateInfo (version, size, url). */
    @GET("api/v1/release/latest")
    suspend fun getLatestRelease(
        @Header("Authorization") auth: String,
        @Query("flavor") flavor: String? = null,
    ): ApiResponse

    /**
     * GET /api/v1/release/latest/apk → APK file stream. Returns
     * ResponseBody so the caller can stream to disk without loading
     * the whole 90MB APK into memory. Caller MUST close the body.
     *
     * v1.7.22: optional [range] header enables HTTP Range requests
     * for resumable downloads on flaky networks. The server (Go
     * http.ServeFile) natively supports Range — when [range] is
     * "bytes=N-" the server returns 206 Partial Content with the
     * remaining bytes. Returns [retrofit2.Response] so the caller
     * can distinguish 200 (full download) from 206 (resume).
     */
    @GET("api/v1/release/latest/apk")
    @retrofit2.http.Streaming
    suspend fun downloadLatestApk(
        @Header("Authorization") auth: String,
        @Header("Range") range: String? = null,
        @Query("flavor") flavor: String? = null,
    ): retrofit2.Response<okhttp3.ResponseBody>

    // --- Camera audio toggle (admin) ---

    @PUT("api/v1/cameras/{id}/audio")
    suspend fun updateCameraAudio(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
        @Body request: com.homedatacenter.app.data.model.UpdateAudioRequest,
    ): ApiResponse

    // Pre-warm the camera's RTSP/go2rtc connection so the first
    // stream request (WebRTC offer / MP4 / preview frame) doesn't
    // pay the cold-start RTSP handshake cost.
    @POST("api/v1/cameras/{id}/preheat")
    suspend fun preheat(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
    ): ApiResponse

    // --- User management (admin except /me) ---

    @GET("api/v1/user")
    suspend fun listUsers(@Header("Authorization") auth: String): ApiResponse

    @POST("api/v1/user")
    suspend fun createUser(
        @Header("Authorization") auth: String,
        @Body request: CreateUserRequest,
    ): ApiResponse

    @GET("api/v1/user/{id}")
    suspend fun getUser(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
    ): ApiResponse

    @PUT("api/v1/user/{id}")
    suspend fun updateUser(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
        @Body request: UpdateUserRequest,
    ): ApiResponse

    @DELETE("api/v1/user/{id}")
    suspend fun deleteUser(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
    ): ApiResponse

    // --- Camera sharing (admin or camera owner) ---

    /** POST /api/v1/cameras/:id/shares — share the camera with another user. */
    @POST("api/v1/cameras/{id}/shares")
    suspend fun shareCamera(
        @Header("Authorization") auth: String,
        @Path("id") cameraId: Long,
        @Body body: com.homedatacenter.app.data.model.ShareCameraRequest,
    ): ApiResponse

    /** DELETE /api/v1/cameras/:id/shares/:user_id — revoke a user's access. */
    @DELETE("api/v1/cameras/{id}/shares/{userId}")
    suspend fun unshareCamera(
        @Header("Authorization") auth: String,
        @Path("id") cameraId: Long,
        @Path("userId") userId: Long,
    ): ApiResponse

    /** GET /api/v1/cameras/:id/shares — list users the camera is shared with. */
    @GET("api/v1/cameras/{id}/shares")
    suspend fun listShares(
        @Header("Authorization") auth: String,
        @Path("id") cameraId: Long,
    ): ApiResponse
}
