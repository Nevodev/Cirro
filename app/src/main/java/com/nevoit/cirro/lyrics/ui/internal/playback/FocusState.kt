package com.nevoit.cirro.lyrics.ui.internal.playback

internal data class FocusState(
    val firstIndex: Int,
    val allIndices: List<Int>,
    val activeInterludeIndex: Int?,
    val activeIntro: Boolean,
    val firstWordStartsLater: Boolean = false,
)
