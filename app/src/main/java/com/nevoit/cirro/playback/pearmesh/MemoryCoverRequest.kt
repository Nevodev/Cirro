package com.nevoit.cirro.playback.pearmesh

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nevoit.cirro.ui.ReflectionTransformation
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.cirro.playback.pearmesh.gl.PearMeshCoverPreset
import kotlin.math.roundToInt

internal class MemoryCoverRequest(
    private val context: Context,
    source: String,
    width: Int,
    height: Int,
    reflection: ReflectionTransformation,
    private val crossfadeFromDisk: Boolean = false,
) {
    private val transformation = MemoryCoverTransformation(context, source, reflection)
    private val key = MemoryCache.Key("${transformation.cacheKey}:$width:$height")
    private val request = ImageRequest.Builder(context)
        .data(source)
        // 成品固定为预设分辨率，布局像素更大不能让 Coil 把它判成尺寸不足而重新加载。
        .size(PearMeshCoverPreset.Side, (PearMeshCoverPreset.Side.toDouble() * height / width).roundToInt())
        .transformations(transformation)
        // 数据可能从 URL 变成本地 Bitmap，但成品的身份、尺寸和变换参数没有改变。
        .memoryCacheKey(key)
        .crossfade(true)
        .build()

    fun memoryCached(): ImageRequest? = request.takeIf { context.imageLoader.memoryCache?.get(key) != null }

    suspend fun load(): ImageRequest {
        memoryCached()?.let { return it }
        val cached = transformation.cachedImage()
        if (cached == null) {
            val square = transformation.cachedSquare() ?: return request
            return request.newBuilder().data(square).memoryCacheKey(key).build()
        }
        return request.newBuilder()
            .data(cached)
            .transformations(emptyList())
            .memoryCacheKey(key)
            .crossfade(crossfadeFromDisk)
            .build()
    }
}

internal fun memoryCoverPaletteKey(context: Context, source: String): String =
    if (source.isEmpty()) "" else MemoryCoverTransformation(context, source).paletteKey

internal suspend fun prepareMemoryCoverPalette(context: Context, source: String, palette: CoverPalette) {
    if (source.isEmpty()) return
    val square = MemoryCoverTransformation(context, source)
    if (palette.seed(square.paletteKey) != null) return
    context.imageLoader.execute(
        ImageRequest.Builder(context)
            .data(source)
            .size(PearMeshCoverPreset.Side)
            .memoryCacheKey(square.cacheKey)
            .transformations(square)
            .build(),
    )
    palette.seed(square.paletteKey)
}

@Composable
internal fun rememberMemoryCoverRequest(
    source: String,
    width: Int,
    height: Int,
    reflection: ReflectionTransformation,
    crossfadeFromDisk: Boolean = false,
): ImageRequest? {
    val context = LocalContext.current
    val loader = remember(context, source, width, height, reflection.cacheKey, crossfadeFromDisk) {
        MemoryCoverRequest(context, source, width, height, reflection, crossfadeFromDisk)
    }
    val request by key(loader) {
        produceState(loader.memoryCached()) {
            value = loader.load()
        }
    }
    return request
}
