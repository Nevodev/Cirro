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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// The animated value plus the interaction source that drives it. Give `interactionSource` to the
// clickable, and read `progress` where the value is consumed (draw or layout phase): reading it
// during composition recomposes the caller on every frame of the press.
class PressAnimatable internal constructor(
    val interactionSource: MutableInteractionSource,
    val progress: Animatable<Float, AnimationVector1D>,
)

// A value driven by press: rest -> pressed on press, back to rest on release or cancel. Release
// waits for the press animation to land first: interrupting a spring mid-flight makes the value
// spring back from wherever it happened to be, which reads as a stutter. `onReleased` runs in that
// gap, before the rebound — haptics belong there.
//
// `interrupt` drops that wait. The next spring starts from wherever the running one is, carrying
// its current velocity as initial velocity, so the motion leaves in the direction it was already
// going. Every transition obeys the flag: press, release and cancel alike.
//
// `minPressMillis` is a floor on how long the value stays pressed. Lift the finger earlier and the
// release just waits the remainder out before doing what it always does — a very short tap still
// reads as a press instead of a flicker.
@Composable
fun rememberPressAnimatable(
    atRest: Float,
    whenPressed: Float,
    onPressSpec: AnimationSpec<Float>,
    onReleaseSpec: AnimationSpec<Float>,
    interrupt: Boolean = false,
    minPressMillis: Long = 0L,
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
    val interrupting = rememberUpdatedState(interrupt)
    val minPress = rememberUpdatedState(minPressMillis)
    val released = rememberUpdatedState(onReleased)

    LaunchedEffect(interactionSource) {
        var pressJob: Job? = null
        var releaseJob: Job? = null
        // Finger-down time on a monotonic clock. The floor is measured from the finger, not from the
        // animation: the press spring may land well before the floor is up.
        var pressedAtNanos = 0L

        // Must be read while the animation is still in flight, never after cancelling it: Animatable
        // resets its velocity to zero in endAnimation() the moment a running animation is cancelled,
        // so a read on the far side of a cancel only ever yields zero.
        fun carryOver(): Float = if (interrupting.value) progress.velocity else 0f

        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    pressedAtNanos = System.nanoTime()
                    val carried = carryOver()
                    releaseJob?.cancel()
                    pressJob?.cancel()
                    pressJob = this.launch {
                        progress.animateTo(pressed.value, pressSpec.value, carried)
                    }
                }

                is PressInteraction.Release -> {
                    val pendingPress = pressJob
                    val interrupted = interrupting.value
                    releaseJob?.cancel()
                    releaseJob = this.launch {
                        // Hold the floor first, then release exactly as before.
                        val floorEndNanos = pressedAtNanos + minPress.value * 1_000_000L
                        val remainingNanos = floorEndNanos - System.nanoTime()
                        if (remainingNanos > 0L) {
                            delay((remainingNanos / 1_000_000L + 1L).milliseconds)
                        }
                        // Re-sampled here, not on the interaction: the floor may have let the press
                        // spring run on, and its velocity is what has to be handed over.
                        val carried = carryOver()
                        if (interrupted) pendingPress?.cancel() else pendingPress?.join()
                        released.value.invoke()
                        progress.animateTo(resting.value, releaseSpec.value, carried)
                    }
                }

                is PressInteraction.Cancel -> {
                    val carried = carryOver()
                    releaseJob?.cancel()
                    pressJob?.cancel()
                    releaseJob = this.launch {
                        progress.animateTo(resting.value, releaseSpec.value, carried)
                    }
                }
            }
        }
    }

    return remember(interactionSource, progress) {
        PressAnimatable(interactionSource, progress)
    }
}
