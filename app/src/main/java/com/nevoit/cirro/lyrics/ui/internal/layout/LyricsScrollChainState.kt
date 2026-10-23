package com.nevoit.cirro.lyrics.ui.internal.layout

import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsScrollChain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Physics storage survives lazy item recycling. Only the retained viewport range is advanced. */
internal class LyricsScrollChainState {
    private var settings: LyricsScrollChain? = null
    private var layoutTops = DoubleArray(0)
    private var positions = DoubleArray(0)
    private var velocities = FloatArray(0)
    private var contentOffsets = FloatArray(0)
    private var contentVelocities = FloatArray(0)
    private var leadingContentOffsets = FloatArray(0)
    private var leadingContentVelocities = FloatArray(0)
    private var breathingContentOffsets = FloatArray(0)
    private var breathingContentVelocities = FloatArray(0)
    private val contentSpring = LyricsScrollChain.of(durationMillis = 600f, bounce = 0.1f)
    private var offsets by mutableStateOf(emptyArray<MutableFloatState>())
    private var leadingOffsets by mutableStateOf(emptyArray<MutableFloatState>())
    private var breathingOffsets by mutableStateOf(emptyArray<MutableFloatState>())
    private var stiffness = FloatArray(0)
    private var damping = FloatArray(0)
    private var couplingGain = FloatArray(0)
    private var first = 0
    private var end = 0
    private var base = 0.0
    private var limit = 1f
    private var glide = false
    private var focus = 0
    private var followBounceFromIndex = -1
    private val delayedFirstWordExtraBounce = 0f
    private val delayedFirstWordExtraDurationMillis = 150f
    private var delayedFirstWordStiffness = FloatArray(0)
    private var delayedFirstWordDamping = FloatArray(0)
    private var delayedFirstWordCouplingGain = FloatArray(0)
    var active by mutableStateOf(false)
        private set

    fun configure(count: Int, config: LyricsScrollChain?, position: Double, viewport: Int) {
        limit = viewport.coerceAtLeast(1).toFloat()
        if (positions.size == count && settings == config) return
        settings = config
        layoutTops = DoubleArray(count) { Double.NaN }
        positions = DoubleArray(count) { position }
        velocities = FloatArray(count)
        contentOffsets = FloatArray(count)
        contentVelocities = FloatArray(count)
        leadingContentOffsets = FloatArray(count)
        leadingContentVelocities = FloatArray(count)
        breathingContentOffsets = FloatArray(count)
        breathingContentVelocities = FloatArray(count)
        offsets = Array(count) { mutableFloatStateOf(0f) }
        leadingOffsets = Array(count) { mutableFloatStateOf(0f) }
        breathingOffsets = Array(count) { mutableFloatStateOf(0f) }
        stiffness = FloatArray(count + 1)
        damping = FloatArray(count + 1)
        couplingGain = FloatArray(count + 1)
        delayedFirstWordStiffness = FloatArray(count + 1)
        delayedFirstWordDamping = FloatArray(count + 1)
        delayedFirstWordCouplingGain = FloatArray(count + 1)
        if (config != null)
            for (distance in stiffness.indices) {
                val response =
                    (1f - distance * config.distanceFalloff).coerceIn(config.minResponse, 1f)
                stiffness[distance] = config.stiffness * response
                damping[distance] = config.damping * response.pow(0.3f)
                val frequency = sqrt(stiffness[distance])
                val durationMillis = 2000f * PI.toFloat() / frequency
                val frequencyScale =
                    durationMillis / (durationMillis + delayedFirstWordExtraDurationMillis)
                // Keep each distance's response and damping ratio when extending its period.
                delayedFirstWordStiffness[distance] =
                    stiffness[distance] * frequencyScale * frequencyScale
                val criticalDamping = 2f * frequency
                delayedFirstWordDamping[distance] =
                    (damping[distance] - criticalDamping * delayedFirstWordExtraBounce)
                        .coerceAtLeast(criticalDamping * 0.05f) * frequencyScale
                couplingGain[distance] =
                    impulseGain(stiffness[distance], damping[distance]) * config.coupling
                delayedFirstWordCouplingGain[distance] = impulseGain(
                    delayedFirstWordStiffness[distance], delayedFirstWordDamping[distance],
                ) * config.coupling
            }
        first = 0
        end = 0
        base = position
        active = false
        followBounceFromIndex = -1
    }

    fun retain(from: Int, until: Int) {
        for (i in from until until) if (i < first || i >= end) {
            positions[i] = base
            layoutTops[i] = Double.NaN
            velocities[i] = 0f
            contentOffsets[i] = 0f
            contentVelocities[i] = 0f
            leadingContentOffsets[i] = 0f
            leadingContentVelocities[i] = 0f
            breathingContentOffsets[i] = 0f
            breathingContentVelocities[i] = 0f
            offsets[i].floatValue = 0f
            leadingOffsets[i].floatValue = 0f
            breathingOffsets[i].floatValue = 0f
        }
        first = from
        end = until
    }

