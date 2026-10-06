package com.homedatacenter.app.util

import android.content.Context
import android.util.Log
import com.homedatacenter.app.data.api.NetworkFactory
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
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
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String?,
) {
    /** Secondary constructor for static baseUrl / token backward compatibility. */
    constructor(
        context: Context,
        okHttpClient: OkHttpClient,
        baseUrl: String,
        token: String?,
    ) : this(context, okHttpClient, { baseUrl }, { token })
    /** Owns the EGL context used for video decoding + rendering. */
    val eglBase: EglBase = EglBase.create()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var audioTransceiver: RtpTransceiver? = null
    private var localAudioSource: org.webrtc.AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    @Volatile
    private var isTalkingBack: Boolean = false
    @Volatile
    var talkbackMicGain: Float = 5.0f // 5.0x software amplifier for camera speaker
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    // v1.7.2: latch the user's mute preference so that audio tracks
    // arriving AFTER a reconnect / renegotiation honour it. Without
    // this, onTrack forces setEnabled(true) + setVolume(1.0) on every
    // new audio track, un-muting the stream even though the user
    // tapped mute — the "关闭视频声音不停" symptom.
    @Volatile
    private var audioEnabledByUser: Boolean = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var signalingJob: Job? = null
    private var watchdogJob: Job? = null
    // v1.6.13: latched true once the listener has received onConnected
    // OR onError for the current stream. Used by the app-level
    // connection timeout to avoid firing after the stream has
    // already settled. Reset to false at the start of each
    // startStreamInternal call.
    @Volatile
    private var connectedOrFailed: Boolean = false

    // v1.6.35: SDP pre-negotiation state. prepareOffer() creates a
    // PeerConnection and completes ICE gathering in advance; the
    // next startStream() call consumes the prepared PC (if it matches
    // the requested cameraId) to skip the 800ms LAN / 5s remote ICE
    // gathering delay. The prepared PC is one-shot — once consumed
    // or replaced, it's cleared.
    @Volatile
    private var preparedPc: PeerConnection? = null
    @Volatile
    private var preparedSdp: String? = null
    @Volatile
    private var preparedCameraId: Long = -1L
    private var prepareJob: Job? = null
    private var disconnectWatchdogJob: Job? = null
    @Volatile
    private var candidateCount: Int = 0
    @Volatile
    private var hasSrflxCandidate: Boolean = false
    @Volatile
    private var hasHostCandidate: Boolean = false

    // v1.6.35: settable listener + surface renderer for the shared
    // observer. The observer is created at PeerConnection creation
    // time (in prepareOffer), but the listener/surface are only known
    // when startStream is called. All coroutines run on Dispatchers.Main
    // so volatile reads/writes are safe and no locking is needed.
    @Volatile
    private var activeListener: Listener? = null
    @Volatile
    private var activeSurfaceRenderer: SurfaceViewRenderer? = null

    /**
     * v1.5.8: Toggle video track enabled state. Used by the
     * WebRTC control bar's pause button — when disabled, the
     * SurfaceViewRenderer stops getting new frames (last frame
     * stays on screen) but the PeerConnection stays alive so
     * resume is instant (no re-negotiation needed).
     */
    fun setVideoEnabled(enabled: Boolean) {
        val vt = videoTrack
        val sink = activeSurfaceRenderer
        if (enabled) {
            vt?.setEnabled(true)
            if (sink != null && vt != null) {
                try {
                    vt.addSink(sink)
                } catch (_: Exception) {}
            }
            if (audioEnabledByUser) {
                audioTrack?.setEnabled(true)
            }
        } else {
            if (sink != null && vt != null) {
                try {
                    vt.removeSink(sink)
                } catch (_: Exception) {}
            }
            vt?.setEnabled(false)
            audioTrack?.setEnabled(false)
        }
    }

    @Volatile
    private var audioTrackField: java.lang.reflect.Field? = null

    private fun getAudioTrackFromAdm(): android.media.AudioTrack? {
        val audioOutput = audioDeviceModule?.audioOutput ?: return null
        return try {
            var field = audioTrackField
            if (field == null) {
                field = audioOutput.javaClass.getDeclaredField("audioTrack").apply {
                    isAccessible = true
                }
                audioTrackField = field
            }
            field.get(audioOutput) as? android.media.AudioTrack
        } catch (_: Exception) {
            null
        }
    }

    /**
     * v1.12.13: Immediately pauses and flushes the underlying AudioTrack hardware buffer
     * to eliminate any audio residue when stopping or muting playback.
     */
    fun stopPlayoutImmediately() {
        try {
            audioTrack?.setEnabled(false)
            audioTrack?.setVolume(0.0)
        } catch (_: Exception) {}
        try {
            audioDeviceModule?.setSpeakerMute(true)
        } catch (_: Exception) {}
        try {
            val audioOutput = audioDeviceModule?.audioOutput
            if (audioOutput != null) {
                val method = audioOutput.javaClass.getDeclaredMethod("stopPlayout").apply {
                    isAccessible = true
                }
                method.invoke(audioOutput)
            }
        } catch (_: Exception) {}
        try {
            val at = getAudioTrackFromAdm()
            at?.pause()
            at?.flush()
            at?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stopPlayoutImmediately: ${e.message}")
        }
    }

    /**
     * v1.5.8: Toggle audio track enabled state. Mute is a local
     * operation — the backend keeps sending RTP audio, we just
     * stop rendering it. Cheaper than setVolume(0f) and survives
     * track re-negotiation.
     *
     * v1.7.2: also latch the preference in [audioEnabledByUser] so
     * that a late-arriving audio track (after ICE reconnect) does
     * not un-mute the stream via onTrack's default setEnabled(true).
     *
     * v1.12.13: flush AudioTrack hardware buffer immediately when disabled
     * to eliminate any audio residue.
     */
    fun setAudioEnabled(enabled: Boolean) {
        audioEnabledByUser = enabled
        audioTrack?.setEnabled(enabled)
        try {
            audioTrack?.setVolume(if (enabled) 1.0 else 0.0)
        } catch (_: Exception) {}
        try {
            audioDeviceModule?.setSpeakerMute(!enabled)
        } catch (_: Exception) {}
        if (!enabled) {
            try {
                val at = getAudioTrackFromAdm()
                at?.pause()
                at?.flush()
            } catch (_: Exception) {}
        } else {
            try {
                val at = getAudioTrackFromAdm()
                if (at != null && at.playState != android.media.AudioTrack.PLAYSTATE_PLAYING) {
                    at.play()
                }
            } catch (_: Exception) {}
        }
    }


    /**
     * v1.13.0: Prepares or gets the local microphone audio track and attaches it to the transceiver.
     */
    private fun ensureLocalAudioTrack(transceiver: RtpTransceiver): String? {
        val pcFactory = factory ?: run {
            Log.w(TAG, "ensureLocalAudioTrack: factory is null")
            return "WebRTC底层引擎未初始化"
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "ensureLocalAudioTrack: RECORD_AUDIO permission not granted yet")
            return "未授予麦克风录音权限"
        }
        return try {
            // Check if existing track or source has ended or been disposed by native WebRTC
            val isDisposed = try {
                val t = localAudioTrack
                t == null || t.state() == MediaStreamTrack.State.ENDED
            } catch (_: Throwable) {
                true
            }
            if (isDisposed) {
                try { localAudioTrack?.dispose() } catch (_: Throwable) {}
                localAudioTrack = null
                try { localAudioSource?.dispose() } catch (_: Throwable) {}
                localAudioSource = null
            }

            if (localAudioTrack == null || localAudioSource == null) {
                val constraints = MediaConstraints().apply {
                    // Disable VoIP AGC and NS so our 5.0x amplifier isn't squashed down or filtered
                    optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                    optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                    optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
                }
                val src = try {
                    pcFactory.createAudioSource(constraints)
                } catch (e: Exception) {
                    Log.w(TAG, "createAudioSource with constraints failed: ${e.message}, fallback to empty constraints")
                    pcFactory.createAudioSource(MediaConstraints())
                }
                localAudioSource = src
                val track = pcFactory.createAudioTrack("ARDAMSa0_mic", src)
                track.setEnabled(false) // Muted until user explicitly speaks
                localAudioTrack = track
            }
            val track = localAudioTrack ?: return "音频音轨创建失败"
            val sender = transceiver.sender ?: return "音频发送通道不存在"
            val currentTrack = try {
                sender.track()
            } catch (_: Throwable) {
                null
            }
            if (currentTrack != track) {
                val ok = sender.setTrack(track, false)
                Log.i(TAG, "ensureLocalAudioTrack: local audio track attached to transceiver sender, ok=$ok")
            }
            runCatching {
                if (transceiver.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                    transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                }
            }
            null
        } catch (e: Throwable) {
            Log.e(TAG, "ensureLocalAudioTrack failed: ${e.message}", e)
            "音频通道异常: ${e.javaClass.simpleName}(${e.message})"
        }
    }

    /**
     * Pre-warms local audio track and binds to transceiver if permissions are granted.
     */
    fun warmLocalAudioTrack() {
        val transceiver = audioTransceiver ?: peerConnection?.transceivers?.find {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
        } ?: return
        ensureLocalAudioTrack(transceiver)
    }

    /**
     * Captures audio from the device microphone and sends it to the camera backchannel.
     * Returns null on success, or an error description string on failure.
     */
    fun startTalkback(): String? {
        val pc = peerConnection ?: run {
            Log.w(TAG, "startTalkback: peerConnection is null")
            return "视频流未就绪或已断开"
        }
        val transceiver = audioTransceiver
            ?: pc.transceivers.find { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            ?: run {
                Log.w(TAG, "startTalkback: audioTransceiver not found")
                return "未找到摄像机音频通道"
            }
        audioTransceiver = transceiver

        return try {
            val err = ensureLocalAudioTrack(transceiver)
            if (err != null) {
                Log.w(TAG, "startTalkback: ensureLocalAudioTrack error: $err")
                return err
            }
            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.mode = android.media.AudioManager.MODE_NORMAL
            localAudioTrack?.setEnabled(true)
            isTalkingBack = true
            Log.i(TAG, "Talkback started (mic unmuted, 5.0x amplifier active, mode=NORMAL)")
            null
        } catch (e: Exception) {
            Log.e(TAG, "startTalkback failed: ${e.message}", e)
            "对讲启动异常: ${e.javaClass.simpleName}(${e.message})"
        }
    }

    /**
     * v1.13.0: Stops two-way talkback by muting the microphone track.
     */
    fun stopTalkback() {
        isTalkingBack = false
        try {
            localAudioTrack?.setEnabled(false)
            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.mode = android.media.AudioManager.MODE_NORMAL
            Log.i(TAG, "Talkback stopped (mic muted)")
        } catch (e: Exception) {
            Log.w(TAG, "stopTalkback failed: ${e.message}")
        }
    }

    fun isTalkingBack(): Boolean = isTalkingBack

    fun isConnected(): Boolean {
        val pc = peerConnection ?: return false
        val state = pc.iceConnectionState()
        return (state == PeerConnection.IceConnectionState.CONNECTED ||
                state == PeerConnection.IceConnectionState.COMPLETED)
    }

    fun hasActiveStream(): Boolean = peerConnection != null && videoTrack != null

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
            .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .createAudioDeviceModule()
        audioDeviceModule = audioDevice
        // Hardware-accelerated encoder/decoder where available.
        // The second boolean (enableH264HighProfile) is true so we
        // can decode H264 streams from Hikvision cameras without
        // falling back to software decode.
        val videoEncoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val videoDecoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        val audioProcessingFactory = try {
            ExternalAudioProcessingFactory().apply {
                // In WebRTC C++ APM, bypass flags default to true! We MUST explicitly disable bypass!
                setBypassFlagForCapturePost(false)
                setBypassFlagForRenderPre(false)

                // 1. Microphone capture amplifier (Talkback to camera speaker)
                setCapturePostProcessing(object : ExternalAudioProcessingFactory.AudioProcessing {
                    override fun initialize(sampleRateHz: Int, numChannels: Int) {}
                    override fun reset(newRate: Int) {}
                    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
                        if (!isTalkingBack) return
                        val gain = talkbackMicGain
                        if (gain <= 1.0f) return
                        val originalOrder = buffer.order()
                        val shortBuf = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val count = shortBuf.remaining()
                        for (i in 0 until count) {
                            val sample = shortBuf.get(i).toFloat()
                            val amplified = (sample * gain).toInt()
                            val clamped = when {
                                amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                                amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                                else -> amplified.toShort()
                            }
                            shortBuf.put(i, clamped)
                        }
                        buffer.order(originalOrder)
                    }
                })

            }
        } catch (e: Throwable) {
            Log.w(TAG, "ExternalAudioProcessingFactory initialization skipped: ${e.message}")
            null
        }

        val pcBuilder = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDevice)
            .setVideoEncoderFactory(videoEncoder)
            .setVideoDecoderFactory(videoDecoder)
        if (audioProcessingFactory != null) {
            try {
                pcBuilder.setAudioProcessingFactory(audioProcessingFactory)
                Log.i(TAG, "WebRTC Software Audio Processors (Mic & Playback) attached successfully")
            } catch (e: Throwable) {
                Log.w(TAG, "setAudioProcessingFactory failed: ${e.message}")
            }
        }
        factory = pcBuilder.createPeerConnectionFactory()
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
        startStream(cameraId, surfaceRenderer, iceServers, isLan, false, "720p", listener)
    }

    fun startStream(
        cameraId: Long,
        surfaceRenderer: SurfaceViewRenderer,
        iceServers: List<PeerConnection.IceServer>,
        isLan: Boolean,
        enableTwoWayAudio: Boolean,
        quality: String = "720p",
        listener: Listener,
    ) {
        signalingJob?.cancel()
        signalingJob = scope.launch {
            try {
                startStreamInternal(cameraId, surfaceRenderer, iceServers, isLan, enableTwoWayAudio, quality, listener)
            } catch (e: Exception) {
                Log.e(TAG, "WebRTC signaling failed: ${e.message}", e)
                listener.onError(e.message ?: "unknown")
            }
        }
    }

    /**
     * v1.6.35: Pre-negotiate the SDP offer before the user taps Play.
     */
    fun prepareOffer(
        cameraId: Long,
        iceServers: List<PeerConnection.IceServer>,
        isLan: Boolean,
    ) {
        prepareOffer(cameraId, iceServers, isLan, false)
    }

    fun prepareOffer(
        cameraId: Long,
        iceServers: List<PeerConnection.IceServer>,
        isLan: Boolean,
        enableTwoWayAudio: Boolean,
    ) {
        prepareJob?.cancel()
        prepareJob = scope.launch {
            try {
                prepareOfferInternal(cameraId, iceServers, isLan, enableTwoWayAudio)
            } catch (e: Exception) {
                Log.w(TAG, "prepareOffer failed: ${e.message}")
                preparedPc?.let { try { it.dispose() } catch (_: Exception) {} }
                preparedPc = null
                preparedSdp = null
                preparedCameraId = -1L
            }
        }
    }

    private suspend fun prepareOfferInternal(
        cameraId: Long,
        iceServers: List<PeerConnection.IceServer>,
        isLan: Boolean,
        enableTwoWayAudio: Boolean,
    ) {
        val pcFactory = factory ?: run {
            Log.w(TAG, "prepareOffer: factory not initialized")
            return
        }

        // Tear down any previously prepared PC before creating a new one.
        preparedPc?.let { try { it.dispose() } catch (_: Exception) {} }
        preparedPc = null
        preparedSdp = null
        preparedCameraId = -1L

        val pcConfig = buildRtcConfiguration(iceServers, isLan)
        val pc = pcFactory.createPeerConnection(pcConfig, createPeerConnectionObserver())
            ?: run {
                Log.w(TAG, "prepareOffer: createPeerConnection returned null")
                return
            }

        try {
            // Add video transceiver (recvonly) and audio transceiver (sendrecv if twoWayAudio).
            val audioDirection = if (enableTwoWayAudio) {
                RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            } else {
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            }
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                )
            )
            audioTransceiver = pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(audioDirection)
            )

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                // Disable CPU overuse detection and bandwidth suspension to prevent WebRTC
                // from artificially dropping frames or freezing video during transient network/CPU load.
                optional.add(MediaConstraints.KeyValuePair("googCpuOveruseDetection", "false"))
                optional.add(MediaConstraints.KeyValuePair("googSuspendBelowMinBitrate", "false"))
            }

            val offer = withContext(Dispatchers.IO) {
                createOfferSuspend(pc, constraints)
            } ?: run {
                Log.w(TAG, "prepareOffer: createOffer returned null")
                pc.dispose()
                return
            }

            candidateCount = 0
            hasSrflxCandidate = false
            hasHostCandidate = false

            withContext(Dispatchers.IO) {
                setLocalDescriptionSuspend(pc, offer)
            }

            // Wait for ICE gathering — fast early exit once candidates are gathered.
            val iceTimeoutMs = if (isLan) 600L else 1_200L
            val gatheringComplete = withContext(Dispatchers.IO) {
                waitForIceGathering(pc, timeoutMs = iceTimeoutMs, isLan = isLan)
            }
            if (!gatheringComplete) {
                Log.w(TAG, "prepareOffer: ICE gathering timed out; using partial offer")
            }

            val localSdp = pc.localDescription?.description ?: run {
                Log.w(TAG, "prepareOffer: localDescription is null")
                pc.dispose()
                return
            }

            preparedPc = pc
            preparedSdp = localSdp
            preparedCameraId = cameraId
            Log.d(TAG, "prepareOffer done: cameraId=$cameraId, sdpLen=${localSdp.length}")
        } catch (e: Exception) {
            try { pc.dispose() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * v1.6.35: Builds the RTCConfiguration shared by both prepareOffer
     * and startStreamInternal. Extracted so the config is identical
     * whether the PC is created in the pre-negotiation phase or the
     * live-stream phase — a mismatch would invalidate the prepared SDP.
     */
    private fun buildRtcConfiguration(
        iceServers: List<PeerConnection.IceServer>,
        isLan: Boolean,
    ): PeerConnection.RTCConfiguration {
        val filteredServers = iceServers.filterNot { srv ->
            srv.urls.any { it.contains("google.com") }
        }.let { list ->
            if (list.isEmpty() && !isLan) {
                listOf(
                    PeerConnection.IceServer.builder("stun:stun.qq.com:3478").createIceServer(),
                    PeerConnection.IceServer.builder("stun:stun.miwifi.com:3478").createIceServer(),
                    PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
                )
            } else {
                list
            }
        }
        return PeerConnection.RTCConfiguration(filteredServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // v1.13.19: GATHER_CONTINUALLY allows dynamic candidate adaptation during
            // network handovers and transient NAT re-mapping, preventing video freeze.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceConnectionReceivingTimeout = 4000
            tcpCandidatePolicy = if (isLan) {
                PeerConnection.TcpCandidatePolicy.DISABLED
            } else {
                PeerConnection.TcpCandidatePolicy.ENABLED
            }
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
    }

    /**
     * v1.6.35: Creates the PeerConnection.Observer used by both
     * prepareOffer and startStreamInternal. The observer delegates to
     * [activeListener] and [activeSurfaceRenderer] — these are set at
     * the start of [startStreamInternal] so callbacks that fire after
     * setRemoteDescription (onTrack, onIceConnectionChange) reach the
     * correct listener/surface. Before startStream is called (during
     * prepareOffer's ICE gathering phase), these fields are null and
     * the observer's onTrack/onConnected callbacks are no-ops, which
     * is correct — we don't want to notify any listener until the user
     * actually starts playback.
     */
    private fun createPeerConnectionObserver(): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { c ->
                    candidateCount++
                    val sdp = c.sdp ?: "(no sdp)"
                    if (sdp.contains("typ srflx")) hasSrflxCandidate = true
                    if (sdp.contains("typ host")) hasHostCandidate = true
                    val addrIsIpv6 = sdp.contains("::")
                    android.util.Log.i(TAG, "ICE candidate: type=${c.sdpMid ?: "?"} " +
                        "url=${c.serverUrl ?: ""} " +
                        "addrFamily=${if (addrIsIpv6) "IPv6" else "IPv4"} " +
                        "sdp=$sdp")
                }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE state: $state")
                state?.let { nonNullState ->
                    scope.launch { activeListener?.onIceStateChanged(nonNullState) }
                    when (nonNullState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            connectedOrFailed = true
                            disconnectWatchdogJob?.cancel()
                            disconnectWatchdogJob = null
                            scope.launch { activeListener?.onConnected() }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            // v1.13.19 fast failover watchdog: do not hang for 30s.
                            // If remote tunnel rotated or NAT dropped, trigger recovery.
                            // Relaxed to 3500ms to allow WebRTC continual gathering to recover from brief network jitter
                            // without falsely tearing down the stream.
                            disconnectWatchdogJob?.cancel()
                            disconnectWatchdogJob = scope.launch {
                                delay(3500)
                                val current = peerConnection?.iceConnectionState()
                                if (current == PeerConnection.IceConnectionState.DISCONNECTED ||
                                    current == PeerConnection.IceConnectionState.FAILED
                                ) {
                                    Log.w(TAG, "ICE remained DISCONNECTED for 3500ms — triggering seamless failover")
                                    connectedOrFailed = true
                                    activeListener?.onError("ICE disconnected (3.5s fast failover)")
                                }
                            }
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            disconnectWatchdogJob?.cancel()
                            disconnectWatchdogJob = null
                            connectedOrFailed = true
                            scope.launch { activeListener?.onError("ICE failed") }
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            disconnectWatchdogJob?.cancel()
                            disconnectWatchdogJob = null
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
                        val sink = activeSurfaceRenderer
                        if (sink != null) {
                            scope.launch { vt.addSink(sink) }
                        } else {
                            android.util.Log.w(TAG, "onTrack: video track arrived but no surfaceRenderer set")
                        }
                    }
                    MediaStreamTrack.AUDIO_TRACK_KIND -> {
                        val at = track as AudioTrack
                        audioTrack = at
                        // v1.7.2: honour the user's mute preference
                        // instead of unconditionally enabling audio.
                        // A reconnect delivers a fresh track here; if
                        // the user had muted, forcing enabled=true
                        // brought the sound back ("关闭声音不停").
                        at.setEnabled(audioEnabledByUser)
                        runCatching {
                            at.setVolume(if (audioEnabledByUser) 1.0 else 0.0)
                        }
                        try {
                            audioDeviceModule?.setSpeakerMute(!audioEnabledByUser)
                        } catch (_: Exception) {}
                        if (!audioEnabledByUser) {
                            try {
                                val atNative = getAudioTrackFromAdm()
                                atNative?.pause()
                                atNative?.flush()
                            } catch (_: Exception) {}
                        }
                        android.util.Log.d(TAG, "onTrack: audio track received, " +
                            "userEnabled=$audioEnabledByUser enabled=${at.enabled()}")
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
    }

    private suspend fun startStreamInternal(
        cameraId: Long,
        surfaceRenderer: SurfaceViewRenderer,
        iceServers: List<PeerConnection.IceServer>,
        isLan: Boolean,
        enableTwoWayAudio: Boolean = false,
        quality: String = "720p",
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
        // v1.12.12: sync speaker mute with audioEnabledByUser instead of unconditionally forcing it on
        try {
            audioDeviceModule?.setSpeakerMute(!audioEnabledByUser)
        } catch (_: Exception) {}

        // v1.6.35: set the active listener + surface so the shared
        // observer (createPeerConnectionObserver) can dispatch
        // onTrack / onIceConnectionChange callbacks to the correct
        // listener. These must be set BEFORE any code that could
        // trigger observer callbacks (setRemoteDescription).
        activeListener = listener
        activeSurfaceRenderer = surfaceRenderer

        // v1.6.35: if prepareOffer is still running, wait for it to
        // complete (with the same timeout as ICE gathering). The
        // prepare has already done PC creation + offer creation +
        // (partial) ICE gathering, so waiting is always faster than
        // starting fresh. If the prepare finishes, we use its PC and
        // skip the full flow. If it times out, we cancel it and do
        // the full flow — but this only happens when ICE gathering
        // itself is stuck (the prepare and full flow use the same
        // timeout, so a stuck prepare means a stuck full flow too).
        val prepareJobLocal = prepareJob
        if (prepareJobLocal != null && prepareJobLocal.isActive) {
            val iceTimeoutMs = if (isLan) 600L else 1_200L
            val waitMs = iceTimeoutMs + 300L
            try {
                withTimeout(waitMs) { prepareJobLocal.join() }
            } catch (_: Exception) {
                // Timeout or cancellation — prepare is still running.
                // Cancel it so its PC (if any) gets disposed by the
                // prepareOffer catch block, then fall through to the
                // full flow.
                prepareJobLocal.cancel()
            }
        }
        prepareJob = null

        // v1.6.35: check for a pre-negotiated PeerConnection. If
        // prepareOffer completed for this cameraId, reuse its PC +
        // local SDP and skip straight to POSTing the offer — this
        // saves the 800ms LAN / 5s remote ICE gathering phase.
        val preparedPcLocal = preparedPc
        val preparedSdpLocal = preparedSdp
        val usePrepared = preparedPcLocal != null &&
            preparedSdpLocal != null &&
            preparedCameraId == cameraId

        // Tear down any previous active PeerConnection so we can
        // start fresh on a reload. removeSink detaches the previous
        // SurfaceViewRenderer sink before the track is disposed.
        videoTrack?.removeSink(surfaceRenderer)
        videoTrack = null
        if (!usePrepared) {
            peerConnection?.let { it.dispose() }
            peerConnection = null
        } else {
            // The prepared PC will become the active peerConnection;
            // dispose any stale non-prepared PC first.
            if (peerConnection != null && peerConnection !== preparedPcLocal) {
                peerConnection?.dispose()
            }
            peerConnection = null
        }

        val pc: PeerConnection
        val localSdp: String

        if (usePrepared) {
            // --- Pre-negotiated fast path ---
            pc = preparedPcLocal!!
            localSdp = preparedSdpLocal!!
            // Clear prepared state (one-shot consumption).
            preparedPc = null
            preparedSdp = null
            preparedCameraId = -1L
            peerConnection = pc
            audioTransceiver = pc.transceivers.find { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            // v1.6.41: upgraded Log.d -> Log.i so the preload fast-path
            // hit is visible in logcat without a debug filter — this is
            // the key signal that precomputeSdpOffer actually paid off.
            Log.i(TAG, "Using precomputed offer for cameraId=$cameraId (skipped ICE gathering)")
        } else {
            // --- Full negotiation path (no prepared PC available) ---
            val pcConfig = buildRtcConfiguration(iceServers, isLan)
            val newPc = pcFactory.createPeerConnection(pcConfig, createPeerConnectionObserver())
                ?: run {
                    listener.onError("createPeerConnection returned null")
                    return
                }
            pc = newPc
            peerConnection = pc

            // v1.12.5: Add video and audio transceivers so live streams
            // negotiate Opus/PCMA audio with go2rtc.
            // v1.13.0: support SEND_RECV direction when enableTwoWayAudio is true.
            val audioDirection = if (enableTwoWayAudio) {
                RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            } else {
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            }
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                )
            )
            audioTransceiver = pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(audioDirection)
            )

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                // Disable CPU overuse detection and bandwidth suspension to prevent WebRTC
                // from artificially dropping frames or freezing video during transient network/CPU load.
                optional.add(MediaConstraints.KeyValuePair("googCpuOveruseDetection", "false"))
                optional.add(MediaConstraints.KeyValuePair("googSuspendBelowMinBitrate", "false"))
            }

            val offer = withContext(Dispatchers.IO) {
                createOfferSuspend(pc, constraints)
            } ?: run {
                listener.onError("createOffer returned null")
                return
            }

            candidateCount = 0
            hasSrflxCandidate = false
            hasHostCandidate = false

            withContext(Dispatchers.IO) {
                setLocalDescriptionSuspend(pc, offer)
            }

            // Wait for ICE gathering — fast early exit once candidates are gathered.
            val iceTimeoutMs = if (isLan) 600L else 1_200L
            val gatheringComplete = withContext(Dispatchers.IO) {
                waitForIceGathering(pc, timeoutMs = iceTimeoutMs, isLan = isLan)
            }
            if (!gatheringComplete) {
                Log.w(TAG, "ICE gathering timed out; sending partial offer")
            }

            val localDesc = pc.localDescription ?: run {
                listener.onError("localDescription is null")
                return
            }
            localSdp = localDesc.description
        }

        // POST the offer to the backend's WHEP-style endpoint.
        // The body is the raw SDP string (Content-Type: application/sdp).
        val answerSdp = withContext(Dispatchers.IO) {
            postOffer(cameraId, localSdp, quality)
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
        // v1.6.23: shortened 10s → 6s on remote. The user reported
        // "webrtc still doesn't load, falls back to HLS" — when
        // IPv6 P2P fails (carrier blocks UDP, or WebRTC library
        // doesn't gather IPv6 candidates), 10s is too long to wait
        // before falling back to HLS. 6s is still 2x the typical
        // IPv6 ICE completion time (~2-3s) but feels much more
        // responsive when the path is broken. LAN stays at 5s
        // (host-candidate ICE completes in <500ms).
        watchdogJob?.cancel()
        if (!connectedOrFailed) {
            // Fast failover watchdog: 4.0s on LAN, 7.0s on remote
            val connectTimeoutMs = if (isLan) 4_000L else 7_000L
            watchdogJob = scope.launch {
                delay(connectTimeoutMs)
                if (!connectedOrFailed && activeListener === listener) {
                    Log.w(TAG, "WebRTC connection timed out after ${connectTimeoutMs}ms — falling back to MP4")
                    activeListener?.onError("connection timeout")
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
     * Fast-paths return early once host candidates (LAN) or reflexive
     * candidates (remote) are discovered, avoiding long waits on unresponsive STUN servers.
     */
    private suspend fun waitForIceGathering(
        pc: PeerConnection,
        timeoutMs: Long,
        isLan: Boolean = false,
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                return true
            }
            val elapsed = System.currentTimeMillis() - start
            // On LAN: once host candidates are present (after min 150ms), return early
            if (isLan && hasHostCandidate && elapsed >= 150) {
                Log.d(TAG, "waitForIceGathering: LAN host candidates ready in ${elapsed}ms")
                return true
            }
            // On remote: once reflexive candidate is discovered or 2+ candidates (after min 250ms), return early
            if (!isLan && (hasSrflxCandidate || candidateCount >= 2) && elapsed >= 250) {
                Log.d(TAG, "waitForIceGathering: remote candidates ready (srflx=$hasSrflxCandidate, count=$candidateCount) in ${elapsed}ms")
                return true
            }
            delay(40)
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
    private suspend fun postOffer(cameraId: Long, sdpOffer: String, quality: String = "720p"): String? {
        val currentBase = baseUrlProvider().trimEnd('/')
        val currentToken = tokenProvider()
        val url = "$currentBase/api/v1/cameras/$cameraId/webrtc?quality=$quality"
        android.util.Log.i(TAG, "postOffer: url=$url, offerLen=${sdpOffer.length}")

        val signalingClient = okHttpClient.newBuilder()
            .callTimeout(9_000, TimeUnit.MILLISECONDS)
            .connectTimeout(4_000, TimeUnit.MILLISECONDS)
            .readTimeout(8_000, TimeUnit.MILLISECONDS)
            .build()

        // v1.13.20: Try up to 2 times. If camera was in a cold-start state waiting for keyframe
        // or remote tunnel had transient lag, the 1st attempt warms up the upstream ffmpeg/RTSP pipeline
        // and the 2nd attempt returns in ~150ms, completely avoiding accidental fallback to HLS.
        for (attempt in 1..2) {
            val start = System.currentTimeMillis()
            try {
                val req = Request.Builder()
                    .url(url)
                    .post(sdpOffer.toRequestBody("application/sdp".toMediaType()))
                    .apply {
                        header("User-Agent", NetworkFactory.USER_AGENT)
                        if (!currentToken.isNullOrEmpty()) {
                            addHeader("Authorization", "Bearer $currentToken")
                            addHeader("Cookie", "home_token=$currentToken")
                        }
                        addHeader("Accept", "application/sdp")
                    }
                    .build()

                val answer = signalingClient.newCall(req).execute().use { resp ->
                    val elapsed = System.currentTimeMillis() - start
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "WebRTC signaling HTTP ${resp.code} for $url (attempt $attempt) in ${elapsed}ms")
                        return@use null
                    }
                    val body = resp.body?.string()?.takeIf { it.isNotBlank() }
                    Log.i(TAG, "WebRTC signaling HTTP 200 for $url (attempt $attempt) in ${elapsed}ms, answerLen=${body?.length ?: 0}")
                    body
                }
                if (answer != null) {
                    return answer
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - start
                Log.w(TAG, "WebRTC signaling network error for $url (attempt $attempt) after ${elapsed}ms: ${e.message}")
            }
            if (attempt < 2) {
                delay(300)
            }
        }
        return null
    }

    /** Detaches video sinks and disposes the PeerConnection. */
    fun release() {
        watchdogJob?.cancel()
        watchdogJob = null
        disconnectWatchdogJob?.cancel()
        disconnectWatchdogJob = null
        activeListener = null
        activeSurfaceRenderer = null
        stopPlayoutImmediately()
        signalingJob?.cancel()
        // v1.6.35: cancel any pending pre-negotiation and dispose
        // the prepared PC so it doesn't leak when the activity is
        // destroyed before the user taps Play.
        prepareJob?.cancel()
        prepareJob = null
        preparedPc?.let {
            try {
                it.close()
                it.dispose()
            } catch (_: Exception) {}
        }
        preparedPc = null
        preparedSdp = null
        preparedCameraId = -1L
        activeListener = null
        stopTalkback()
        try {
            localAudioTrack?.let {
                try { audioTransceiver?.sender?.setTrack(null, false) } catch (_: Throwable) {}
                it.dispose()
            }
        } catch (_: Throwable) {}
        localAudioTrack = null
        try {
            localAudioSource?.dispose()
        } catch (_: Throwable) {}
        localAudioSource = null
        audioTransceiver = null

        try {
            audioTrack?.setEnabled(false)
            audioTrack?.setVolume(0.0)
            // DO NOT call audioTrack?.dispose() here. Remote tracks received via
            // onTrack are owned by native PeerConnection/RtpReceiver; disposing them
            // manually triggers a native C++ SIGABRT double-free when peerConnection.dispose() runs!
        } catch (_: Exception) {}
        try {
            audioDeviceModule?.setSpeakerMute(true)
        } catch (_: Exception) {}
        audioTrack = null
        try {
            activeSurfaceRenderer?.let { videoTrack?.removeSink(it) }
            videoTrack?.setEnabled(false)
            // DO NOT call videoTrack?.dispose() here for the same reason.
        } catch (_: Exception) {}
        activeSurfaceRenderer = null
        videoTrack = null
        peerConnection?.let {
            try {
                it.close()
                it.dispose()
            } catch (_: Exception) {}
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
            try { vt.removeSink(surfaceRenderer) } catch (_: Exception) {}
            vt.addSink(surfaceRenderer)
            activeSurfaceRenderer = surfaceRenderer
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
        try { audioDeviceModule?.release() } catch (_: Exception) {}
        audioDeviceModule = null
        try { eglBase.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "WebRtcClient"
    }
}
