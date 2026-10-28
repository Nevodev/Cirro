package com.nevoit.cirro.playback

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.nevoit.cirro.data.AppleAlbumRepository
import com.nevoit.cirro.data.CollectionRepository
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.ui.video.ArtistHeaderVideo
import com.nevoit.cirro.ui.video.PlayerMotionVideoLayout
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

internal object PlayerMotionArtworkDefaults {
    // 圆心固定在屏幕顶部正中，圆底按封面自然高度计；起止值是此圆半径的倍数。
    const val BlurCircleBottomFraction = 1f

    // 只放大横向半轴，保持底部及中心线上的模糊起止位置不动；1f 为正圆。
    const val BlurHorizontalScale = 1.1f
    const val BlurStartFraction = 0.9f
    const val BlurEndFraction = 1.2f
    const val ReflectionSourceFraction = 1f / 3f
    const val BlurRadiusPx = 100f
    const val TransitionMillis = 300
    const val MaxVideoWidthPx = 1024
    const val WashStartFraction = 0.4f
    const val WashEndFraction = 0.8f
    const val WashAlpha = 0.8f
}

@Composable
internal fun rememberPlayerMotionArtworkUrl(
    songId: Long?,
    enabled: Boolean,
    opening: Boolean,
    connection: PlaybackConnection,
): String? {
    val albums = koinInject<AppleAlbumRepository>()
    val collections = koinInject<CollectionRepository>()
    var url by remember(songId, enabled, connection) { mutableStateOf<String?>(null) }
    var requested by remember(songId, enabled, connection) { mutableStateOf(false) }
    LaunchedEffect(songId, enabled, opening, albums, collections, connection) {
        if (!enabled || songId == null || url != null || (!opening && requested)) return@LaunchedEffect
        requested = true
        try {
            val queue = connection.queue.value
            val queued =
                queue.entries.getOrNull(queue.currentIndex)?.song?.takeIf { it.id == songId && it.albumId > 0 }
            val song = queued ?: collections.songMetadata(songId).getOrThrow()
            if (song.albumId <= 0) return@LaunchedEffect
            val match = albums.cached(song.albumId) ?: run {
                val info = collections.info(CollectionKind.ALBUM, song.albumId).getOrThrow()
                val tracks = collections.tracks(CollectionKind.ALBUM, song.albumId).getOrThrow()
                albums.resolve(info, tracks)
            }
            url = match?.let { albums.portraitVideo(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("PlayerMotionArtwork", "无法获取动态封面，保留原背景", error)
        }
    }
    return url
}

internal class PlayerMotionArtworkPresentation(private val initialUrl: String?) {
    var url by mutableStateOf(initialUrl)
        private set
    var ready by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    // 已知链接在打开时就占据整页；首帧缓冲期间不能重新露出方形封面。
    val replacesCover: Boolean get() = url != null && !failed && (ready || url == initialUrl)

    fun show(url: String?) {
        this.url = url
        ready = false
        failed = false
    }

    fun onReadyChanged(ready: Boolean) {
        this.ready = ready
    }

    fun onFailure() {
        ready = false
        failed = true
    }
}

@Composable
internal fun PlayerMotionArtwork(
    url: String?,
    active: Boolean,
    isPlaying: Boolean,
    opacity: Animatable<Float, AnimationVector1D>,
    backdropWashColor: ColorProducer,
    onStateChanged: (ready: Boolean, replacesCover: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = remember { PlayerMotionArtworkPresentation(url) }
    val shownUrl = presentation.url
    val ready = presentation.ready
    val replacesCover = presentation.replacesCover
    val reportState by rememberUpdatedState(onStateChanged)
    SideEffect { reportState(ready, replacesCover) }
    LaunchedEffect(url) {
        if (shownUrl != url) {
            if (opacity.value > 0f) opacity.animateTo(
                0f,
                tween(PlayerMotionArtworkDefaults.TransitionMillis)
            )
            presentation.show(url)
        }
    }
    LaunchedEffect(url, shownUrl, replacesCover, active) {
        if (url == shownUrl) {
            opacity.animateTo(
                if (replacesCover && active) 1f else 0f,
                tween(PlayerMotionArtworkDefaults.TransitionMillis)
            )
        }
    }
    // 错误或 surface 销毁后不能用仍未归零的淡出值让旧画面继续遮住回退背景。
    val effectiveOpacity by remember(ready) { derivedStateOf { if (ready) opacity.value else 0f } }
    val playing by remember(active, isPlaying) {
        derivedStateOf { isPlaying && (active || opacity.value > 0f) }
    }
    BoxWithConstraints(
        modifier
            .graphicsLayer { alpha = effectiveOpacity }
            .drawWithCache {
                val color = backdropWashColor()
                // 多个色标逼近 mesh 的 smoothstep 曲线，兼容不支持 RuntimeShader 的系统。
                val wash = Brush.verticalGradient(
                    *Array(33) { index ->
                        val t = index / 32f
                        t to color.copy(alpha = t * t * (3f - 2f * t) * PlayerMotionArtworkDefaults.WashAlpha)
                    },
                    startY = size.height * PlayerMotionArtworkDefaults.WashStartFraction,
                    endY = size.height * PlayerMotionArtworkDefaults.WashEndFraction,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(wash)
                }
            },
    ) {
        val density = LocalDensity.current
        val layout = remember(maxWidth, maxHeight) {
            PlayerMotionVideoLayout(
                heightToWidth = (maxHeight.value / maxWidth.value).coerceAtLeast(0.01f),
                blurStartFraction = PlayerMotionArtworkDefaults.BlurStartFraction,
                blurEndFraction = PlayerMotionArtworkDefaults.BlurEndFraction,
                reflectionSourceFraction = PlayerMotionArtworkDefaults.ReflectionSourceFraction,
                blurRadiusPx = PlayerMotionArtworkDefaults.BlurRadiusPx,
                blurCircleBottomFraction = PlayerMotionArtworkDefaults.BlurCircleBottomFraction,
                blurHorizontalScale = PlayerMotionArtworkDefaults.BlurHorizontalScale,
            )
        }
        shownUrl?.let { video ->
            key(video) {
                ArtistHeaderVideo(
                    url = video,
                    riseRatio = 0f,
                    referenceSide = with(density) { maxWidth.roundToPx() }
                        .coerceIn(1, PlayerMotionArtworkDefaults.MaxVideoWidthPx),
                    isVisible = playing,
                    fitSourceWidth = true,
                    matchStaticCoverBlur = true,
                    playerLayout = layout,
                    revealDurationMillis = 0,
                    onReadyChanged = { if (presentation.url == video) presentation.onReadyChanged(it) },
                    onFailure = { if (presentation.url == video) presentation.onFailure() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