    /**
     * Records the latest content-space top and optionally preserves the item's current screen
     * coordinate while its measured height changes.
     *
     * During a predicted follow the target scroll already includes the settled heights of the
     * preceding items. Injecting the same top delta into the spring would apply that geometry
     * change twice, so callers can update the cached top without perturbing the spring.
     */
    fun layoutAt(
        index: Int,
        top: Double,
        anchorCorrection: Double,
        preserveScreenPosition: Boolean = true,
    ) {
        val previous = layoutTops[index]
        layoutTops[index] = top
        if (settings == null || !previous.isFinite() || !preserveScreenPosition) return
        val delta = top - previous - anchorCorrection
        if (delta == 0.0) return
        positions[index] += delta
        offsets[index].floatValue = (base - positions[index]).toFloat() + contentOffsets[index]
        active = true
    }

    /** Apply one measured content-height change to every following retained item exactly once. */
    fun applyContentShift(afterIndex: Int, delta: Double) {
        if (delta == 0.0 || settings == null) return
        for (index in maxOf(first, afterIndex + 1) until end) {
            contentOffsets[index] -= delta.toFloat()
            if (layoutTops[index].isFinite()) layoutTops[index] += delta
            offsets[index].floatValue = (base - positions[index]).toFloat() + contentOffsets[index]
            active = true
        }
    }

    /** Leading vocals shift content inside their owner, independently of the item's list top. */
    fun applyLeadingContentShift(index: Int, delta: Double) {
        if (delta == 0.0 || settings == null || index !in first until end) return
        leadingContentOffsets[index] -= delta.toFloat()
        leadingOffsets[index].floatValue = leadingContentOffsets[index]
        active = true
    }

    // Dots precede the whole owner; backing vocals precede only its main text. Their offsets must
    // stay independent when the dots disappear in the same frame that the backing vocals enter.
    fun applyBreathingContentShift(index: Int, delta: Double) {
        if (delta == 0.0 || settings == null || index !in first until end) return
        breathingContentOffsets[index] -= delta.toFloat()
        breathingOffsets[index].floatValue = breathingContentOffsets[index]
        active = true
    }

    fun rebase(position: Double) {
        val delta = position - base
        if (delta == 0.0) return
        for (i in first until end) positions[i] += delta
        base = position
    }

    fun follow(glide: Boolean) {
        this.glide = glide
    }

    fun focusAt(index: Int, bounceIndex: Int = -1) {
        // The preceding line leads; the incoming line springs behind it to open a brief gap.
        focus = if (glide) index else (index - 1).coerceAtLeast(0)
        followBounceFromIndex =
            if (glide || bounceIndex < 0) -1 else (bounceIndex - 1).coerceAtLeast(0)
    }

    fun offset(index: Int): Float = offsets.getOrNull(index)?.floatValue ?: 0f

    fun leadingOffset(index: Int): Float = leadingOffsets.getOrNull(index)?.floatValue ?: 0f

    fun breathingOffset(index: Int): Float = breathingOffsets.getOrNull(index)?.floatValue ?: 0f

    val retainedCount
        get() = end - first

    fun retentionExtentPx(): Int {
        if (!active || settings == null) return 0
        var previousBound = 0.0
        var maximum = 0.0
        for (i in first until end) {
            val distance =
                if (glide) 0 else (i - focus).coerceIn(0, stiffness.lastIndex)
            val extraBounce = !glide && followBounceFromIndex >= 0 && i >= followBounceFromIndex
            val rowStiffness =
                if (extraBounce) delayedFirstWordStiffness[distance] else stiffness[distance]
            val gain =
                if (extraBounce) delayedFirstWordCouplingGain[distance] else couplingGain[distance]
            // Energy bounds the free response; the impulse's absolute integral bounds the
            // neighbour's forcing, including future overshoot when displacement is currently zero.
            val response = amplitude(positions[i] - base, velocities[i], rowStiffness) +
                    if (!glide && i > focus && i > first) gain * previousBound else 0.0
            // moveTo/advance bound scroll displacement by the viewport; layout shifts can
            // temporarily exceed it before the next physics step and must remain retained.
            val bound = minOf(response, maxOf(limit.toDouble(), abs(positions[i] - base)))
            previousBound = bound
            maximum = maxOf(
                maximum,
                bound + amplitude(
                    contentOffsets[i].toDouble(),
                    contentVelocities[i],
                    contentSpring.stiffness
                ) +
                        amplitude(
                            leadingContentOffsets[i].toDouble(),
                            leadingContentVelocities[i],
                            contentSpring.stiffness
                        ) +
                        amplitude(
                            breathingContentOffsets[i].toDouble(),
                            breathingContentVelocities[i],
                            contentSpring.stiffness
                        ),
            )
        }
        return ceil(maximum).toInt()
    }

    private fun amplitude(displacement: Double, velocity: Float, stiffness: Float): Double =
        sqrt(displacement * displacement + velocity.toDouble() * velocity / stiffness)

