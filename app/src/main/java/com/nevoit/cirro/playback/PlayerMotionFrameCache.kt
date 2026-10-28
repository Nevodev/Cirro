package com.nevoit.cirro.playback

import androidx.compose.ui.graphics.ImageBitmap
import com.nevoit.cirro.ui.video.PlayerMotionVideoLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PlayerMotionFrameKey(
    val url: String,
    val width: Int,
    val height: Int,
    val layout: PlayerMotionVideoLayout,
    val albumId: Long = 0,
)

internal data class PlayerMotionCachedFrame(
    val image: ImageBitmap,
    val sourceWidth: Int,
    val capturedAtMillis: Long,
) {
    companion object {
        const val RefreshAfterMillis = 7L * 24 * 60 * 60 * 1000
    }
}

internal class PlayerMotionFrameCache(
    private val scope: CoroutineScope? = null,
    private val read: (PlayerMotionFrameKey) -> PlayerMotionCachedFrame? = { null },
    private val write: (PlayerMotionFrameKey, PlayerMotionCachedFrame) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val diskMutex = Mutex()
    private var selectedUrl: String? = null
    private var nextUrl: String? = null
    private val images = LinkedHashMap<PlayerMotionFrameKey, PlayerMotionCachedFrame>(4, 0.75f, true)

    fun select(url: String?, nextUrl: String? = null) {
        selectedUrl = url
        this.nextUrl = nextUrl
        if (url == null && nextUrl == null) images.clear()
    }

    fun get(key: PlayerMotionFrameKey): ImageBitmap? = images[key]?.image

    fun needsRefresh(key: PlayerMotionFrameKey): Boolean = images[key]?.let {
        it.sourceWidth <= 0 || it.capturedAtMillis <= 0 ||
            now() - it.capturedAtMillis >= PlayerMotionCachedFrame.RefreshAfterMillis
    } ?: true

    fun needsCapture(key: PlayerMotionFrameKey, sourceWidth: Int): Boolean = images[key]?.let {
        sourceWidth > it.sourceWidth || (sourceWidth >= it.sourceWidth && needsRefresh(key))
    } ?: true

    suspend fun restore(key: PlayerMotionFrameKey): ImageBitmap? {
        get(key)?.let { return it }
        val restored = withContext(Dispatchers.IO) { diskMutex.withLock { read(key) } } ?: return null
        if (!accepts(key)) return null
        get(key)?.let { return it }
        remember(key, restored)
        return restored.image
    }

    fun put(key: PlayerMotionFrameKey, image: ImageBitmap, sourceWidth: Int = image.width) {
        if (!accepts(key) || !needsCapture(key, sourceWidth)) return
        val frame = PlayerMotionCachedFrame(image, sourceWidth, now())
        remember(key, frame)
        scope?.launch(Dispatchers.IO) { diskMutex.withLock { write(key, frame) } }
    }

    private fun accepts(key: PlayerMotionFrameKey) = key.url == selectedUrl || key.url == nextUrl

    private fun remember(key: PlayerMotionFrameKey, frame: PlayerMotionCachedFrame) {
        images[key] = frame
        while (images.size > 3) images.remove(images.keys.first())
    }
}
