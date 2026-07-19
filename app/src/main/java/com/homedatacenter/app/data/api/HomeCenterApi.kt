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
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface HomeCenterApi {

    @POST("api/v1/auth/bind")
    suspend fun bindDevice(@Body req: BindRequest): ApiResponse

    @GET("api/v1/user/me")
    suspend fun getMe(@Header("Authorization") auth: String): ApiResponse

    @GET("api/v1/device/list")
    suspend fun listDevices(@Header("Authorization") auth: String): ApiResponse

    @DELETE("api/v1/device/{id}")
    suspend fun revokeDevice(
        @Header("Authorization") auth: String,
        @Path("id") id: Long
    ): ApiResponse

    @GET("api/v1/system/status")
    suspend fun getSystemStatus(@Header("Authorization") auth: String): ApiResponse

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
        @Path("id") id: Long
    ): ApiResponse

    @GET("api/v1/cameras/alerts")
    suspend fun listAlerts(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 50,
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
    @retrofit2.http.Streaming
    suspend fun getWeather(@Header("Authorization") auth: String): okhttp3.ResponseBody

    // --- Camera audio toggle (admin) ---

    @PUT("api/v1/cameras/{id}/audio")
    suspend fun updateCameraAudio(
        @Header("Authorization") auth: String,
        @Path("id") id: Long,
        @Body request: com.homedatacenter.app.data.model.UpdateAudioRequest,
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
}
