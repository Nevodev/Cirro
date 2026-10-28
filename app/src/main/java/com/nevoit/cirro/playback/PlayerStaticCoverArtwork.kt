package com.nevoit.cirro.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter

internal class PlayerStaticCoverArtwork(initialMotion: Boolean) {
    var painter by mutableStateOf<Painter?>(null)
        private set
    var opaque by mutableStateOf(false)
        private set
    var preparingReturn by mutableStateOf(initialMotion)
        private set
    var transitionGeneration by mutableIntStateOf(0)
        private set

    fun reserveMotion() {
        preparingReturn = true
    }

    fun show(painter: Painter?, opaque: Boolean, motion: Boolean) {
        this.painter = painter
        this.opaque = opaque
        if (preparingReturn && !motion) {
            // 动态退场后的第一张静态图必须直接就位，再由外层统一淡入。
            transitionGeneration++
            preparingReturn = false
        }
    }
}
