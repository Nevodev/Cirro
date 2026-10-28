package com.nevoit.cirro.ui.video

import kotlin.math.roundToInt
import kotlin.math.hypot

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
    outputHeight: Int? = null,
    blurStartFraction: Float? = null,
    reflectionSourceFraction: Float? = null,
    blurEndFraction: Float? = null,
    blurCircleBottomFraction: Float? = null,
    blurHorizontalScale: Float = 1f,
) {
    val height = outputHeight ?: (side + (side * riseRatio).roundToInt().coerceAtLeast(1))
    val rise = (height - sourceHeight).coerceAtLeast(0)
    val strip = (reflectionSourceFraction?.let { sourceHeight * it } ?: (side * riseRatio / 3f))
        .roundToInt().coerceIn(1, sourceHeight)
    private val blurReference = sourceHeight * (blurCircleBottomFraction ?: 1f)
    val bleed = if (blurStartFraction != null) (sourceHeight - blurReference * blurStartFraction).roundToInt()
        .coerceAtMost(sourceHeight) else (rise * bleedRatio).roundToInt().coerceIn(0, rise)

    /** 半径为 0 的距离；圆形模式从顶部圆心量，其余模式从图像顶边量。 */
    val blurStart = sourceHeight - bleed

    /** 达到最大半径的位置；不受输出高度裁剪，避免横竖屏改变同一封面上的模糊斜坡。 */
    val blurEnd = blurEndFraction?.let { (blurReference * it).roundToInt() } ?: height

    /** 起点在可见区域以外时不需要模糊处理；终点下方由 shader 保持最大半径。 */
    private val maxBlurDistance = if (blurCircleBottomFraction != null)
        hypot(side / (2f * blurHorizontalScale), height.toFloat()) else height.toFloat()
    val blurHeight = if (blurStart >= maxBlurDistance) 0 else (blurEnd - blurStart).coerceAtLeast(0)
}
