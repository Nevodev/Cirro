package com.nevoit.kinetic.layout.snap

import androidx.compose.animation.core.Animation
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimation
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.copy
import androidx.compose.animation.core.generateDecayAnimationSpec
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import com.nevoit.kinetic.layout.FlingVelocityProvider
import com.nevoit.kinetic.layout.KineticExponentialDecay
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Optional fling behavior: exponential free decay, platform approach planning, then a C1
 * retargeted settle on a real measured anchor. It returns the unconsumed velocity at a hard edge,
 * so the caller's overscroll effect still owns the bounce.
 *
 * [snapStiffness] controls the final braking duration (4.8 / sqrt(stiffness) seconds).
 * Implements [FlingVelocityProvider], so Kinetic containers keep their low-speed click policy with
 * it; pass it explicitly to any Kinetic list — it is never a default.
 *
 * Not a bit-for-bit platform port: some retarget paths rewrite the release velocity upstream.
 */
@Composable
fun rememberKineticSnapFlingBehavior(
    state: LazyListState,
    snapPosition: SnapPosition = SnapPosition.Center,
    decelerationRate: Float = 0.998f,
    snapStiffness: Float = 400f,
): FlingBehavior {
    val density = LocalDensity.current.density
    return remember(state, snapPosition, decelerationRate, snapStiffness, density) {
        KineticSnapFlingBehavior(
            provider = SnapLayoutInfoProvider(state, snapPosition),
            decay = KineticExponentialDecay(decelerationRate, absVelocityThreshold = 10f * density),
            bounds = LazyListBounds(state),
            snapStiffness = snapStiffness,
            positionThreshold = 0.1f * density,
            targets = LazyListSnapTargets(state, snapPosition, 400f * density),
            density = density,
        )
    }
}

internal interface ScrollBounds {
    fun canScroll(direction: Float): Boolean

    /** Nonnegative physical distance, or null if the corresponding edge is not laid out yet. */
    fun distanceToEdge(direction: Float): Float?
}

internal class LazyListBounds(private val state: LazyListState) : ScrollBounds {
    override fun canScroll(direction: Float): Boolean = when {
        direction > 0f -> state.canScrollForward
        direction < 0f -> state.canScrollBackward
        else -> true
    }

    override fun distanceToEdge(direction: Float): Float? {
        if (!canScroll(direction)) return 0f
        if (direction < 0f) {
            // A pinned header may be visible at index 0 far from the physical start.
            return if (state.firstVisibleItemIndex == 0) state.firstVisibleItemScrollOffset.toFloat() else null
        }
        val info = state.layoutInfo
        val last =
            info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 } ?: return null
        return (last.offset + last.size - info.viewportEndOffset + info.afterContentPadding)
            .toFloat().coerceAtLeast(0f)
    }
}

