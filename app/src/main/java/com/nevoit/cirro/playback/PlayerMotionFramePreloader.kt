package com.nevoit.cirro.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.nevoit.cirro.ui.video.ArtistReflectionEffect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal suspend fun prefetchPlayerMotionFrame(context: Context, key: PlayerMotionFrameKey): PlayerMotionCachedFrame? =
    withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { continuation ->
            val preloader = MotionFramePreloader(context, key) { image ->
                if (continuation.isActive) continuation.resume(image)
            }
            continuation.invokeOnCancellation { preloader.release() }
            preloader.start()
        }
    }

@OptIn(UnstableApi::class)
private class MotionFramePreloader(
    private val context: Context,
    private val key: PlayerMotionFrameKey,
    private val completed: (PlayerMotionCachedFrame?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val width = key.width.coerceIn(1, PlayerMotionArtworkDefaults.MaxVideoWidthPx)
    private val height = (width * key.layout.heightToWidth).roundToInt().coerceAtLeast(1)
    private val texture = SurfaceTexture(false).apply { setDefaultBufferSize(width, height) }
    private val surface = Surface(texture)
    private var player: ExoPlayer? = null
    private var released = false
    private var copying = false

    fun start() {
        if (released) return
        try {
            val preparedPlayer = ExoPlayer.Builder(context).build()
            player = preparedPlayer
            preparedPlayer.apply {
                volume = 0f
                playWhenReady = false
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setForceHighestSupportedBitrate(true)
                    .setMaxVideoSize(width, Int.MAX_VALUE).build()
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        if (!released && !copying) copyFrame()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w("PlayerMotionArtwork", "下一首首帧预取失败", error)
                        finish(null)
                    }
                })
                setVideoEffects(listOf(ArtistReflectionEffect(
                    riseRatio = 0f,
                    referenceSide = width,
                    fitSourceWidth = true,
                    matchStaticCoverBlur = true,
                    playerLayout = key.layout,
                )))
                setVideoSurfaceHolder(FrameSurface(surface, Rect(0, 0, width, height)))
                val item = MediaItem.Builder().setUri(key.url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                setMediaSource(HlsMediaSource.Factory(DefaultHttpDataSource.Factory()).createMediaSource(item))
                prepare()
            }
        } catch (error: Exception) {
            Log.w("PlayerMotionArtwork", "无法准备下一首首帧", error)
            finish(null)
        }
    }

    private fun copyFrame(attempt: Int = 0) {
        if (released) return
        copying = true
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            // 预取没有可见 View；直接复制已提交到离屏 Surface 的处理结果。
            PixelCopy.request(surface, bitmap, { result ->
                if (released) bitmap.recycle()
                else if (result == PixelCopy.SUCCESS) {
                    val sourceWidth = player?.videoFormat?.width ?: 0
                    if (sourceWidth > 0) finish(PlayerMotionCachedFrame(
                        bitmap.asImageBitmap(), sourceWidth, System.currentTimeMillis(),
                    )) else {
                        bitmap.recycle()
                        finish(null)
                    }
                }
                else {
                    bitmap.recycle()
                    if (result == PixelCopy.ERROR_SOURCE_NO_DATA && attempt < 2) {
                        handler.postDelayed({ copyFrame(attempt + 1) }, 50)
                    } else finish(null)
                }
            }, handler)
        } catch (error: Exception) {
            bitmap.recycle()
            Log.w("PlayerMotionArtwork", "无法复制下一首首帧", error)
            finish(null)
        }
    }

    private fun finish(image: PlayerMotionCachedFrame?) {
        if (released) return
        release()
        completed(image)
    }

    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { release() }
            return
        }
        if (released) return
        released = true
        player?.release()
        player = null
        surface.release()
        texture.release()
    }

    private class FrameSurface(private val surface: Surface, private val bounds: Rect) : SurfaceHolder {
        override fun getSurface() = surface
        override fun getSurfaceFrame() = bounds
        override fun addCallback(callback: SurfaceHolder.Callback) = Unit
        override fun removeCallback(callback: SurfaceHolder.Callback) = Unit
        override fun isCreating() = false
        @Deprecated("Deprecated by SurfaceHolder")
        override fun setType(type: Int) = Unit
        override fun setSizeFromLayout() = Unit
        override fun setFixedSize(width: Int, height: Int) = Unit
        override fun setFormat(format: Int) = Unit
        override fun setKeepScreenOn(screenOn: Boolean) = Unit
        override fun lockCanvas(): Canvas? = null
        override fun lockCanvas(dirty: Rect?): Canvas? = null
        override fun unlockCanvasAndPost(canvas: Canvas) = Unit
    }
}
