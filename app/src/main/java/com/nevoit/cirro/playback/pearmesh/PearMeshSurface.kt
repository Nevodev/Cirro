package com.nevoit.cirro.playback.pearmesh

import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 * 用 [AndroidEmbeddedExternalSurface] 而不是 SurfaceView 版本：它内部是 **TextureView**，
 * 作为普通 View 参与 Compose 的层级与绘制。SurfaceView 会在 window 上打洞、z 序脱离 Compose，
 * 播放页这种整层要跟着手势位移的覆盖层用它必然错位。
 *
 * 渲染跟着 `RESUMED` 走；播放页收起时这个 composable 离开组合，EGL 线程随之销毁，不留后台负载。
 */
@Composable
fun PearMeshSurface(
    state: PearMeshState,
    modifier: Modifier = Modifier,
) {
    val applicationContext = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestState = rememberUpdatedState(state)
    val renderingEnabled = remember { AtomicBoolean(false) }
    val renderThread = remember { AtomicReference<EglRenderThread?>(null) }

    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        fun updateRenderingState() {
            val enabled = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            renderingEnabled.set(enabled)
            renderThread.get()?.setRenderingEnabled(enabled)
        }

        val observer = LifecycleEventObserver { _, _ -> updateRenderingState() }
        lifecycle.addObserver(observer)
        updateRenderingState()

        onDispose {
            lifecycle.removeObserver(observer)
            renderingEnabled.set(false)
            renderThread.get()?.setRenderingEnabled(false)
        }
    }

    AndroidEmbeddedExternalSurface(
        modifier = modifier,
        isOpaque = true,
    ) {
        onSurface { surface, width, height ->
            val newRenderThread = EglRenderThread(
                context = applicationContext,
                surface = surface,
                initialWidth = width,
                initialHeight = height,
                stateProvider = { latestState.value.snapshot() },
                initiallyRenderingEnabled = renderingEnabled.get(),
                statsListener = state::setRenderDebugInfo,
                frameCaptureRequest = state.frameCaptureRequest,
            )
            renderThread.getAndSet(newRenderThread)?.stopAndJoin()

            surface.onChanged { newWidth, newHeight ->
                newRenderThread.resize(newWidth, newHeight)
            }
            surface.onDestroyed {
                renderThread.compareAndSet(newRenderThread, null)
                newRenderThread.stopAndJoin()
            }

            newRenderThread.start()
        }
    }
}