internal class KineticSnapFlingBehavior(
    private val provider: SnapLayoutInfoProvider,
    private val decay: KineticExponentialDecay,
    private val bounds: ScrollBounds,
    private val snapStiffness: Float = 400f,
    private val positionThreshold: Float = 0.1f,
    private val targets: SnapTargetProvider = SnapTargetProvider { _, velocity, _ ->
        provider.calculateSnapOffset(velocity)
    },
    private val density: Float = 1f,
) : FlingBehavior, FlingVelocityProvider {
    init {
        require(snapStiffness.isFinite() && snapStiffness > 0f)
        require(positionThreshold.isFinite() && positionThreshold > 0f)
        require(density.isFinite() && density > 0f)
    }

    private val decaySpec: DecayAnimationSpec<Float> = decay.generateDecayAnimationSpec()

    /** Publish the live frame velocity so a low-speed DOWN can still reach the item's own click. */
    private var velocity: Float? = null

    override val currentFlingVelocity: Float?
        get() = velocity

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        require(initialVelocity.isFinite())
        velocity = initialVelocity
        try {
            return settle(initialVelocity)
        } finally {
            velocity = null
        }
    }

    private suspend fun ScrollScope.settle(initialVelocity: Float): Float {
        if (initialVelocity != 0f && !bounds.canScroll(initialVelocity)) return initialVelocity
        val scale = coroutineContext[MotionDurationScale]
        if (scale?.scaleFactor == 0f) {
            val target = provider.calculateSnapOffset(0f)
            check(target.isFinite())
            scrollBy(target)
            return 0f
        }
        var state = AnimationState(0f, initialVelocity)
        val projected = decaySpec.calculateTargetValue(0f, initialVelocity)
        val rawApproach = provider.calculateApproachOffset(initialVelocity, projected)
        check(rawApproach.isFinite())
        var approach = abs(rawApproach) * sign(initialVelocity)
        val selectionVelocity = (sign(initialVelocity) * max(
            0.0,
            abs(initialVelocity.toDouble()) - decay.lambda * abs(approach)
        )).toFloat()
        if (willReachEdge(state.velocity)) return runDecay(
            state,
            scale,
            0f,
            0f,
            false
        ).remainingVelocity

        // Apply the platform's directional/closest policy at its planned approach point, before
        // crossing that anchor. Selecting around the full decay endpoint would pick an extra item.
        var target = if (approach == 0f) provider.calculateSnapOffset(initialVelocity)
        else targets.calculateTarget(approach, selectionVelocity, false)
        var carry = 0L
        while (target == null && abs(state.velocity) > decay.absVelocityThreshold) {
            val result = runDecay(state, scale, approach, selectionVelocity, true)
            if (result.hitEdge) return result.remainingVelocity
            approach -= result.state.value
            target = result.target
            carry = result.carryNanos
            state = result.state.copy(value = 0f)
            if (result.decayFinished) break
        }
        if (willReachEdge(state.velocity)) return runDecayToEdge(state, scale, carry)
        val distance =
            target ?: provider.calculateSnapOffset(sign(initialVelocity) * 400f * density)
        check(distance.isFinite())
        if (distance == 0f && state.velocity == 0f) return 0f
        val initialSnapVelocity = state.velocity
        val baseSpec = RetargetedDecaySpec(distance, initialSnapVelocity, snapStiffness, density)
        val baseCurve = TargetBasedAnimation(
            baseSpec, Float.VectorConverter, 0f, distance,
            AnimationVector1D(initialSnapVelocity)
        )
        carry = minOf(carry, baseCurve.durationNanos)
        if (carry > 0L) {
            val firstStepper =
                ScrollStepper(this, bounds, baseCurve, scale, reportVelocity = ::reportVelocity)
            val x = baseCurve.getValueFromNanos(carry)
            val v = baseCurve.getVelocityVectorFromNanos(carry).value
            val collision = firstStepper.step(x, v, (carry * (scale?.scaleFactor ?: 1f)).toLong())
            if (collision != null) return collision
            if (carry >= baseCurve.durationNanos) return 0f
            state = state.copy(value = x, velocity = v)
        }
        val spec = if (carry == 0L) baseSpec else ContinuedSnapSpec(
            baseSpec,
            distance,
            initialSnapVelocity,
            carry
        )
        val curve = TargetBasedAnimation(
            spec, Float.VectorConverter, state.value, distance,
            AnimationVector1D(state.velocity)
        )
        val stepper = ScrollStepper(
            this, bounds, curve, scale, startValue = state.value,
            reportVelocity = ::reportVelocity
        )
        var remaining = 0f
        state.animateTo(
            distance,
            spec,
            sequentialAnimation = state.lastFrameTimeNanos != Long.MIN_VALUE
        ) {
            val collision = stepper.step(value, velocity, lastFrameTimeNanos - startTimeNanos)
            if (collision != null) {
                remaining = collision
                cancelAnimation()
            }
        }
        return remaining
    }

    private fun reportVelocity(value: Float) {
        if (value.isFinite()) velocity = value
    }

    private suspend fun ScrollScope.runDecayToEdge(
        state: AnimationState<Float, AnimationVector1D>,
        scale: MotionDurationScale?,
        carryNanos: Long,
    ): Float {
        if (carryNanos == 0L) return runDecay(state, scale, 0f, 0f, false).remainingVelocity
        // If the physical edge becomes measurable inside the target-discovery frame, the
        // remaining portion of that frame still belongs to the same free decay.
        val curve = DecayAnimation(decay, initialValue = 0f, initialVelocity = state.velocity)
        val time = minOf(carryNanos, curve.durationNanos)
        val x = curve.getValueFromNanos(time)
        val v = curve.getVelocityVectorFromNanos(time).value
        val stepper = ScrollStepper(this, bounds, curve, scale, reportVelocity = ::reportVelocity)
        val collision = stepper.step(x, v, (time * (scale?.scaleFactor ?: 1f)).toLong())
        if (collision != null) return collision
        if (time >= curve.durationNanos) return 0f
        return runDecay(
            state.copy(value = 0f, velocity = v),
            scale,
            0f,
            0f,
            false
        ).remainingVelocity
    }

    private fun willReachEdge(velocity: Float): Boolean {
        if (velocity == 0f) return false
        val edge = bounds.distanceToEdge(velocity) ?: return false
        return edge <= abs(decaySpec.calculateTargetValue(0f, velocity))
    }

    private suspend fun ScrollScope.runDecay(
        state: AnimationState<Float, AnimationVector1D>,
        scale: MotionDurationScale?,
        approach: Float,
        selectionVelocity: Float,
        findTarget: Boolean,
    ): DecayResult {
        val initialVelocity = state.velocity
        val curve = DecayAnimation(decay, initialValue = 0f, initialVelocity = initialVelocity)
        val stepper = ScrollStepper(this, bounds, curve, scale, reportVelocity = ::reportVelocity)
        var hitEdge = false
        var remaining = 0f
        var target: Float? = null
        var actualValue = 0f
        var actualVelocity = initialVelocity
        var carry = 0L
        var interrupted = false
        state.animateDecay(
            decaySpec,
            sequentialAnimation = state.lastFrameTimeNanos != Long.MIN_VALUE
        ) {
            val elapsed = lastFrameTimeNanos - startTimeNanos
            if (!findTarget) {
                val collision = stepper.step(value, velocity, elapsed)
                if (collision != null) {
                    hitEdge = true
                    remaining = collision
                    cancelAnimation()
                }
            } else {
                val factor = scale?.scaleFactor ?: 1f
                val frameTime = (elapsed / factor).toLong()
                var chunks = 0
                // Requery real geometry inside a long frame. Never advance blindly through
                // unseen items and choose an anchor after already scrolling past it.
                while (abs(value - actualValue) > .00001f) {
                    val direction = sign(initialVelocity)
                    val safe = targets.safeAdvance(direction).coerceAtLeast(.5f)
                    val next = actualValue + direction * minOf(abs(value - actualValue), safe)
                    val ratio =
                        (decay.lambda * abs(next) / abs(initialVelocity)).coerceIn(0.0, 1.0 - 1e-15)
                    val crossingTime = (-kotlin.math.ln1p(-ratio) / decay.lambda * 1e9).toLong()
                        .coerceAtMost(frameTime)
                    actualVelocity = (initialVelocity - decay.lambda * next).toFloat()
                    val collision =
                        stepper.step(next, actualVelocity, (crossingTime * factor).toLong())
                    if (collision != null) {
                        hitEdge = true
                        remaining = collision
                        cancelAnimation()
                        break
                    }
                    actualValue = next
                    target =
                        targets.calculateTarget(approach - actualValue, selectionVelocity, true)
                    if (target != null) {
                        carry = (frameTime - crossingTime).coerceAtLeast(0L)
                        interrupted = true
                        cancelAnimation()
                        break
                    }
                    if (++chunks >= 128) {
                        interrupted = true
                        cancelAnimation()
                        break
                    }
                }
            }
        }
        val resultState =
            if (findTarget) state.copy(value = actualValue, velocity = actualVelocity) else state
        return DecayResult(resultState, hitEdge, remaining, target, carry, !interrupted)
    }
}

