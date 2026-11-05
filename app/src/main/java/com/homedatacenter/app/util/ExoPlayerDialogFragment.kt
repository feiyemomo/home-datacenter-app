package com.homedatacenter.app.util

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.DialogFragment
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory

/**
 * Pure Jetpack Compose ExoPlayer dialog.
 * Eliminates legacy fragment_exo_player.xml.
 */
class ExoPlayerDialogFragment : DialogFragment() {

    private var player: ExoPlayer? = null
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        arguments?.let { args ->
            cameraName = args.getString("cameraName")
            hlsUrl = args.getString("hlsUrl")
            token = args.getString("token")
        }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                ExoPlayerScreen(
                    cameraName = cameraName ?: "摄像头",
                    hlsUrl = hlsUrl,
                    token = token,
                    onClose = { dismiss() },
                    onInitPlayer = { playerView ->
                        hlsUrl?.let { initPlayer(it, playerView) }
                    }
                )
            }
        }
    }

    private fun initPlayer(url: String, playerView: StyledPlayerView) {
        val ctx = context ?: return
        android.util.Log.d("ExoPlayer", "Initializing player for: $url")

        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent(NetworkFactory.USER_AGENT)
            setConnectTimeoutMs(10000)
            setReadTimeoutMs(30000)
            setAllowCrossProtocolRedirects(true)
            if (!token.isNullOrEmpty()) {
                setDefaultRequestProperties(mutableMapOf("Authorization" to "Bearer $token"))
            }
        }

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(1_500)
                    .build()
            )
            .build()

        val mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs= */ 1_000,
                /* maxBufferMs= */ 3_000,
                /* bufferForPlaybackMs= */ 500,
                /* bufferForPlaybackAfterRebufferMs= */ 1_000,
            )
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = ExoPlayerRendererFactory.create(ctx)
        val p = ExoPlayer.Builder(ctx, renderersFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setMediaSource(mediaSource)
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.e("ExoPlayer", "Player error: ${error.message}, code: ${error.errorCode}")
                        handleError(error, playerView)
                    }
                })
            }

        // Surface Order: playerView.player MUST be set before calling prepare()
        playerView.player = p
        p.prepare()
        playerView.setShowBuffering(StyledPlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        player = p
    }

    private fun handleError(error: PlaybackException, playerView: StyledPlayerView) {
        retryCount++
        android.util.Log.w("ExoPlayer", "Error retry $retryCount/$maxRetries: ${error.message}")

        if (retryCount <= maxRetries) {
            player?.stop()
            player?.release()
            player = null

            view?.postDelayed({
                hlsUrl?.let { initPlayer(it, playerView) }
            }, retryCount * 2000L)
        } else {
            Toast.makeText(context, "ExoPlayer 播放失败，尝试备用播放器...", Toast.LENGTH_LONG).show()
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

@Composable
fun ExoPlayerScreen(
    cameraName: String,
    hlsUrl: String?,
    token: String?,
    onClose: () -> Unit,
    onInitPlayer: (StyledPlayerView) -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                StyledPlayerView(ctx).apply {
                    useController = true
                    setShowSubtitleButton(false)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    onInitPlayer(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0x990F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x3338BDF8))
            ) {
                Text(
                    text = cameraName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0x990F172A), CircleShape)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_close),
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
