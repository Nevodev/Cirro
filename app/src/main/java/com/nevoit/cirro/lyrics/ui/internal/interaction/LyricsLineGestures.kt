package com.nevoit.cirro.lyrics.ui.internal.interaction

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isOutOfBounds

internal suspend fun PointerInputScope.detectLyricsLineGestures(
    interactionSource: MutableInteractionSource,
    onTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
) {
    awaitEachGesture {
        // A scrolling parent may consume DOWN while stopping its motion. Observe it before Main
        // without consuming any movement, and use displacement rather than consumption to cancel.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val interaction = PressInteraction.Press(down.position)
        var released = false
        interactionSource.tryEmit(interaction)
        try {
            val result = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                awaitLyricsRelease(down)
            }
            when (result) {
                true -> {
                    released = true
                    onTap(down.position)
                }
                false -> Unit
                null -> {
                    onLongPress(down.position)
                    released = awaitLyricsRelease(down)
                }
            }
        } finally {
            interactionSource.tryEmit(
                if (released) PressInteraction.Release(interaction)
                else PressInteraction.Cancel(interaction)
            )
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitLyricsRelease(down: PointerInputChange): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.any { it.id != down.id && it.pressed }) return false
        val change = event.changes.firstOrNull { it.id == down.id } ?: return false
        if (change.isOutOfBounds(size, extendedTouchPadding) ||
            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
        ) return false
        if (!change.pressed) return true
    }
}
