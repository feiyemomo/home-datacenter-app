package com.homedatacenter.app.util

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.homedatacenter.app.R

class ExoPlayerDialogFragment : DialogFragment() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: StyledPlayerView
    private var cameraName: String? = null
    private var hlsUrl: String? = null
    private var token: String? = null
    private var retryCount = 0
    private val maxRetries = 3
    private var onPlaybackFailed: (() -> Unit)? = null

    companion object {
        fun newInstance(
            cameraName: String,
            hlsUrl: String,
            token: String?,
            onPlaybackFailed: (() -> Unit)? = null
        ): ExoPlayerDialogFragment {
            return ExoPlayerDialogFragment().apply {
                this.onPlaybackFailed = onPlaybackFailed
                arguments = Bundle().apply {
                    putString("cameraName", cameraName)
                    putString("hlsUrl", hlsUrl)
                    putString("token", token)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?) =
        super.onCreateDialog(savedInstanceState).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawableResource(android.R.color.black)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_exo_player, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.let { args ->
            cameraName = args.getString("cameraName")
            hlsUrl = args.getString("hlsUrl")
            token = args.getString("token")
        }

        playerView = view.findViewById(R.id.player_view)
        val btnClose = view.findViewById<Button>(R.id.btn_close)
        val tvCameraName = view.findViewById<TextView>(R.id.tv_camera_name)

        tvCameraName.text = cameraName ?: "Camera"

        btnClose.setOnClickListener {
            dismiss()
        }

        hlsUrl?.let { initPlayer(it) }
    }

    private fun initPlayer(url: String) {
        android.util.Log.d("ExoPlayer", "Initializing player for: $url")

        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setConnectTimeoutMs(10000)
            setReadTimeoutMs(30000)
            setAllowCrossProtocolRedirects(true)
            if (!token.isNullOrEmpty()) {
                setDefaultRequestProperties(
                    mutableMapOf("Authorization" to "Bearer $token")
                )
            }
        }

        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(url))

        // Delegate to ExoPlayerRendererFactory: filters out broken
        // goldfish decoders on emulators, enables decoder fallback on
        // real devices. See util/ExoPlayerRendererFactory.kt.
        val renderersFactory = ExoPlayerRendererFactory.create(requireContext())
        player = ExoPlayer.Builder(requireContext(), renderersFactory).build().apply {
            setMediaSource(mediaSource)
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            android.util.Log.d("ExoPlayer", "Buffering...")
                        }
                        Player.STATE_READY -> {
                            android.util.Log.d("ExoPlayer", "Ready, latency: ${currentPosition}ms")
                        }
                        Player.STATE_ENDED -> {
                            android.util.Log.d("ExoPlayer", "Playback ended")
                        }
                        Player.STATE_IDLE -> {
                            android.util.Log.d("ExoPlayer", "Idle")
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("ExoPlayer", "Player error: ${error.message}, code: ${error.errorCode}")
                    handleError(error)
                }
            })

            prepare()
        }

        playerView.player = player
        playerView.setShowBuffering(StyledPlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    }

    private fun handleError(error: PlaybackException) {
        retryCount++
        android.util.Log.w("ExoPlayer", "Error retry $retryCount/$maxRetries: ${error.message}")

        if (retryCount <= maxRetries) {
            player?.stop()
            player?.release()
            player = null

            requireView().postDelayed({
                hlsUrl?.let { initPlayer(it) }
            }, retryCount * 2000L)
        } else {
            Toast.makeText(context, "ExoPlayer 播放失败，尝试备用播放器...", Toast.LENGTH_LONG).show()
            android.util.Log.e("ExoPlayer", "Max retries reached, falling back to WebView")
            onPlaybackFailed?.invoke()
            dismiss()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
    }
}
