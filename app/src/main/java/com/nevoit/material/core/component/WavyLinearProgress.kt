/*
 * Wave path ported from androidx.compose.material3's LinearWavyProgressIndicator (Apache-2.0).
 * Material3 caches a full-amplitude path and slices segments out of it with a PathMeasure; we
 * rebuild the path per frame instead, which skips the measure entirely and lets the wave end
 * exactly on the progress head. A couple dozen curves per frame is cheaper than the bookkeeping.
 */
package com.nevoit.material.core.component

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs
import kotlin.math.floor

// The wave settles into a flat line whenever it is not travelling. No bounce: overshooting would
// push the crest past the centre line and read as the wave turning inside out on the way down.
private val SettleSpec: AnimationSpec<Float> = Springs.smooth<Float>(durationMillis = 400)

// A linear progress indicator whose filled part is a travelling wave. `progress` is read in the
// draw scope, so a snapshot-state caller never recomposes; `rolling` gates the frame callback.
// The two colours are producers for the same reason: they travel with the cover-derived theme,
// and taking them as values would drag the whole caller through every frame of that transition.
@Composable
fun WavyLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    activeColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.primary),
    trackColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.contentVariant),
    strokeWidth: Dp = 4.dp,
    trackStrokeWidth: Dp = strokeWidth,
    waveHeight: Dp = 10.dp,
    wavelength: Dp = 40.dp,
    waveSpeed: Dp = wavelength / 2,
    amplitude: Float = 1f,
    rolling: Boolean = false,
    interacting: Boolean = false,
    trackGap: Dp = 4.dp,
    playheadWidth: Dp = 4.dp,
    playheadHeight: Dp = 32.dp,
) {
    val density = LocalDensity.current
    val wavelengthPx = with(density) { wavelength.toPx() }
    val speedPx = with(density) { waveSpeed.toPx() }

    // Wave phase, advanced only while rolling so a paused track stays frozen at its offset.
    // Written from a frame callback and read in the draw scope: a running wave invalidates
    // drawing only, it never recomposes this composable or its caller.
    //
    // withInfiniteAnimationFrameNanos is NOT a loop despite the name — it just defers to
    // withFrameNanos and reports to InfiniteAnimationPolicy. The loop below is what keeps the
    // wave moving; leaving it out advances the phase by exactly one frame and stops.
    val phase = remember { mutableFloatStateOf(0f) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(rolling, wavelengthPx, speedPx, active) {
        if (!active || !rolling || wavelengthPx <= 0f || speedPx <= 0f) return@LaunchedEffect
        var previous = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (previous != 0L) {
                    val seconds = (now - previous) / 1_000_000_000.0
                    phase.floatValue =
                        (phase.floatValue + (seconds * speedPx).toFloat()) % wavelengthPx
                }
                previous = now
            }
        }
    }

    // The wave only travels while the track is actually running — pausing settles it flat, and
    // grabbing the bar does the same, because a rolling wave under the finger is just noise.
    val settled = interacting || !rolling
    val flatness = animateFloatAsState(
        targetValue = if (settled) 0f else amplitude,
        animationSpec = SettleSpec,
        label = "WavyLinearProgressAmplitude",
    )

    val path = remember { Path() }

    // Deliberately not clipped. Everything that draws inside stays within the horizontal bounds
    // already — the wave and the track end a cap width short of the edges, and the playhead is
    // coerced in. Vertically the playhead is the exception: its height is the caller's business,
    // and it is allowed to stand taller than the layout it is measured into.
    Canvas(modifier = modifier) {
        val width = size.width
        if (width <= 0f) return@Canvas

        // Read here, not at composition: a colour change then invalidates drawing only.
        val active = activeColor()
        val track = trackColor()

        val centerY = size.height / 2f
        val stroke = strokeWidth.toPx()
        val trackStroke = trackStrokeWidth.toPx()
        // Distance from the centre line to a crest. The stroke eats half its width at both ends,
        // so what is left of the requested band is the room the wave may actually use.
        val crest = ((waveHeight.toPx() - stroke) / 2f).coerceAtLeast(0f)

        // Room the round caps need at either end. Both the wave and the track have to stop half a
        // stroke short of the edges — a cap drawn on the edge gets clipped flush and the bar ends
        // in a flat cut instead of a round cap.
        val capWidth = maxOf(stroke, trackStroke) / 2f
        val left = capWidth
        val right = (width - capWidth).coerceAtLeast(left)

        // Mapped across [left, right] rather than clamped against it: clamping would leave a dead
        // zone at either end where progress moves but the head does not.
        val fraction = progress().coerceIn(0f, 1f)
        val head = left + fraction * (right - left)

        if (fraction > 0f) {
            drawWave(
                path = path,
                from = left,
                to = head,
                centerY = centerY,
                crest = crest * flatness.value,
                phase = phase.floatValue,
                wavelength = wavelengthPx,
                color = active,
                strokeWidth = stroke,
            )
        }

        // The track starts past the head and both of their round caps, so the two never touch.
        val trackStart =
            (if (fraction > 0f) head + trackGap.toPx() + capWidth * 2f else left)
                .coerceIn(left, right)
        if (trackStart < right) {
            drawLine(
                color = track,
                start = Offset(trackStart, centerY),
                end = Offset(right, centerY),
                strokeWidth = trackStroke,
                cap = StrokeCap.Round,
            )
        }

        // Playhead: a vertical bar on the head, there in every state. It is what carries the
        // position once the wave settles, so it never fades.
        val headWidth = playheadWidth.toPx()
        val halfHeadWidth = headWidth / 2f
        val halfHeadHeight = playheadHeight.toPx() / 2f
        // Both ends keep half a bar of clearance — at full progress the upper bound would fall
        // below the lower one and coerceIn would throw, hence the second guard.
        val headX = head.coerceIn(
            halfHeadWidth,
            (width - halfHeadWidth).coerceAtLeast(halfHeadWidth),
        )
        drawLine(
            color = active,
            start = Offset(headX, centerY - halfHeadHeight),
            end = Offset(headX, centerY + halfHeadHeight),
            strokeWidth = headWidth,
            cap = StrokeCap.Round,
        )
    }
}

