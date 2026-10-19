@file:Suppress(
    "INVISIBLE_MEMBER",
    "INVISIBLE_REFERENCE",
    "CANNOT_OVERRIDE_INVISIBLE_MEMBER",
    "EXPOSED_SUPER_CLASS",
    "EXPOSED_PROPERTY_TYPE",
    "EXPOSED_PARAMETER_TYPE",
)

package com.nevoit.kinetic.layout.internal.gestures

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.gestures.DefaultScrollMotionDurationScale
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaultFlingBehavior
import androidx.compose.ui.MotionDurationScale
import com.nevoit.kinetic.layout.FlingVelocityProvider
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Default fling of every Kinetic container.
 *
 * Releases slower than [velocityThreshold] do not fling at all; anything faster decays through
 * [flingDecay] (exponential by default). Unconsumed frames cancel the animation and hand the
 * remaining velocity back to the scroll gesture, same as the Foundation behavior.
 *
 * [currentFlingVelocity] publishes the live per-frame velocity so a slow DOWN can still reach the
 * item's own click.
 */
internal class TrackedDefaultFlingBehavior(
    private val flingDecay: DecayAnimationSpec<Float>,
    private val velocityThreshold: Float = 0f,
    private val motionDurationScale: MotionDurationScale = DefaultScrollMotionDurationScale,
) : ScrollableDefaultFlingBehavior, FlingVelocityProvider {

    init {
        require(velocityThreshold.isFinite() && velocityThreshold >= 0f) {
            "velocityThreshold must be finite and non-negative (px/s)."
        }
    }

    private var flingRunning = false
    private var observedVelocity = 0f

    override val currentFlingVelocity: Float?
        get() = if (flingRunning) observedVelocity else null

    // For Testing
    var lastAnimationCycleCount = 0

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) < velocityThreshold) return 0f

        observedVelocity = initialVelocity
        flingRunning = true
        try {
            lastAnimationCycleCount = 0
            return withContext(motionDurationScale) {
                if (abs(initialVelocity) > 1f) {
                    var velocityLeft = initialVelocity
                    var lastValue = 0f
                    val animationState =
                        AnimationState(initialValue = 0f, initialVelocity = initialVelocity)
                    try {
                        animationState.animateDecay(flingDecay) {
                            observedVelocity = this.velocity
                            val delta = value - lastValue
                            val consumed = scrollBy(delta)
                            lastValue = value
                            velocityLeft = this.velocity
                            // avoid rounding errors and stop if anything is unconsumed
                            if (abs(delta - consumed) > 0.5f) this.cancelAnimation()
                            lastAnimationCycleCount++
                        }
                    } catch (exception: CancellationException) {
                        velocityLeft = animationState.velocity
                    }
                    velocityLeft
                } else {
                    initialVelocity
                }
            }
        } finally {
            flingRunning = false
            observedVelocity = 0f
        }
    }
}
