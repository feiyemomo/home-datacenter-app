package com.homedatacenter.app.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Native WebRTC client for sub-second live camera streams.
 *
 * v1.5.3 replaces the previous ExoPlayer-only live path with a
 * WebRTC-primary path:
 *
 *   1. App calls [startStream] with the camera id and a configured
 *      SurfaceViewRenderer.
 *   2. WebRtcClient creates a PeerConnection with recvonly audio +
 *      video transceivers (the app never sends media, only receives).
 *   3. SDP offer is created locally, then POSTed to the backend's
 *      WHEP-style endpoint `POST /api/v1/cameras/{id}/webrtc` with
 *      Content-Type: application/sdp (raw SDP body, not JSON).
 *   4. Backend responds with a raw SDP answer (Content-Type:
 *      application/sdp) — set as the remote description on the
 *      PeerConnection.
 *   5. ICE completes (the backend embeds all its candidates in the
 *      answer via non-trickle ICE), the connection goes to
 *      `IceConnectionState.CONNECTED`, and the backend's RTP stream
 *      is delivered as a [VideoTrack] + AudioTrack.
 *   6. The VideoTrack is bound to the SurfaceViewRenderer for
 *      display; the AudioTrack plays through the system media
 *      stream automatically.
 *
 * ICE servers come from `GET /api/v1/cameras/ice` (the existing
 * endpoint that returns the home STUN/TURN config). When the user is
 * on the home LAN, the backend typically returns an empty ICE list
 * (host-only candidates are sufficient).
 *
 * Fallback: if WebRTC fails (backend 404, ICE timeout, signaling
 * error), the caller falls back to the existing MP4 + HLS path in
 * CameraDetailActivity. This is the "WebRTC primary, MP4/HLS backup"
 * strategy the user requested.
 *
 * Threading:
 *  - PeerConnectionFactory.initialize must run on a thread with a
 *    Looper. We use the main thread (where init() is called from
 *    Activity.onCreate).
 *  - SDP offer/answer creation is async via PeerConnection's
 *    SdpObserver callbacks. We bridge these to coroutines for a
 *    cleaner API.
 *  - SurfaceViewRenderer.init must run on the UI thread (EGL
 *    context creation requires the main thread on most GPUs).
 *
 * Lifecycle: the client owns the PeerConnectionFactory (lives for the
 * activity's lifetime) and the PeerConnection (one per stream). Call
 * [release] in onPause to release the PeerConnection + MediaCodec,
 * and [shutdown] in onDestroy to release the PeerConnectionFactory
 * and EGL context.
 */
class WebRtcClient(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
    private val token: String?,
) {
    /** Owns the EGL context used for video decoding + rendering. */
    val eglBase: EglBase = EglBase.create()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var signalingJob: Job? = null

    interface Listener {
        /** WebRTC connection established; video is rendering. */
        fun onConnected()
        /** Signaling or ICE failed; caller should fall back to MP4. */
        fun onError(reason: String)
        /** ICE state changed — useful for debugging connectivity issues. */
        fun onIceStateChanged(state: PeerConnection.IceConnectionState) {}
    }

    /**
     * Must be called once before [startStream]. Initializes the
     * PeerConnectionFactory on the main thread (required for
     * video decoder EGL context creation).
     */
    fun init() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val audioDevice = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        // Hardware-accelerated encoder/decoder where available.
        // The second boolean (enableH264HighProfile) is true so we
        // can decode H264 streams from Hikvision cameras without
        // falling back to software decode.
        val videoEncoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val videoDecoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDevice)
            .setVideoEncoderFactory(videoEncoder)
            .setVideoDecoderFactory(videoDecoder)
            .createPeerConnectionFactory()
    }

    /**
     * Starts a WebRTC stream for the given camera.
     *
     * @param cameraId Backend camera id (used in the signaling URL).
     * @param surfaceRenderer Already-init'd SurfaceViewRenderer
     *     (call SurfaceViewRenderer.init(eglBase, ...) before
     *     passing it in).
     * @param iceServers ICE server config from /api/v1/cameras/ice.
     *     Empty list is OK — host candidates will be used.
     * @param listener Callbacks for connect/error/state events.
     */
    fun startStream(
        cameraId: Long,
        surfaceRenderer: SurfaceViewRenderer,
        iceServers: List<PeerConnection.IceServer>,
        listener: Listener,
    ) {
        signalingJob?.cancel()
        signalingJob = scope.launch {
            try {
                startStreamInternal(cameraId, surfaceRenderer, iceServers, listener)
            } catch (e: Exception) {
                Log.e(TAG, "WebRTC signaling failed: ${e.message}", e)
                listener.onError(e.message ?: "unknown")
            }
        }
    }

    private suspend fun startStreamInternal(
        cameraId: Long,
        surfaceRenderer: SurfaceViewRenderer,
        iceServers: List<PeerConnection.IceServer>,
        listener: Listener,
    ) {
        val pcFactory = factory ?: run {
            listener.onError("factory not initialized")
            return
        }
        // Tear down any previous PeerConnection so we can start fresh
        // on a reload. removeSink detaches the previous
        // SurfaceViewRenderer sink before the track is disposed.
        videoTrack?.removeSink(surfaceRenderer)
        videoTrack = null
        peerConnection?.let { it.dispose() }
        peerConnection = null

        val pcObserver = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                // Non-trickle: we wait for gathering-complete before
                // sending the offer. ignore individual candidates.
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE state: $state")
                state?.let { listener.onIceStateChanged(it) }
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        listener.onConnected()
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        listener.onError("ICE failed")
                    }
                    else -> {}
                }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track() ?: return
                if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    val vt = track as VideoTrack
                    videoTrack = vt
                    // addSink must run on the main thread (EGL
                    // renderer's onFrame is posted there).
                    scope.launch { vt.addSink(surfaceRenderer) }
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering: $state")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver?,
                mediaStreams: Array<out org.webrtc.MediaStream>?,
            ) {}
        }

        val pcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            // Backend doesn't support trickle ICE — it expects the
            // full SDP offer with all candidates gathered before
            // POSTing. GATHER_ONCE waits for all candidates before
            // firing onIceGatheringChange(COMPLETE).
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            // Candidate selection: prefer host candidates on LAN
            // (faster than STUN/TURN relay for local streaming).
            // Keep the default policy = ALL so TURN works when
            // the user is off-LAN.
        }

        val pc = pcFactory.createPeerConnection(pcConfig, pcObserver) ?: run {
            listener.onError("createPeerConnection returned null")
            return
        }
        peerConnection = pc

        // Add recvonly transceivers for audio + video. The order
        // matters: backend expects audio first, then video (matches
        // the SDP m-line order go2rtc generates).
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            )
        )
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            )
        )

        // Create SDP offer. OfferToReceiveAudio/Video are implied
        // by the recvonly transceivers in UnifiedPlan, but we set
        // them explicitly for compatibility with older backends.
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        val offer = withContext(Dispatchers.IO) {
            createOfferSuspend(pc, constraints)
        } ?: run {
            listener.onError("createOffer returned null")
            return
        }

        // Set local description and wait for ICE gathering to complete.
        withContext(Dispatchers.IO) {
            setLocalDescriptionSuspend(pc, offer)
        }

        // Wait for ICE gathering — non-trickle ICE requires all
        // candidates to be in the local SDP before sending.
        // Typical: ~200-500ms on LAN, up to 5s with STUN/TURN.
        val gatheringComplete = withContext(Dispatchers.IO) {
            waitForIceGathering(pc, timeoutMs = 5_000)
        }
        if (!gatheringComplete) {
            Log.w(TAG, "ICE gathering timed out; sending partial offer")
        }

        val localSdp = pc.localDescription ?: run {
            listener.onError("localDescription is null")
            return
        }

        // POST the offer to the backend's WHEP-style endpoint.
        // The body is the raw SDP string (Content-Type: application/sdp).
        val answerSdp = withContext(Dispatchers.IO) {
            postOffer(cameraId, localSdp.description)
        } ?: run {
            listener.onError("backend returned empty SDP answer")
            return
        }

        // Set remote description. This triggers ICE connectivity
        // checks — once they succeed, onIceConnectionStateChange
        // fires with CONNECTED and the listener is notified.
        val remote = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        withContext(Dispatchers.IO) {
            setRemoteDescriptionSuspend(pc, remote)
        }
        // From here, async events drive the Listener callbacks.
    }

    private suspend fun createOfferSuspend(
        pc: PeerConnection,
        constraints: MediaConstraints,
    ): SessionDescription? = suspendCancellableCoroutine { cont ->
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (cont.isActive) cont.resume(sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createOffer failed: $error")
                if (cont.isActive) cont.resume(null)
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private suspend fun setLocalDescriptionSuspend(
        pc: PeerConnection,
        sdp: SessionDescription,
    ) = suspendCancellableCoroutine<Unit> { cont ->
        pc.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setLocalDescription failed: $error")
                if (cont.isActive) cont.resumeWithException(RuntimeException(error ?: "unknown"))
            }
        }, sdp)
    }

    private suspend fun setRemoteDescriptionSuspend(
        pc: PeerConnection,
        sdp: SessionDescription,
    ) = suspendCancellableCoroutine<Unit> { cont ->
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription failed: $error")
                if (cont.isActive) cont.resumeWithException(RuntimeException(error ?: "unknown"))
            }
        }, sdp)
    }

    /**
     * Waits for ICE gathering to reach COMPLETE state, or times out.
     * Polls the PeerConnection state every 50ms — WebRTC has no
     * callback-based "wait for gathering" API; the observer fires
     * onIceGatheringChange but coordinating that with a suspendable
     * wait requires an extra state machine. Polling is simpler
     * and the gathering is fast (sub-second on LAN).
     */
    private suspend fun waitForIceGathering(
        pc: PeerConnection,
        timeoutMs: Long,
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                return true
            }
            delay(50)
        }
        return false
    }

    /**
     * POSTs the SDP offer to `/api/v1/cameras/{id}/webrtc` and
     * returns the SDP answer string. The body is the raw SDP text
     * (Content-Type: application/sdp), NOT a JSON wrapper.
     *
     * Returns null on any non-2xx response or network error.
     */
    private suspend fun postOffer(cameraId: Long, sdpOffer: String): String? {
        val url = "${baseUrl.trimEnd('/')}/api/v1/cameras/$cameraId/webrtc"
        val req = Request.Builder()
            .url(url)
            .post(sdpOffer.toRequestBody("application/sdp".toMediaType()))
            .apply {
                if (!token.isNullOrEmpty()) {
                    addHeader("Authorization", "Bearer $token")
                    addHeader("Cookie", "home_token=$token")
                }
                addHeader("Accept", "application/sdp")
            }
            .build()
        return try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "WebRTC signaling HTTP ${resp.code} for $url")
                    return@use null
                }
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebRTC signaling network error: ${e.message}")
            null
        }
    }

    /** Detaches video sinks and disposes the PeerConnection. */
    fun release() {
        signalingJob?.cancel()
        videoTrack = null
        peerConnection?.let {
            try { it.dispose() } catch (_: Exception) {}
        }
        peerConnection = null
    }

    /** Releases the PeerConnectionFactory + EGL context (call from Activity.onDestroy). */
    fun shutdown() {
        release()
        factory?.let {
            try { it.dispose() } catch (_: Exception) {}
        }
        factory = null
        try { eglBase.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "WebRtcClient"
    }
}