    private fun impulseGain(stiffness: Float, damping: Float): Float {
        val dampedFrequencySquared = stiffness - damping * damping / 4f
        if (dampedFrequencySquared <= 0f) return 1f
        val decay = exp(-PI * damping / (2.0 * sqrt(dampedFrequencySquared.toDouble())))
        return ((1.0 + decay) / (1.0 - decay)).toFloat()
    }

    fun reset(position: Double) {
        base = position
        for (i in first until end) {
            positions[i] = base
            velocities[i] = 0f
            contentOffsets[i] = 0f
            contentVelocities[i] = 0f
            leadingContentOffsets[i] = 0f
            leadingContentVelocities[i] = 0f
            breathingContentOffsets[i] = 0f
            breathingContentVelocities[i] = 0f
            offsets[i].floatValue = 0f
            leadingOffsets[i].floatValue = 0f
            breathingOffsets[i].floatValue = 0f
        }
        active = false
    }

    fun moveTo(position: Double, cascade: Boolean) {
        if (!cascade || settings == null) {
            followBounceFromIndex = -1
            reset(position)
            return
        }
        val delta = position - base
        if (delta == 0.0) return
        base = position
        var moving = false
        for (i in first until end) {
            // Historical rows ride the scroll; the leaving row joins the spring for a delayed
            // first word. Existing offsets survive when a trailing row becomes the leader.
            if (!glide && i <= focus &&
                (followBounceFromIndex < 0 || i < followBounceFromIndex)
            ) positions[i] += delta
            val offset = (base - positions[i]).toFloat().coerceIn(-limit, limit)
            positions[i] = base - offset
            offsets[i].floatValue = offset + contentOffsets[i]
            if (abs(offset) > 0.08f || abs(velocities[i]) > 0.08f ||
                abs(contentOffsets[i]) > 0.08f || abs(contentVelocities[i]) > 0.08f ||
                abs(leadingContentOffsets[i]) > 0.08f || abs(leadingContentVelocities[i]) > 0.08f ||
                abs(breathingContentOffsets[i]) > 0.08f || abs(breathingContentVelocities[i]) > 0.08f
            ) moving = true
        }
        active = moving
    }

    fun advance(seconds: Float) {
        val config = settings ?: return
        if (!active || seconds <= 0f) return
        val dt = seconds.coerceAtMost(0.05f)
        val steps = ceil(dt / (1f / 240f)).toInt().coerceAtLeast(1)
        val step = dt / steps
        repeat(steps) {
            // Reverse traversal reads the previous substep's neighbour without a scratch array.
            for (i in end - 1 downTo first) {
                val distance =
                    if (glide) 0 else (i - focus).coerceAtLeast(0).coerceAtMost(stiffness.lastIndex)
                val neighbour =
                    if (!glide && i > focus && i > first) positions[i - 1] - base else 0.0
                val target = base + neighbour * config.coupling
                val extraBounce = !glide && followBounceFromIndex >= 0 && i >= followBounceFromIndex
                val rowStiffness =
                    if (extraBounce) delayedFirstWordStiffness[distance] else stiffness[distance]
                val rowDamping =
                    if (extraBounce) delayedFirstWordDamping[distance] else damping[distance]
                val acceleration =
                    -rowStiffness * (positions[i] - target) -
                            rowDamping * velocities[i]
                velocities[i] += (acceleration * step).toFloat()
                positions[i] += velocities[i] * step
                // Occupancy settles independently so normal follow motion keeps its response.
                contentVelocities[i] += (
                        -contentSpring.stiffness * contentOffsets[i] -
                                contentSpring.damping * contentVelocities[i]
                        ) * step
                contentOffsets[i] += contentVelocities[i] * step
                leadingContentVelocities[i] += (
                        -contentSpring.stiffness * leadingContentOffsets[i] -
                                contentSpring.damping * leadingContentVelocities[i]
                        ) * step
                leadingContentOffsets[i] += leadingContentVelocities[i] * step
                breathingContentVelocities[i] += (
                        -contentSpring.stiffness * breathingContentOffsets[i] -
                                contentSpring.damping * breathingContentVelocities[i]
                        ) * step
                breathingContentOffsets[i] += breathingContentVelocities[i] * step
                if (abs(positions[i] - base) > limit) {
                    positions[i] =
                        base + (positions[i] - base).coerceIn(-limit.toDouble(), limit.toDouble())
                    velocities[i] = 0f
                }
            }
        }
        var moving = false
        for (i in first until end) {
            if (abs(positions[i] - base) > 0.08 || abs(velocities[i]) > 0.08f ||
                abs(contentOffsets[i]) > 0.08f || abs(contentVelocities[i]) > 0.08f ||
                abs(leadingContentOffsets[i]) > 0.08f || abs(leadingContentVelocities[i]) > 0.08f ||
                abs(breathingContentOffsets[i]) > 0.08f || abs(breathingContentVelocities[i]) > 0.08f
            ) moving = true
            offsets[i].floatValue = (base - positions[i]).toFloat() + contentOffsets[i]
            leadingOffsets[i].floatValue = leadingContentOffsets[i]
            breathingOffsets[i].floatValue = breathingContentOffsets[i]
        }
        if (!moving) reset(base)
    }
}
