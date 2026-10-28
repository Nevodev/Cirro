package com.nevoit.cirro.palette

import kotlin.math.pow

internal fun coverTopLuminance(pixels: IntArray, width: Int, height: Int): Double? {
    if (width <= 0 || height <= 0 || pixels.size < width.toLong() * height) return null
    val side = minOf(width, height)
    val left = (width - side) / 2
    val top = (height - side) / 2
    val rows = (side / 10).coerceAtLeast(1)
    fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    var sum = 0.0
    var weight = 0.0
    for (y in top until top + rows) for (x in left until left + side) {
        val color = pixels[y * width + x]
        val alpha = (color ushr 24) / 255.0
        sum += alpha * (0.2126 * linear((color ushr 16) and 255) +
                0.7152 * linear((color ushr 8) and 255) + 0.0722 * linear(color and 255))
        weight += alpha
    }
    return if (weight > 0.0) sum / weight else null
}

internal fun collectionStatusBarDarkIcons(
    dark: Boolean?,
    topLuminance: Double?,
    backgroundVisible: Boolean = false,
    backgroundLuminance: Double? = null,
): Boolean? {
    if (dark == true) return false
    val luminance = if (backgroundVisible) backgroundLuminance else topLuminance
    // 亮度未知时交回系统默认行为；同一阈值用于封面和滚动后的页面底色。
    return luminance?.let { it > 0.4 }
}
