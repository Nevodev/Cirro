package com.nevoit.material.core.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The animated value plus the interaction source that drives it. Give `interactionSource` to the
// clickable, and read `progress` where the value is consumed (draw or layout phase): reading it
// during composition recomposes the caller on every frame of the press.
class PressAnimatable internal constructor(
    val interactionSource: MutableInteractionSource,
    val progress: Animatable<Float, AnimationVector1D>,
)

// A value driven by press: rest -> pressed on press, back to rest on release or cancel. Release
// always waits for the press animation to land first: interrupting a spring mid-flight makes the
// value spring back from wherever it happened to be, which reads as a stutter. `onReleased` runs in
// that gap, before the rebound — haptics belong there.
@Composable
fun rememberPressAnimatable(
    atRest: Float,
    whenPressed: Float,
    onPressSpec: AnimationSpec<Float>,
    onReleaseSpec: AnimationSpec<Float>,
    onReleased: suspend () -> Unit = {},
): PressAnimatable {
    val interactionSource = remember { MutableInteractionSource() }
    val progress = remember { Animatable(atRest) }

    // The effect restarts with the source only, and must not restart for anything else: a new spec
    // or callback arriving mid-press would kill the animation in flight. Hence updated state.
    val resting = rememberUpdatedState(atRest)
    val pressed = rememberUpdatedState(whenPressed)
    val pressSpec = rememberUpdatedState(onPressSpec)
    val releaseSpec = rememberUpdatedState(onReleaseSpec)
    val released = rememberUpdatedState(onReleased)

    LaunchedEffect(interactionSource) {
        var pressJob: Job? = null
        var releaseJob: Job? = null

        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    releaseJob?.cancel()
                    pressJob?.cancel()
                    pressJob = launch {
                        progress.animateTo(pressed.value, pressSpec.value)
                    }
                }

                is PressInteraction.Release -> {
                    val pendingPress = pressJob
                    releaseJob?.cancel()
                    releaseJob = launch {
                        pendingPress?.join()
                        released.value.invoke()
                        progress.animateTo(resting.value, releaseSpec.value)
                    }
                }

                is PressInteraction.Cancel -> {
                    releaseJob?.cancel()
                    pressJob?.cancel()
                    releaseJob = launch {
                        progress.animateTo(resting.value, releaseSpec.value)
                    }
                }
            }
        }
    }

    return remember(interactionSource, progress) {
        PressAnimatable(interactionSource, progress)
    }
}
