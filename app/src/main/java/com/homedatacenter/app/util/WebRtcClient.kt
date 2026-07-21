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
import org.webrtc.AudioTrack
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
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var signalingJob: Job? = null
    // v1.6.13: latched true once the listener has received onConnected
    // OR onError for the current stream. Used by the app-level
    // connection timeout to avoid firing after the stream has
    // already settled. Reset to false at the start of each
    // startStreamInternal call.
    @Volatile
    private var connectedOrFailed: Boolean = false

    /**
     * v1.5.8: Toggle video track enabled state. Used by the
     * WebRTC control bar's pause button — when disabled, the
     * SurfaceViewRenderer stops getting new frames (last frame
     * stays on screen) but the PeerConnection stays alive so
     * resume is instant (no re-negotiation needed).
     */
    fun setVideoEnabled(enabled: Boolean) {
        videoTrack?.setEnabled(enabled)
    }

    /**
     * v1.5.8: Toggle audio track enabled state. Mute is a local
     * operation — the backend keeps sending RTP audio, we just
     * stop rendering it. Cheaper than setVolume(0f) and survives
     * track re-negotiation.
     */
    fun setAudioEnabled(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
    }

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
     * @param isLan v1.6.13: hint from the caller (BaseUrlResolver.isLan())
     *     that controls candidate-filtering policy. On LAN we disable
     *     TCP candidates (saves 100-300ms of pointless TCP
     *     host-candidate gathering) and use a short ICE gathering
     *     timeout (host candidates appear in <100ms). On remote we
     *     ENABLE TCP candidates (go2rtc exposes 8555 TCP, this is a
     *     critical fallback when UDP is blocked by carrier NAT or
     *     firewall) and use a longer ICE gathering timeout so STUN
     *     round-trips on cellular have time to complete.
     * @param listener Callbacks for connect/error/state events.
     */
    fun startStream(
        cameraId: Long,
        surfaceRenderer: SurfaceViewRenderer,
        iceServers: List<PeerConnection.IceServer>,
        isLan: Boolean,
        listener: Listener,
    ) {
        signalingJob?.cancel()
        signalingJob = scope.launch {
            try {
                startStreamInternal(cameraId, surfaceRenderer, iceServers, isLan, listener)
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
        isLan: Boolean,
        listener: Listener,
    ) {
        val pcFactory = factory ?: run {
            listener.onError("factory not initialized")
            return
        }
        // v1.6.13: reset the connection-settled latch for this
        // attempt. The app-level connection timeout checks this
        // flag to know whether to fire onError("connection timeout").
        connectedOrFailed = false
        // Tear down any previous PeerConnection so we can start fresh
        // on a reload. removeSink detaches the previous
        // SurfaceViewRenderer sink before the track is disposed.
        videoTrack?.removeSink(surfaceRenderer)
        videoTrack = null
        peerConnection?.let { it.dispose() }
        peerConnection = null

        val pcObserver = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                // v1.6.17: log every gathered candidate. This mirrors
                // the browser's `iceGatheringState` + candidate event
                // logging — without it we can't tell whether STUN
                // reflexive gathering succeeded or timed out, which
                // was the root cause of the v1.6.13-v1.6.16 "WebRTC
                // always fails on remote" bug. The candidate's
                // `candidateType()` (host / srflx / relay) tells us
                // exactly which ICE path go2rtc can use to reach us.
                candidate?.let { c ->
                    val serverUrl = c.serverUrl ?: ""
                    Log.i(TAG, "ICE candidate: type=${c.sdpMid ?: "?"} " +
                        "url=$serverUrl " +
                        "addr=${c.sdp ?: "(no sdp)"}")
                }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE state: $state")
                // v1.5.5: PeerConnection.Observer callbacks fire on
                // WebRTC's internal signaling thread, NOT the main
                // thread. If the listener touches any View (e.g.
                // binding.progressVideo.visibility = View.GONE) we
                // get a CalledFromWrongThreadException. The WebRTC
                // JNI bridge checks env->ExceptionCheck() after the
                // callback returns, sees the pending Java exception,
                // and fails RTC_CHECK(!env->ExceptionCheck()) —
                // which calls abort() and kills the process.
                // Fix: dispatch ALL listener callbacks through the
                // main-thread scope so listeners can safely touch UI.
                state?.let { nonNullState ->
                    scope.launch { listener.onIceStateChanged(nonNullState) }
                    when (nonNullState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            // v1.6.13: latch the settled state so the
                            // app-level connection timeout knows not
                            // to fire.
                            connectedOrFailed = true
                            scope.launch { listener.onConnected() }
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            connectedOrFailed = true
                            scope.launch { listener.onError("ICE failed") }
                        }
                        else -> {}
                    }
                }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track() ?: run {
                    android.util.Log.w(TAG, "onTrack: transceiver.receiver.track() = null")
                    return
                }
                android.util.Log.d(TAG, "onTrack: kind=${track.kind()} " +
                    "direction=${transceiver?.direction}")
                when (track.kind()) {
                    MediaStreamTrack.VIDEO_TRACK_KIND -> {
                        val vt = track as VideoTrack
                        videoTrack = vt
                        // addSink must run on the main thread (EGL
                        // renderer's onFrame is posted there).
                        scope.launch { vt.addSink(surfaceRenderer) }
                    }
                    MediaStreamTrack.AUDIO_TRACK_KIND -> {
                        // v1.5.8: capture the audio track so the
                        // control bar's mute button can toggle it.
                        // v1.5.9: explicitly enable + max volume.
                        // v1.5.13: add diagnostic logging. The user
                        // reported "no sound" — if this branch never
                        // fires it confirms the backend strips
                        // audio (default rtspURL uses #audio=0 when
                        // Capabilities["audio"] is not true).
                        val at = track as AudioTrack
                        audioTrack = at
                        android.util.Log.d(TAG, "onTrack: audio track received, " +
                            "enabled=${at.enabled()}")
                        at.setEnabled(true)
                        runCatching { at.setVolume(1.0) }
                    }
                    else -> {
                        android.util.Log.w(TAG, "onTrack: unknown track kind=${track.kind()}")
                    }
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
            // v1.6.13: TCP candidate policy is now conditional on isLan.
            // - LAN: DISABLED — TCP candidate gathering adds 100-300ms
            //   (tries to connect to TCP 80/443 on the gateway which
            //   never answers). LAN streaming uses UDP host candidates
            //   only.
            // - Remote: ENABLED — go2rtc exposes 8555 TCP, and TCP
            //   candidates are a critical fallback when UDP is blocked
            //   by carrier-grade NAT, symmetric NAT, or firewall. The
            //   previous global DISABLED setting was a major cause of
            //   the "~20s and falls back to MP4" bug on external
            //   networks: the Android client couldn't use go2rtc's TCP
            //   candidate, so ICE had no working pair when UDP failed.
            tcpCandidatePolicy = if (isLan) {
                PeerConnection.TcpCandidatePolicy.DISABLED
            } else {
                PeerConnection.TcpCandidatePolicy.ENABLED
            }
            // v1.6.18: rtcpMuxPolicy kept at REQUIRE (WebRTC library
            // default for UnifiedPlan). v1.6.17 removed it thinking
            // it caused SDP negotiation failures with go2rtc, but the
            // real root cause was the audio transceiver (see below).
            // Reverting to match v1.6.13-v1.6.16 behavior.
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val pc = pcFactory.createPeerConnection(pcConfig, pcObserver) ?: run {
            listener.onError("createPeerConnection returned null")
            return
        }
        peerConnection = pc

        // v1.6.18: VIDEO-ONLY transceiver, matching the dashboard's
        // useWebRTCStream.ts. The previous v1.5.3-v1.6.17 code added
        // both audio + video transceivers, but the backend's go2rtc
        // source URL includes `#audio=0` (see registry.go rtspURL)
        // which strips the audio track at the source. When go2rtc
        // receives an SDP offer with an audio m-line but has no audio
        // source to offer, the SDP negotiation breaks — go2rtc either
        // rejects the offer or returns a malformed answer, and ICE
        // never reaches CONNECTED.
        //
        // The dashboard (which reliably succeeds in ~4s on remote)
        // only adds a video transceiver for exactly this reason — see
        // useWebRTCStream.ts line 146:
        //   "Video only — camera audio codecs (G726/PCMU/MPEG4-
        //    GENERIC) are not browser-decodable via WebRTC. The API
        //    also appends #audio=0 to the go2rtc source URL so go2rtc
        //    won't even try to negotiate audio."
        //
        // Camera audio is still available via the MP4/HLS fallback
        // paths (which use AAC, not WebRTC's Opus). The WebRTC
        // control bar's mute button (btnWebRtcMute) is now a no-op
        // for live streams — we keep the button for UI stability but
        // it has no audio track to toggle.
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            )
        )

        // v1.6.18: OfferToReceiveAudio removed. Setting it to "true"
        // forces an audio m-line into the SDP offer even though we
        // didn't add an audio transceiver — same root cause as above.
        // The dashboard doesn't set this constraint either.
        val constraints = MediaConstraints().apply {
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
        // Typical: ~200-500ms on LAN (host candidates only), up to
        // 5s with STUN/TURN.
        // v1.5.7: reduce timeout from 5s -> 2s so a stuck gathering
        // phase fails faster and falls back to MP4. On LAN the
        // host candidate is gathered in <100ms; 2s is still 4x
        // headroom for STUN. If gathering times out we send the
        // partial SDP anyway — backend will reject if it can't
        // pick a candidate, and the listener's onError -> MP4
        // fallback kicks in.
        // v1.6.10: when there are no STUN/TURN servers (LAN mode
        // passes an empty iceServers list), host candidate gathering
        // completes in <100ms — drop the timeout to 800ms so a
        // stuck gather fails 1.2s faster. With STUN/TURN (remote
        // mode), keep 2s to allow STUN round-trips to complete.
        // v1.6.13: remote-mode timeout bumped 2s -> 5s. Cellular
        // STUN round-trips to Google/Cloudflare can take 1-3s on
        // flaky mobile networks; the previous 2s timeout was
        // cutting off STUN-reflexive candidate gathering before
        // it completed, leaving the offer with only unreachable
        // host candidates. The browser (which has a 3s timeout)
        // was succeeding where Android was failing. The decision
        // is now based on isLan (passed in by the caller) instead
        // of iceServers.isEmpty() — those usually agree but
        // iceServers could be empty even in remote mode if the
        // backend returns an empty list, in which case we still
        // want the longer timeout (host candidates on cellular
        // are unreachable from the home server, so we want any
        // late-arriving candidate to make it into the offer).
        val iceTimeoutMs = if (isLan) 800L else 5_000L
        val gatheringComplete = withContext(Dispatchers.IO) {
            waitForIceGathering(pc, timeoutMs = iceTimeoutMs)
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

        // v1.6.13: app-level connection timeout. Without this, a
        // stuck ICE state (CHECKING forever, never CONNECTED or
        // FAILED) leaves the user staring at the loading spinner
        // for the WebRTC library's internal ~20-30s ICE FAILED
        // timeout. The user reports ~20s before falling back to
        // MP4 on external network — this is the WebRTC library
        // exhausting its candidate-pair retry sequence before
        // finally firing IceConnectionState.FAILED.
        //
        // 10s is the cap: ICE on a healthy remote link completes
        // in 1-4s (STUN + DTLS handshake). On a broken link we
        // want to bail out at 10s, not 20-30s, so MP4 fallback
        // kicks in fast enough to feel responsive. The timeout
        // fires only if the listener hasn't already received
        // onConnected or onError — `connectedOrFailed` is set by
        // the PeerConnection.Observer callbacks (which we route
        // through `scope.launch` to the main-thread scope).
        //
        // LAN mode gets a shorter 5s timeout because host-candidate
        // ICE completes in <500ms; any longer than 5s on LAN means
        // something is genuinely wrong.
        //
        // v1.6.18: reverted v1.6.17's 15s timeout back to 10s. The
        // real WebRTC failure root cause was the audio transceiver
        // (see addTransceiver comment above), not the timeout being
        // too short. With the audio m-line removed, ICE completes
        // in ~2-4s on remote, so 10s is plenty of headroom.
        if (!connectedOrFailed) {
            val connectTimeoutMs = if (isLan) 5_000L else 10_000L
            scope.launch {
                delay(connectTimeoutMs)
                if (!connectedOrFailed) {
                    Log.w(TAG, "WebRTC connection timed out after ${connectTimeoutMs}ms — falling back to MP4")
                    listener.onError("connection timeout")
                }
            }
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
        audioTrack = null
        peerConnection?.let {
            try { it.dispose() } catch (_: Exception) {}
        }
        peerConnection = null
    }

    /**
     * v1.6.10: re-attaches the current videoTrack (if any) to a
     * freshly-init'd SurfaceViewRenderer. Used by CameraDetailActivity
     * .onResume to recover video after the EGL surface was torn down
     * in onPause — the PeerConnection stayed alive so we don't need
     * to re-do SDP/signaling; we just rebind the renderer.
     *
     * Returns true if a track was attached, false if no track is
     * available (caller should call startStream to rebuild).
     */
    fun reattachVideoTrack(surfaceRenderer: SurfaceViewRenderer): Boolean {
        val vt = videoTrack ?: return false
        val pc = peerConnection ?: return false
        val state = pc.iceConnectionState()
        if (state != PeerConnection.IceConnectionState.CONNECTED &&
            state != PeerConnection.IceConnectionState.COMPLETED) {
            return false
        }
        return try {
            vt.addSink(surfaceRenderer)
            Log.d(TAG, "Video track re-attached on resume (PC state=$state)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "reattachVideoTrack failed: ${e.message}")
            false
        }
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