// Plots the wave between `from` and `to`, reusing `path`.
//
// Anchors sit on the centre line half a wavelength apart, joined by one quadratic whose control
// point sits twice `crest` away (a quadratic peaks at half its control offset). They are placed
// at `phase + k * halfWave`, so advancing `phase` slides the whole wave sideways and `phase`
// wrapping by a full wavelength leaves the shape untouched.
//
// Both ends are cut where they fall, and the partial curve is rebuilt with de Casteljau so the
// path genuinely starts and stops there. That is what makes the ends round: the stroke's round
// cap closes them, and a cap only reads as a cap if the path ends inside the canvas. Letting a
// curve run past the edge instead gets it clipped into a flat cut.
private fun DrawScope.drawWave(
    path: Path,
    from: Float,
    to: Float,
    centerY: Float,
    crest: Float,
    phase: Float,
    wavelength: Float,
    color: Color,
    strokeWidth: Float,
) {
    if (to <= from) return

    path.rewind()
    val style = Stroke(width = strokeWidth, cap = StrokeCap.Round)

    val halfWave = wavelength / 2f
    if (crest <= 0.5f || halfWave <= 0f) {
        path.moveTo(from, centerY)
        path.lineTo(to, centerY)
        drawPath(path, color, style = style)
        return
    }

    // Index of the half wave that contains `from`. Anchors live at `phase + k * halfWave`.
    val first = floor((from - phase) / halfWave).toInt()
    var k = first
    while (true) {
        val x0 = phase + k * halfWave
        if (x0 >= to) break
        val x1 = x0 + halfWave

        // Every other half wave bulges the other way. `and 1` rather than `% 2`: k runs negative
        // whenever `from` sits left of the first anchor and Kotlin's % keeps the sign.
        val offset = if (k and 1 == 0) 2f * crest else -2f * crest

        // Trim the half wave to [from, to]; `start`/`end` are parameters along the full curve.
        val start = if (x0 < from) (from - x0) / halfWave else 0f
        val end = if (x1 > to) (to - x0) / halfWave else 1f
        val span = end - start

        // A quadratic trimmed to [start, end] is again a quadratic: its endpoints are the curve's
        // values there, and its control point is the start tangent carried half the span. On x the
        // anchors are evenly spaced, so B'(t) works out to a constant `halfWave` and x stays
        // linear — x(t) = x0 + t * halfWave.
        val startX = x0 + start * halfWave
        val endX = x0 + end * halfWave
        val controlX = x0 + (start + span / 2f) * halfWave
        val startY = centerY + 2f * start * (1f - start) * offset
        val endY = centerY + 2f * end * (1f - end) * offset
        val controlY = startY + span * (1f - 2f * start) * offset

        // Every segment after the first picks up exactly where the previous one ended on its own,
        // so only the very first needs an explicit move.
        if (k == first) path.moveTo(startX, startY)
        path.quadraticTo(controlX, controlY, endX, endY)

        if (end < 1f) break
        k++
    }

    drawPath(path, color, style = style)
}
