package com.nevoit.cirro.playback.pearmesh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nevoit.cirro.playback.pearmesh.gl.EglRenderThread
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 由封面驱动的网格渐变背景。（渲染器来自 PearWall 的 PearMesh，Apache-2.0。）
 *
 * 渲染跑在自建的 EGL 线程上、画进自己的 surface，所以**组合期不参与任何绘制**：它不读进度、
 * 不每帧重组，每帧只取一次 [PearMeshState] 快照。放在播放页当背景，对 Compose 的帧预算
 * 几乎零占用。
 *
 * 使用 [TextureView] 作为普通 View 参与 Compose 的层级与绘制。
 * SurfaceView 会在 window 上打洞、z 序脱离 Compose，
 * 播放页这种整层要跟着手势位移的覆盖层用它必然错位。
 *
 * 渲染跟着 `RESUMED` 走；播放页收起时这个 composable 离开组合，EGL 线程随之销毁，不留后台负载。
 */
@Composable
fun PearMeshSurface(
    state: PearMeshState,
    modifier: Modifier = Modifier,
    artwork: Bitmap? = null,
    onFrameReady: (Boolean) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestFrameReady = rememberUpdatedState(onFrameReady)
    key(state) {
        val renderingEnabled = remember { AtomicBoolean(false) }
        val view = remember { AtomicReference<PearMeshTextureView?>(null) }
        DisposableEffect(lifecycleOwner, view) {
            val lifecycle = lifecycleOwner.lifecycle
            fun updateRenderingState() {
                val enabled = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                renderingEnabled.set(enabled)
                view.get()?.setRenderingEnabled(enabled)
            }

            val observer = LifecycleEventObserver { _, _ -> updateRenderingState() }
            lifecycle.addObserver(observer)
            updateRenderingState()

            onDispose {
                lifecycle.removeObserver(observer)
                renderingEnabled.set(false)
                view.get()?.setRenderingEnabled(false)
            }
        }

        AndroidView(
            factory = { context ->
                PearMeshTextureView(context, state) { latestFrameReady.value(it) }.apply {
                    setArtwork(artwork)
                    setRenderingEnabled(renderingEnabled.get())
                    view.set(this)
                }
            },
            modifier = modifier,
            onRelease = {
                view.compareAndSet(it, null)
                it.release()
            },
            update = { it.setArtwork(artwork) },
        )
    }
}

internal class PearMeshTextureView(
    context: Context,
    private val state: PearMeshState,
    private val onFrameReady: (Boolean) -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var renderThread: EglRenderThread? = null
    private var renderSurface: Surface? = null
    private var artwork: Bitmap? = null
    private var renderingEnabled = false
    private var frameReady = false
    private var released = false

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    fun setArtwork(bitmap: Bitmap?) {
        if (bitmap == null || artwork === bitmap) return
        artwork = bitmap
        // This runs before surface creation, so the renderer's initial snapshot already has artwork.
        state.setArtwork(bitmap)
    }

    fun setRenderingEnabled(enabled: Boolean) {
        renderingEnabled = enabled
        renderThread?.setRenderingEnabled(enabled)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        stopRenderer()
        frameReady = false
        alpha = 1f
        onFrameReady(false)
        val surface = Surface(texture)
        renderSurface = surface
        renderThread = EglRenderThread(
            context = context.applicationContext,
            surface = surface,
            initialWidth = width,
            initialHeight = height,
            stateProvider = state::snapshot,
            initiallyRenderingEnabled = renderingEnabled,
            statsListener = state::setRenderDebugInfo,
            frameCaptureRequest = state.frameCaptureRequest,
            onRenderError = {
                post {
                    if (!released && renderSurface === surface) {
                        // A failed GL context must not hold the opening animation indefinitely.
                        alpha = 0f
                        frameReady = true
                        onFrameReady(true)
                    }
                }
            },
        ).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        renderThread?.resize(width, height)
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (!released && !frameReady) {
            // Buffer submission alone does not mean TextureView has acquired the first image.
            frameReady = true
            onFrameReady(true)
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        stopRenderer()
        return true
    }

    fun release() {
        released = true
        stopRenderer()
    }

    private fun stopRenderer() {
        renderThread?.stopAndJoin()
        renderThread = null
        renderSurface?.release()
        renderSurface = null
    }
}
