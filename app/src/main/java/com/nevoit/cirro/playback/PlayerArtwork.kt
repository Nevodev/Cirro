package com.nevoit.cirro.playback

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** 背景始终模糊，限制纹理大小以减少解码与上传开销。 */
private const val CoverTextureSize = 512

internal class PlayerArtwork(val cover: String, val bitmap: Bitmap?)

@Composable
internal fun PreparePlayerArtwork(expansion: PlayerExpansionState?, cover: String) {
    val context = LocalContext.current
    LaunchedEffect(expansion, cover, context) {
        if (expansion == null) return@LaunchedEffect
        expansion.artwork = null
        val bitmap = try {
            val image = if (cover.isEmpty()) null else {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(cover)
                        .size(CoverTextureSize)
                        // GLUtils.texImage2D cannot upload Coil's hardware bitmaps.
                        .allowHardware(false)
                        .build()
                )
                (result as? SuccessResult)?.image
            }
            withContext(Dispatchers.Default) {
                // The renderer retains the image across song transitions and surface recreation;
                // keep its software bitmap independent of Coil's cached image lifetime.
                image?.toBitmap(image.width, image.height)?.copy(Bitmap.Config.ARGB_8888, false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("PlayerArtwork", "Could not prepare background artwork", error)
            null
        }
        expansion.artwork = PlayerArtwork(cover, bitmap)
    }
}
