package com.nevoit.cirro.ui.video

import android.util.Log
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource

@OptIn(UnstableApi::class)
@Composable
internal fun ArtistHeaderVideo(
    url: String,
    riseRatio: Float,
    referenceSide: Int,
    isVisible: Boolean = true,
    modifier: Modifier = Modifier,
    fitSourceWidth: Boolean = false,
    bleedRatio: Float = 0.5f,
    matchStaticCoverBlur: Boolean = false,
    playerLayout: PlayerMotionVideoLayout? = null,
    revealDurationMillis: Int = 500,
    onReadyChanged: (Boolean) -> Unit = {},
    onFailure: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reportReady by rememberUpdatedState(onReadyChanged)
    val reportFailure by rememberUpdatedState(onFailure)
    val player = remember(context, url, riseRatio, referenceSide, lifecycle, fitSourceWidth, bleedRatio, matchStaticCoverBlur, playerLayout) {
        runCatching {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setMaxVideoSize(referenceSide, if (fitSourceWidth) Int.MAX_VALUE else referenceSide)
                    .build()
            }
        }.onFailure { Log.w("ArtistVideo", "无法初始化艺人视频", it) }.getOrNull()
    }
    if (player == null) {
        LaunchedEffect(url, playerLayout) { reportFailure() }
        return
    }
    var firstFrame by remember(player) { mutableStateOf(false) }
    var failed by remember(player) { mutableStateOf(false) }
    if (failed) return

    val videoAlpha by key(player) {
        animateFloatAsState(
            targetValue = if (firstFrame) 1f else 0f,
            animationSpec = tween(revealDurationMillis),
            label = "artistVideoAlpha",
        )
    }

    // LazyColumn 回收头部会连同 TextureView 和首帧状态一起销毁；保留这一项才能在滚回时沿用原画面。
    val pinnableContainer = LocalPinnableContainer.current
    DisposableEffect(pinnableContainer) {
        val handle = pinnableContainer?.pin()
        onDispose { handle?.release() }
    }
    val visible by rememberUpdatedState(isVisible)
    SideEffect {
        player.playWhenReady = visible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    key(player) {
        AndroidView(
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    isOpaque = false
                    player.setVideoTextureView(this)
                }
            },
            modifier = modifier.graphicsLayer { alpha = videoAlpha },
            onRelease = { player.clearVideoTextureView(it) },
        )
    }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true; reportReady(true) }
            override fun onPlayerError(error: PlaybackException) {
                Log.w("ArtistVideo", "艺人视频播放失败，保留封面", error)
                failed = true
                reportReady(false)
                reportFailure()
            }
        }
        val observer = LifecycleEventObserver { _, _ ->
            player.playWhenReady = visible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        try {
            player.setVideoEffects(listOf(ArtistReflectionEffect(
                riseRatio, referenceSide, fitSourceWidth, bleedRatio, matchStaticCoverBlur, playerLayout,
            )))
            val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            player.setMediaSource(HlsMediaSource.Factory(DefaultHttpDataSource.Factory()).createMediaSource(item))
            player.playWhenReady = visible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            player.prepare()
        } catch (error: Exception) {
            Log.w("ArtistVideo", "无法准备艺人视频，保留封面", error)
            failed = true
            reportReady(false)
            reportFailure()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
            reportReady(false)
        }
    }
}
