package com.nevoit.cirro.playback

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nevoit.cirro.data.AppleAlbumRepository
import com.nevoit.cirro.data.CollectionRepository
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.manualNextIndex
import com.nevoit.cirro.data.model.manualPreviousIndex
import com.nevoit.cirro.ui.video.ArtistHeaderVideo
import com.nevoit.cirro.ui.video.PlayerMotionVideoLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    const val PanelTransitionMillis = 350
    const val FadeOutMillis = 220
    const val PanelCoverHiddenScale = 0.8f
    const val CachedFrameTransitionMillis = 120
    const val MaxVideoWidthPx = 1024
    const val WashStartFraction = 0.4f
    const val WashEndFraction = 0.8f
    const val WashAlpha = 0.8f
}

@Composable
internal fun rememberPlayerMotionArtworkSources(
    songId: Long?,
    enabled: Boolean,
    opening: Boolean,
    connection: PlaybackConnection,
): PlayerMotionSources {
    val albums = koinInject<AppleAlbumRepository>()
    val collections = koinInject<CollectionRepository>()
    val sources = remember(albums, collections, connection) { PlayerMotionSourceCache() }
    val queue by connection.queue.collectAsState()
    val queued = queue.entries.getOrNull(queue.currentIndex)?.song?.takeIf { it.id == songId }
        ?: queue.entries.firstOrNull { it.song.id == songId }?.song
    val neighbours = remember(queue) {
        listOfNotNull(
            queue.manualNextIndex(queue.currentIndex),
            queue.manualPreviousIndex(queue.currentIndex)
        )
            .distinct().mapNotNull { queue.entries.getOrNull(it)?.song }
    }
    val nextSong =
        queue.manualNextIndex(queue.currentIndex)?.let { queue.entries.getOrNull(it)?.song }
    LaunchedEffect(songId, queued, neighbours, enabled, opening, sources) {
        if (!enabled || songId == null) return@LaunchedEffect
        if (opening) sources.retryFailures()
        suspend fun load(id: Long, supplied: Song?) {
            if (sources.get(id, supplied?.albumId ?: 0) != null) return
            try {
                val song =
                    supplied?.takeIf { it.albumId > 0 } ?: collections.songMetadata(id).getOrThrow()
                sources.get(id, song.albumId)?.let { source ->
                    sources.put(id, song.albumId, source.url)
                    return
                }
                if (song.albumId <= 0) {
                    sources.put(id, 0, null)
                    return
                }
                val match = albums.cached(song.albumId) ?: run {
                    val info = collections.info(CollectionKind.ALBUM, song.albumId).getOrThrow()
                    val tracks = collections.tracks(CollectionKind.ALBUM, song.albumId).getOrThrow()
                    albums.resolve(info, tracks)
                }
                sources.put(
                    id,
                    song.albumId,
                    match?.let { albums.portraitVideo(it) },
                    failed = match == null
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                sources.put(id, 0, null, failed = true)
                Log.w("PlayerMotionArtwork", "无法获取动态封面，保留原背景", error)
            }
        }
        coroutineScope {
            launch { load(songId, queued) }
            neighbours.filter { it.id != songId }.forEach { song -> launch { load(song.id, song) } }
        }
    }
    return if (!enabled || songId == null) PlayerMotionSources(
        PlayerMotionSource(resolved = true),
        PlayerMotionSource(resolved = true)
    )
    else PlayerMotionSources(
        sources.get(songId, queued?.albumId ?: 0) ?: PlayerMotionSource(),
        sources.get(nextSong?.id, nextSong?.albumId ?: 0) ?: PlayerMotionSource(),
    )
}

internal class PlayerMotionArtworkPresentation(initialUrl: String?) {
    var url by mutableStateOf(initialUrl)
        private set
    var albumId by mutableLongStateOf(0L)
        private set
    var ready by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var reserved by mutableStateOf(initialUrl != null)

    // 已知链接在打开时就占据整页；首帧缓冲期间不能重新露出方形封面。
    val replacesCover: Boolean get() = url != null && !failed && (ready || reserved)

    fun show(url: String?, reserveCover: Boolean = false, albumId: Long = 0) {
        this.url = url
        this.albumId = albumId
        ready = false
        failed = false
        reserved = reserveCover && url != null
    }

    fun onReadyChanged(ready: Boolean) {
        this.ready = ready
    }

    fun onFailure() {
        ready = false
        failed = true
    }
}

internal data class MotionFrameHandoff(val key: PlayerMotionFrameKey, val image: ImageBitmap)

internal class PlayerMotionArtworkReveal(private val opacity: Animatable<Float, AnimationVector1D>) {
    var initialized by mutableStateOf(false)
        private set

    suspend fun show(hasImage: Boolean, hasCachedFrame: Boolean, visible: Boolean) {
        // 尚无画面时不能消耗首次展示机会；稍后恢复的缓存仍须直接接入打开动画。
        if (!initialized && hasImage && visible) {
            if (hasCachedFrame) opacity.snapTo(1f)
            initialized = true
        }
        val showing = hasImage && visible
        opacity.animateTo(
            if (showing) 1f else 0f,
            tween(if (showing) PlayerMotionArtworkDefaults.PanelTransitionMillis else PlayerMotionArtworkDefaults.FadeOutMillis),
        )
    }
}

internal class PlayerMotionArtworkHandoff(
    private val presentation: PlayerMotionArtworkPresentation,
    private val outerOpacity: Animatable<Float, AnimationVector1D>,
) {
    var committedFrame by mutableStateOf<MotionFrameHandoff?>(null)
        private set
    var frame by mutableStateOf<MotionFrameHandoff?>(null)
        private set
    var fadingOut by mutableStateOf(false)
        private set
    val blendOpacity = Animatable(0f)

    suspend fun show(target: PlayerMotionSource, incoming: MotionFrameHandoff?) {
        if (frame == null && presentation.url == target.url) {
            fadingOut = false
            return
        }
        val continuing = frame != null && frame?.key == incoming?.key
        if (!fadingOut && incoming != null && (continuing ||
                    (frame == null && presentation.url != null && outerOpacity.value > 0f))
        ) {
            if (!continuing) {
                blendOpacity.snapTo(0f)
                frame = incoming
            }
            blendOpacity.animateTo(1f, tween(PlayerMotionArtworkDefaults.TransitionMillis))
            committedFrame = incoming
            presentation.show(target.url, reserveCover = true, albumId = target.albumId)
            frame = null
            blendOpacity.snapTo(0f)
        } else {
            // 最新目标取消叠化后，让当前混合画面整体退出，不能先补完已过时的目标。
            // 外层透明度只有可见性 effect 可以写，避免 Animatable 互相取消而终止切歌收集。
            fadingOut = true
            snapshotFlow { outerOpacity.value }.first { it <= 0f }
            frame = null
            blendOpacity.snapTo(0f)
            committedFrame = incoming
            presentation.show(target.url, reserveCover = true, albumId = target.albumId)
            fadingOut = false
        }
    }
}

@Composable
internal fun PlayerMotionArtwork(
    url: String?,
    albumId: Long,
    nextUrl: String?,
    nextAlbumId: Long,
    sourceResolved: Boolean,
    frameCache: PlayerMotionFrameCache,
    active: Boolean,
    opacity: Animatable<Float, AnimationVector1D>,
    backdropWashColor: ColorProducer,
    onStateChanged: (ready: Boolean, replacesCover: Boolean, shownUrl: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = remember {
        PlayerMotionArtworkPresentation(url).apply {
            show(
                url,
                reserveCover = true,
                albumId = albumId
            )
        }
    }
    val shownUrl = presentation.url
    val reportState by rememberUpdatedState(onStateChanged)
    val request by rememberUpdatedState(
        if (sourceResolved) PlayerMotionSource(
            true,
            url,
            albumId
        ) else PlayerMotionSource(resolved = true),
    )
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val context = LocalContext.current.applicationContext
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
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val handoff =
            remember(presentation, opacity) { PlayerMotionArtworkHandoff(presentation, opacity) }
        val shownAlbumId = presentation.albumId
        val frameKey = remember(shownUrl, shownAlbumId, widthPx, heightPx, layout) {
            shownUrl?.let { PlayerMotionFrameKey(it, widthPx, heightPx, layout, shownAlbumId) }
        }
        val nextKey = remember(nextUrl, nextAlbumId, widthPx, heightPx, layout) {
            nextUrl?.let { PlayerMotionFrameKey(it, widthPx, heightPx, layout, nextAlbumId) }
        }
        LaunchedEffect(frameCache, nextKey, lifecycle, sourceResolved) {
            if (!lifecycle.isAtLeast(Lifecycle.State.RESUMED) || !sourceResolved || nextKey == null || nextKey.url == url) return@LaunchedEffect
            frameCache.restore(nextKey)
            if (frameCache.needsRefresh(nextKey)) {
                prefetchPlayerMotionFrame(context, nextKey)?.let {
                    frameCache.put(
                        nextKey,
                        it.image,
                        it.sourceWidth
                    )
                }
            }
        }
        // 只在进入此画面时取缓存；本次捕获的首帧不能打断首次加载的淡入。
        var cachedFrame by remember(frameCache, frameKey) {
            mutableStateOf(
                handoff.committedFrame?.takeIf { it.key == frameKey }?.image ?: frameKey?.let(
                    frameCache::get
                )
            )
        }
        var cacheResolved by remember(
            frameCache,
            frameKey
        ) { mutableStateOf(frameKey == null || cachedFrame != null) }
        LaunchedEffect(frameCache, frameKey) {
            if (!cacheResolved) {
                cachedFrame = frameKey?.let { frameCache.restore(it) }
                cacheResolved = true
            }
        }
        var ready by remember(frameKey) { mutableStateOf(false) }
        val reveal = remember(opacity) { PlayerMotionArtworkReveal(opacity) }
        val hasImage = ready || cachedFrame != null
        val replacesCover = presentation.replacesCover || cachedFrame != null
        SideEffect { reportState(hasImage, replacesCover, shownUrl) }
        LaunchedEffect(frameCache, widthPx, heightPx, layout) {
            snapshotFlow { request }.collectLatest { target ->
                val incomingKey = target.url?.let {
                    PlayerMotionFrameKey(
                        it,
                        widthPx,
                        heightPx,
                        layout,
                        target.albumId
                    )
                }
                val incoming = incomingKey?.let { frameCache.get(it) ?: frameCache.restore(it) }
                handoff.show(
                    target,
                    if (incomingKey != null && incoming != null) MotionFrameHandoff(
                        incomingKey,
                        incoming
                    ) else null
                )
            }
        }
        LaunchedEffect(
            shownUrl,
            hasImage,
            replacesCover,
            active,
            cacheResolved,
            handoff.fadingOut
        ) {
            if (cacheResolved) {
                reveal.show(
                    hasImage = hasImage,
                    hasCachedFrame = cachedFrame != null,
                    visible = replacesCover && active && !handoff.fadingOut,
                )
            }
        }
        val effectiveOpacity by remember(hasImage, cachedFrame, active) {
            derivedStateOf {
                if (!hasImage) 0f else if (!reveal.initialized && cachedFrame != null && active && !handoff.fadingOut) 1f else opacity.value
            }
        }
        val videoOpacity = key(frameKey) {
            if (cachedFrame != null) animateFloatAsState(
                targetValue = if (ready) 1f else 0f,
                animationSpec = tween(PlayerMotionArtworkDefaults.CachedFrameTransitionMillis),
                label = "PlayerCachedFrameHandoff",
            ) else rememberUpdatedState(if (ready) 1f else 0f)
        }
        val playing by remember(active) {
            derivedStateOf { active || opacity.value > 0f }
        }
        Box(
            Modifier
                .fillMaxSize()
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
                    onDrawWithContent { drawContent(); drawRect(wash) }
                },
        ) {
            cachedFrame?.let { frame ->
                Image(
                    bitmap = frame,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            if (!ready || videoOpacity.value < 1f) drawContent()
                        },
                )
            }
            shownUrl?.let { video ->
                if (cacheResolved) key(video) {
                    ArtistHeaderVideo(
                        url = video,
                        riseRatio = 0f,
                        referenceSide = widthPx.coerceIn(
                            1,
                            PlayerMotionArtworkDefaults.MaxVideoWidthPx
                        ),
                        isVisible = playing,
                        fitSourceWidth = true,
                        matchStaticCoverBlur = true,
                        playerLayout = layout,
                        revealDurationMillis = 0,
                        onReadyChanged = {
                            if (presentation.url == video) {
                                ready = it; presentation.onReadyChanged(it)
                            }
                        },
                        onFailure = { if (presentation.url == video) presentation.onFailure() },
                        onFirstFrame = if (frameKey != null) { bitmap, sourceWidth ->
                            frameCache.put(frameKey, bitmap.asImageBitmap(), sourceWidth)
                        } else null,
                        needsFrameCapture = { width ->
                            frameKey != null && frameCache.needsCapture(
                                frameKey,
                                width
                            )
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (ready) videoOpacity.value else 0f },
                    )
                }
            }
            handoff.frame?.let { frame ->
                Image(
                    bitmap = frame.image,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = handoff.blendOpacity.value },
                )
            }
        }
    }
}
