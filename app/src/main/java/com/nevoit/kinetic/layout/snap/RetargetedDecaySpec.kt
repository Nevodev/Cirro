package com.nevoit.kinetic.layout.snap

import androidx.compose.animation.core.FloatAnimationSpec
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * C1 adaptation of the platform's target-before-deceleration retargeting.
 * Forward motion changes the exponential rate, never increasing incoming speed, then brakes
 * continuously to an exact rest. The finite braking tail is a Compose adaptation.
 * A stationary, opposite-direction, or distant low-speed correction uses a bounded cubic instead.
 */
internal class RetargetedDecaySpec(
    private val distance: Float,
    private val incomingVelocity: Float,
    stiffness: Float,
    density: Float,
) : FloatAnimationSpec {
    private val d = abs(distance.toDouble())
    private val speed = abs(incomingVelocity.toDouble())
    private val direction = if (distance >= 0f) 1.0 else -1.0
    // At very low speed a monotone retarget would creep for many seconds. The bounded nudge keeps
    // that velocity change continuous instead.
    private val forward = distance * incomingVelocity > 0f && speed >= 10.0 * density && d <= .75 * speed
    private val lambda: Double
    private val handoffSeconds: Double
    private val tailSeconds: Double
    private val tailSpeed: Double
    private val tailStart: Double
    private val duration: Double

    init {
        val brakingTime = (4.8 / sqrt(stiffness.toDouble())).coerceIn(.06, .5)
        if (forward) {
            tailSeconds = min(brakingTime, 2.0 * d / speed)
            if (tailSeconds * speed / 2.0 >= d - 1e-10) {
                lambda = 0.0
                handoffSeconds = 0.0
                tailSpeed = speed
                tailStart = 0.0
            } else {
                tailSpeed = min(speed * .25, 80.0 * density)
                tailStart = d - tailSpeed * tailSeconds / 2.0
                lambda = (speed - tailSpeed) / tailStart
                handoffSeconds = ln(speed / tailSpeed) / lambda
            }
            duration = handoffSeconds + tailSeconds
        } else {
            var time = max(brakingTime, 1.5 * d / (400.0 * density))
            val limit = max(speed, 400.0 * density)
            // v*T <= 3*d keeps a same-direction cubic monotone even for huge offsets.
            val monotoneLimit = if (distance * incomingVelocity > 0f) 3 * d / speed else Double.POSITIVE_INFINITY
            time = min(time, monotoneLimit)
            repeat(32) {
                if (hermitePeak(time) > limit * (1 + 1e-9)) time = min(time * 1.2, monotoneLimit)
            }
            lambda = 0.0
            handoffSeconds = 0.0
            tailSeconds = time
            tailSpeed = 0.0
            tailStart = 0.0
            duration = if (d == 0.0 && speed == 0.0) 0.0 else time
        }
    }

    private fun hermiteVelocity(u: Double, time: Double): Double =
        (6 * d / time - 3 * direction * incomingVelocity) * u * (1 - u) +
            direction * incomingVelocity * (1 - u)

    private fun hermitePeak(time: Double): Double {
        val v = direction * incomingVelocity
        val a = 3 * v - 6 * d / time
        val b = 6 * d / time - 4 * v
        val root = if (a == 0.0) -1.0 else -b / (2 * a)
        return max(speed, if (root > 0 && root < 1) abs(hermiteVelocity(root, time)) else 0.0)
    }

    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Float {
        val t = (playTimeNanos.coerceAtLeast(0L) / 1e9).coerceAtMost(duration)
        if (t >= duration) return targetValue
        val position = if (forward) {
            if (t < handoffSeconds) -speed * expm1(-lambda * t) / lambda
            else {
                val dt = t - handoffSeconds
                tailStart + tailSpeed * dt * (1 - dt / (2 * tailSeconds))
            }
        } else {
            val u = t / duration
            d * u * u * (3 - 2 * u) + direction * incomingVelocity * duration * u * (1 - u) * (1 - u)
        }
        return (initialValue + direction * position).toFloat()
    }

    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Float {
        if (playTimeNanos <= 0L) return incomingVelocity
        val t = playTimeNanos / 1e9
        if (t >= duration) return 0f
        val v = if (forward) {
            if (t < handoffSeconds) speed * exp(-lambda * t)
            else tailSpeed * (1 - (t - handoffSeconds) / tailSeconds)
        } else hermiteVelocity(t / duration, duration)
        return (direction * v).toFloat()
    }

    override fun getDurationNanos(initialValue: Float, targetValue: Float, initialVelocity: Float): Long =
        if (duration == 0.0) 0L else (duration * 1e9).toLong().coerceIn(1L, Long.MAX_VALUE - 1) + 1

    override fun getEndVelocity(initialValue: Float, targetValue: Float, initialVelocity: Float): Float = 0f
}

/** Resume a frozen curve after consuming the remainder of its first frame. */
internal class ContinuedSnapSpec(
    private val original: FloatAnimationSpec,
    private val distance: Float,
    private val velocity: Float,
    private val elapsed: Long,
) : FloatAnimationSpec {
    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ) = original.getValueFromNanos(playTimeNanos + elapsed, 0f, distance, velocity)

    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ) = original.getVelocityFromNanos(playTimeNanos + elapsed, 0f, distance, velocity)

    override fun getDurationNanos(initialValue: Float, targetValue: Float, initialVelocity: Float) =
        (original.getDurationNanos(0f, distance, velocity) - elapsed).coerceAtLeast(0L)

    override fun getEndVelocity(initialValue: Float, targetValue: Float, initialVelocity: Float) = 0f
}
