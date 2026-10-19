package com.nevoit.kinetic.layout

import androidx.compose.animation.core.FloatDecayAnimationSpec
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sign

/**
 * Continuous exponential decay. Velocity is px/s; [decelerationRate] is the multiplier per
 * millisecond, matching the platform's own "normal / fast" rate constants.
 *
 * This is the decay shared by [rememberKineticDecaySpec] and the optional snap behavior.
 */
class KineticExponentialDecay(
    val decelerationRate: Float = 0.998f,
    override val absVelocityThreshold: Float = 10f,
) : FloatDecayAnimationSpec {
    init {
        require(decelerationRate > 0f && decelerationRate < 1f)
        require(absVelocityThreshold.isFinite() && absVelocityThreshold > 0f)
    }

    internal val lambda: Double = -1000.0 * ln(decelerationRate.toDouble())

    override fun getValueFromNanos(playTimeNanos: Long, initialValue: Float, initialVelocity: Float): Float =
        (initialValue - initialVelocity * expm1(-lambda * playTimeNanos.coerceAtLeast(0L) / 1e9) / lambda).toFloat()

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: Float, initialVelocity: Float): Float =
        (initialVelocity * exp(-lambda * playTimeNanos.coerceAtLeast(0L) / 1e9)).toFloat()

    override fun getDurationNanos(initialValue: Float, initialVelocity: Float): Long =
        (max(0.0, ln(abs(initialVelocity.toDouble()) / absVelocityThreshold) / lambda) * 1e9).toLong()

    override fun getTargetValue(initialValue: Float, initialVelocity: Float): Float =
        if (abs(initialVelocity) <= absVelocityThreshold) initialValue
        else (initialValue + (initialVelocity - sign(initialVelocity) * absVelocityThreshold) / lambda).toFloat()
}
