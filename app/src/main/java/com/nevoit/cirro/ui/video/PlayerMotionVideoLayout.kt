package com.nevoit.cirro.ui.video

internal data class PlayerMotionVideoLayout(
    val heightToWidth: Float,
    val blurStartFraction: Float,
    val blurEndFraction: Float,
    val reflectionSourceFraction: Float,
    val blurRadiusPx: Float,
    val blurCircleBottomFraction: Float = 1f,
    val blurHorizontalScale: Float = 1f,
) {
    init {
        require(blurStartFraction.isFinite() && blurStartFraction >= 0f)
        require(blurEndFraction.isFinite() && blurEndFraction > blurStartFraction)
        require(blurRadiusPx.isFinite() && blurRadiusPx >= 0f)
        require(blurCircleBottomFraction.isFinite() && blurCircleBottomFraction > 0f)
        require(blurHorizontalScale.isFinite() && blurHorizontalScale > 0f)
    }
}
