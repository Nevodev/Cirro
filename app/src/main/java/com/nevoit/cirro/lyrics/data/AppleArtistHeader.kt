package com.nevoit.cirro.lyrics.data

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class AppleArtistHeader(
    val videoUrl: String? = null,
    val nameLogo: AppleArtistNameLogo? = null,
)

@Serializable
data class AppleArtistNameLogo(
    val url: String,
    val width: Int,
    val height: Int,
) {
    fun thumbnail(maxWidth: Int, maxHeight: Int): AppleArtistNameLogo? {
        if (
            width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0 ||
            !url.startsWith("https://") || !url.contains("{w}") || !url.contains("{h}") ||
            !url.substringAfterLast('/').contains('.')
        ) return null
        val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
        val targetWidth = (width * scale).roundToInt().coerceIn(1, maxWidth)
        val targetHeight = (height * scale).roundToInt().coerceIn(1, maxHeight)
        return copy(
            // Apple 的 Logo 默认也给 JPG 模板，透明背景需要请求 PNG。
            url = url.replaceAfterLast('.', "png").replace("{w}", targetWidth.toString())
                .replace("{h}", targetHeight.toString())
                .replace("{c}", "bb"),
            width = targetWidth,
            height = targetHeight,
        )
    }
}