private data class DecayResult(
    val state: AnimationState<Float, AnimationVector1D>,
    val hitEdge: Boolean,
    val remainingVelocity: Float,
    val target: Float?,
    val carryNanos: Long,
    val decayFinished: Boolean,
)

/** No direct overscroll dispatch, per-item animators or recomposition state. */
private class ScrollStepper(
    val scope: ScrollScope,
    val bounds: ScrollBounds,
    val curve: Animation<Float, AnimationVector1D>,
    val durationScale: MotionDurationScale?,
    startValue: Float = 0f,
    val reportVelocity: (Float) -> Unit = {},
) {
    private var previousValue = startValue
    private var previousTime = 0L

    fun step(value: Float, velocity: Float, elapsedNanos: Long): Float? {
        val factor = durationScale?.scaleFactor ?: 1f
        val time = if (factor > 0f) (elapsedNanos / factor).toLong() else curve.durationNanos
        val delta = value - previousValue
        if (delta == 0f) {
            previousTime = time
            return null
        }
        if (!bounds.canScroll(delta)) return collisionVelocity(
            previousValue,
            previousTime,
            time,
            delta,
            velocity
        )
        val edge = bounds.distanceToEdge(delta)
        val requested =
            if (edge != null && edge > 0f) sign(delta) * minOf(abs(delta), edge) else delta
        var requestedTotal = requested
        reportVelocity(velocity)
        var consumed = scope.scrollBy(requested)
        // Layout offsets are integer pixels. If an estimated edge did not stop the list,
        // consume the rest of this frame instead of silently dropping its displacement.
        if (requested != delta && abs(consumed - requested) <= 0.5f && bounds.canScroll(delta)) {
            requestedTotal = delta
            consumed += scope.scrollBy(delta - consumed)
        }
        val edgeReached = edge != null && abs(delta) >= edge && !bounds.canScroll(delta)
        val clipped = abs(consumed - requestedTotal) > 0.5f
        if (edgeReached || clipped) {
            val impact = if (edgeReached && edge > 0f) sign(delta) * edge else consumed
            return collisionVelocity(previousValue + impact, previousTime, time, delta, velocity)
        }
        previousValue = value
        previousTime = time
        return null
    }

    private fun collisionVelocity(
        position: Float,
        from: Long,
        to: Long,
        direction: Float,
        fallback: Float,
    ): Float {
        if (durationScale?.scaleFactor == 0f) return 0f
        // Resolve the crossing inside the frame, so a slow/janky frame does not lose impact velocity.
        var low = from
        var high = max(from, to)
        repeat(32) {
            val mid = low + (high - low) / 2
            if ((curve.getValueFromNanos(mid) - position) * sign(direction) < 0f) low =
                mid else high = mid
        }
        val result = curve.getVelocityVectorFromNanos(high).value
        return if (result.isFinite()) result else fallback
    }
}
