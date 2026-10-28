package com.nevoit.cirro.ui.video

import kotlin.math.roundToInt

/**
 * 倒影的几何：接缝在哪、倒影从源图多高的一条裁出来、模糊带从哪一行开始。
 *
 * [bleedRatio] 是模糊带往接缝**上方**溢进原图的高度，按 [rise] 的比例算。艺人页视频用它默认的
 * 一半 —— 那边画面一直在动，溢一截反而看不出接缝；详情页是静止封面，溢多了会把封面底部糊掉
 * 一大块，所以那边传更小的值。[sourceHeight] 允许竖版视频按宽度适配后，仅延展其下方的空余高度。
 */
internal class ArtistReflectionGeometry(
    val side: Int,
    riseRatio: Float,
    bleedRatio: Float = 0.5f,
    val sourceHeight: Int = side,
) {
    val height = side + (side * riseRatio).roundToInt().coerceAtLeast(1)
    val rise = (height - sourceHeight).coerceAtLeast(0)
    val strip = (side * riseRatio / 3f).roundToInt().coerceIn(1, sourceHeight)
    val bleed = (rise * bleedRatio).roundToInt().coerceIn(0, rise)

    /** 半径 0 的那一行，从图像顶部量。等于 [sourceHeight] 时正好落在接缝上。 */
    val blurStart = sourceHeight - bleed

    /** 有延展带时斜坡一直延续到输出底边；原视频已覆盖输出高度时为 0。 */
    val blurHeight = rise + bleed
}
