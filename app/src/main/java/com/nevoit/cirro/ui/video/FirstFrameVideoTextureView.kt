package com.nevoit.cirro.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
internal class FirstFrameVideoTextureView(
    context: Context,
    private val player: ExoPlayer,
    private val maxCaptureWidth: Int,
    private val onReadyChanged: (Boolean) -> Unit,
    private val onFirstFrame: ((Bitmap, Int) -> Unit)?,
    private val needsFrameCapture: (Int) -> Boolean,
) : TextureView(context) {
    private var released = false
    private var frameReady = false
    private val playerListener: SurfaceTextureListener
    private val listener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            frameReady = false
            onReadyChanged(false)
            playerListener.onSurfaceTextureAvailable(texture, width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            playerListener.onSurfaceTextureSizeChanged(texture, width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            frameReady = false
            onReadyChanged(false)
            return playerListener.onSurfaceTextureDestroyed(texture)
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            playerListener.onSurfaceTextureUpdated(texture)
            if (released) return
            val firstUpdate = !frameReady
            frameReady = true
            // Media3 的提交回调可能早于 TextureView 获取图像；此处才可显示并截取首帧。
            // videoSize 可能已被视频效果放大；缓存质量必须按解码输入的实际分辨率判断。
            val sourceWidth = player.videoFormat?.width ?: 0
            // 以缓存接收成功为准；首次抓图为空或写入时机过早时，下一帧仍须重试。
            if (onFirstFrame != null && sourceWidth > 0 && width > 0 && height > 0 && needsFrameCapture(sourceWidth)) {
                val captureWidth = width.coerceAtMost(maxCaptureWidth)
                val captureHeight = (height.toFloat() * captureWidth / width).roundToInt().coerceAtLeast(1)
                runCatching { getBitmap(captureWidth, captureHeight) }
                    .onSuccess { bitmap -> bitmap?.let { onFirstFrame.invoke(it, sourceWidth) } }
                    .onFailure { Log.w("PlayerMotionArtwork", "无法缓存视频首帧", it) }
            }
            if (firstUpdate) onReadyChanged(true)
        }
    }

    init {
        isOpaque = false
        player.setVideoTextureView(this)
        playerListener = checkNotNull(surfaceTextureListener)
        surfaceTextureListener = listener
    }

    fun release() {
        if (released) return
        released = true
        // 恢复 Media3 自己的 listener 后再解绑，确保它能正常移除 surface 回调。
        if (surfaceTextureListener === listener) surfaceTextureListener = playerListener
        player.clearVideoTextureView(this)
        onReadyChanged(false)
    }
}
