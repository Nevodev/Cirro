package com.nevoit.cirro.ui.video

import kotlin.math.roundToInt

/**
 * 倒影的几何：接缝在哪、倒影从源图多高的一条裁出来、模糊带从哪一行开始。
 *
 * [bleedRatio] 是模糊带往接缝**上方**溢进原图的高度，按 [rise] 的比例算。艺人页视频用它默认的
 * 一半 —— 那边画面一直在动，溢一截反而看不出接缝；详情页是静止封面，溢多了会把封面底部糊掉
 * 一大块，所以那边传更小的值。
 */
internal class ArtistReflectionGeometry(
    val side: Int,
    riseRatio: Float,
    bleedRatio: Float = 0.5f,
) {
    val rise = (side * riseRatio).roundToInt().coerceAtLeast(1)
    val strip = (side * riseRatio / 3f).roundToInt().coerceIn(1, side)
    val bleed = (rise * bleedRatio).roundToInt().coerceIn(0, rise)
    val height = side + rise

    /** 半径 0 的那一行，从图像顶部量。等于 [side] 时正好落在接缝上。 */
    val blurStart = side - bleed

    /** 斜坡总长；[blurStart] + [blurHeight] 恒等于 [height]，最大半径永远在图像最底边。 */
    val blurHeight = rise + bleed
}
