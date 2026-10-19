package com.nevoit.kinetic.layout

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.generateDecayAnimationSpec
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.nevoit.kinetic.layout.internal.gestures.TrackedDefaultFlingBehavior

/** Defaults of the exponential fling used by every Kinetic container. */
object KineticFlingDefaults {

    /** Velocity multiplier per millisecond. 0.998 is the platform "normal" rate. */
    const val DecelerationRate: Float = 0.998f

    /** The decay ends once the velocity drops to this value, in px/s. */
    const val StopVelocity: Float = 50f

    /** Slowest release velocity that still flings, in px/s. Below it the gesture just stops. */
    const val MinVelocity: Float = 500f
}

/**
 * Exponential decay: velocity is multiplied by [decelerationRate] every millisecond and the
 * curve stops at [KineticFlingDefaults.StopVelocity]. Density independent, so one spec can be
 * reused at any font/display size.
 */
internal fun kineticDecaySpec(
    decelerationRate: Float = KineticFlingDefaults.DecelerationRate,
): DecayAnimationSpec<Float> = KineticExponentialDecay(
    decelerationRate = decelerationRate,
    absVelocityThreshold = KineticFlingDefaults.StopVelocity,
).generateDecayAnimationSpec()

/** [kineticDecaySpec] for callers outside composition, e.g. a modifier node. */
internal fun kineticDefaultFlingBehavior(
    decelerationRate: Float = KineticFlingDefaults.DecelerationRate,
    minVelocity: Float = KineticFlingDefaults.MinVelocity,
): TrackedDefaultFlingBehavior =
    TrackedDefaultFlingBehavior(kineticDecaySpec(decelerationRate), minVelocity)

@Composable
fun rememberKineticDecaySpec(
    decelerationRate: Float = KineticFlingDefaults.DecelerationRate,
): DecayAnimationSpec<Float> = remember(decelerationRate) { kineticDecaySpec(decelerationRate) }

/**
 * Fling used by every container that does not pass its own behavior.
 *
 * Releases slower than [minVelocity] do not start an inertia scroll at all; faster ones decay
 * exponentially. The behavior reports its live velocity through [FlingVelocityProvider], so the
 * low-speed click policy keeps working during the tail of a fling.
 */
@Composable
fun rememberKineticFlingBehavior(
    decelerationRate: Float = KineticFlingDefaults.DecelerationRate,
    minVelocity: Float = KineticFlingDefaults.MinVelocity,
): FlingBehavior {
    val decay = rememberKineticDecaySpec(decelerationRate)
    return remember(decay, minVelocity) { TrackedDefaultFlingBehavior(decay, minVelocity) }
}
