package com.nevoit.material.core.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds

// Ticker marquee. One pass carries a whole copy of the content out past the left edge while the
// next copy slides in from the right, then it pauses and repeats from the same position.
// accelerate/decelerate are ramp lengths in Dp (0 disables), velocity is in Dp/s.
//
// NOTE: a DrawModifierNode that follows a LayoutModifierNode in the same chain is drawn by the
// coordinator *below* it, so `size` inside drawWithContent is the content's intrinsic size, not
// the viewport this modifier resolved. Everything drawn here is therefore measured off the
// viewport captured during layout, and the content is clipped to it explicitly.
// The fade mask softens both edges of a *virtual* container: startFadeOffset/endFadeOffset
// grow that container past the viewport on either side. The content bleeds into that grown
// area (drawn past the node's layout bounds; whatever the parent itself clips is the
// parent's business) and each band fades it out at its virtual edge. The left band is
// animated with the pass: it fades in while the text is still at rest, stays on for the
// whole pass, and retires as soon as the leader has fully left the viewport (nothing is cut
// at the left edge anymore at that point), so a resting line never has its leading
// characters dimmed.
// restartKey is the content's identity: whenever it changes the pass restarts from rest
// (offset 0, band off) even if the new content happens to measure the same width.
@Composable
fun Modifier.marquee(
    velocity: Dp = 24.dp,
    accelerate: Dp = 24.dp,
    decelerate: Dp = 24.dp,
    spacing: Dp = 48.dp,
    pauseMillis: Int = 2000,
    fade: Boolean = true,
    fadeLength: Dp = 24.dp,
    startFadeOffset: Dp = 0.dp,
    endFadeOffset: Dp = 0.dp,
    fadeAnimMillis: Int = 500,
    initialDelayMillis: Int = pauseMillis,
    restartKey: Any? = null,
): Modifier {
    val density = LocalDensity.current
    val velocityPx = with(density) { velocity.toPx() }
    val acceleratePx = with(density) { accelerate.toPx() }
    val deceleratePx = with(density) { decelerate.toPx() }
    val spacingPx = with(density) { spacing.toPx() }
    val fadeLengthPx = with(density) { fadeLength.toPx() }
    val startFadeOffsetPx = with(density) { startFadeOffset.toPx() }
    val endFadeOffsetPx = with(density) { endFadeOffset.toPx() }

    val scroll = remember { mutableFloatStateOf(0f) }
    val edgeFade = remember { mutableFloatStateOf(0f) }
    val contentWidth = remember { mutableFloatStateOf(0f) }
    val viewportWidth = remember { mutableFloatStateOf(0f) }
    val viewportHeight = remember { mutableFloatStateOf(0f) }

    val content = contentWidth.floatValue
    val viewport = viewportWidth.floatValue

    LaunchedEffect(
        restartKey, velocityPx, acceleratePx, deceleratePx, spacingPx, pauseMillis,
        fade, fadeLengthPx, fadeAnimMillis, initialDelayMillis, content, viewport
    ) {
        // Every (re)start begins at rest: an in-flight pass and its lit left band are
        // dropped, never inherited by the next content.
        scroll.floatValue = 0f
        edgeFade.floatValue = 0f

        // Content that already fits never moves, and nothing gets masked.
        if (velocityPx <= 0f || viewport <= 0f || content <= viewport) {
            return@LaunchedEffect
        }

        val fading = fade && fadeLengthPx > 0f

        // Animates the left band alpha towards `target` from wherever it currently is, so an
        // effect restart mid-cycle (content changed) resumes instead of snapping.
        suspend fun animateEdgeFade(target: Float) {
            val start = edgeFade.floatValue
            val total = fadeAnimMillis / 1000f
            if (total <= 0f || start == target) {
                edgeFade.floatValue = target
                return
            }
            var t = 0f
            var lastFrame = 0L
            while (this@LaunchedEffect.isActive && t < total) {
                withFrameNanos { now ->
                    if (lastFrame != 0L) {
                        t += ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, MaxFrameSeconds)
                    }
                    lastFrame = now
                    val p = ease((t / total).coerceIn(0f, 1f))
                    edgeFade.floatValue = start + (target - start) * p
                }
            }
            edgeFade.floatValue = target
        }

        // One pass carries a whole copy out, leaving its follower flush to the left edge.
        val travel = content + spacingPx

        // Shrink both ramps proportionally so they can never overlap on a short travel.
        var rampIn = acceleratePx
        var rampOut = deceleratePx
        if (rampIn + rampOut > travel) {
            val k = travel / (rampIn + rampOut)
            rampIn *= k
            rampOut *= k
        }

        // Ramps run on elapsed time, not on distance. A distance driven ramp starts at zero
        // speed and then never covers any ground, which is what froze the animation before.
        // A smoothstep ramp covers half of its own length, hence the factor of two.
        val tIn = if (rampIn > 0f) 2f * rampIn / velocityPx else 0f
        val tOut = if (rampOut > 0f) 2f * rampOut / velocityPx else 0f
        val tMid = ((travel - rampIn - rampOut) / velocityPx).coerceAtLeast(0f)
        val duration = tIn + tMid + tOut

        var elapsed: Float
        var lastFrame: Long
        var firstPass = true
        while (isActive) {
            // A fresh line sits still and fully legible for a beat before its first pass;
            // later passes reuse pauseMillis below instead.
            if (firstPass) {
                if (initialDelayMillis > 0) delay(initialDelayMillis.milliseconds)
                firstPass = false
            }

            // The left band appears before anything moves, so the leading characters dim
            // first and only then start sliding out.
            if (fading) animateEdgeFade(1f)

            elapsed = 0f
            lastFrame = 0L
            // -1 until the leader is fully out; then it counts the retirement animation,
            // which runs inside the same frame loop so it overlaps the tail of the pass.
            var retireElapsed = -1f
            val retireTotal = fadeAnimMillis / 1000f
            while (isActive) {
                withFrameNanos { now ->
                    if (lastFrame != 0L) {
                        val dt = ((now - lastFrame) / 1_000_000_000f).coerceIn(
                            0f,
                            MaxFrameSeconds
                        )
                        elapsed += dt
                        if (retireElapsed >= 0f) retireElapsed += dt
                    }
                    lastFrame = now
                    val offset = when {
                        tIn > 0f && elapsed < tIn -> rampIn * ease(elapsed / tIn)

                        tOut > 0f && elapsed > tIn + tMid ->
                            travel - rampOut * ease(1f - (elapsed - tIn - tMid) / tOut)

                        else -> (rampIn + velocityPx * (elapsed - tIn)).coerceIn(0f, travel)
                    }
                    scroll.floatValue = offset
                    // Once the leader has fully left the viewport nothing is cut at the
                    // left edge anymore (the follower still slides in behind it), so the
                    // band starts retiring right there instead of after the whole pass.
                    if (fading) {
                        if (retireElapsed < 0f && offset >= content) retireElapsed = 0f
                        if (retireElapsed >= 0f) {
                            edgeFade.floatValue = if (retireTotal <= 0f) 0f
                            else 1f - ease((retireElapsed / retireTotal).coerceIn(0f, 1f))
                        }
                    }
                }
                if (elapsed >= duration) break
            }

            // The pass ends with the next copy flush to the left edge, which renders exactly
            // like the rest position, so the snap back to 0 is invisible. If the tail was
            // shorter than the retirement itself, finish retiring on the resting line.
            scroll.floatValue = 0f
            if (fading && edgeFade.floatValue > 0f) animateEdgeFade(0f)

            if (pauseMillis > 0) delay(pauseMillis.milliseconds)
        }
    }

    // The chain only captures the size states (stable) and the few params below, so build it
    // once per param change instead of on every recomposition of the caller (MiniPlayer
    // recomposes with playback state several times a second). The drawn content is read live
    // every frame, so no content identity is needed here.
    val chain = remember(fade, fadeLengthPx, spacingPx, startFadeOffsetPx, endFadeOffsetPx) {
        Modifier
            .layout { measurable, constraints ->
                // Measure the content unconstrained so it keeps its intrinsic (full) width. The
                // viewport is whatever the incoming constraints fix; never constrain to the
                // content, that is what made this node collapse to the text width.
                val placeable = measurable.measure(
                    Constraints(
                        minHeight = constraints.minHeight,
                        maxHeight = constraints.maxHeight
                    )
                )

                val width =
                    if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                val height = constraints.constrainHeight(placeable.height)

                contentWidth.floatValue = placeable.width.toFloat()
                viewportWidth.floatValue = width.toFloat()
                viewportHeight.floatValue = height.toFloat()

                layout(width, height) {
                    placeable.place(0, 0)
                }
            }
            .drawWithCache {
                // State reads here (the size triple) rebuild the cache when they change; scroll
                // and edgeFade are only read inside the draw lambda below, so scrolling never
                // re-runs this block.
                val width = viewportWidth.floatValue
                val height = viewportHeight.floatValue
                val content = contentWidth.floatValue

                if (width <= 0f || height <= 0f || content <= width) {
                    onDrawWithContent { drawContent() }
                } else {
                    // Virtual container: the real viewport grown by both offsets. The content is
                    // clipped to THIS rect, not the viewport, so it bleeds past the layout bounds
                    // until the bands fade it out at the virtual edges.
                    val start = -startFadeOffsetPx
                    val end = width + endFadeOffsetPx
                    val span = (end - start).coerceAtLeast(1f)
                    val band = fadeLengthPx.coerceAtMost(span / 2f)
                    val fading = fade && band > 0f

                    // The content itself is NOT recorded into a graphics layer: its look
                    // (text, color, style) changes through recomposition with no state the
                    // cache could observe, so a recording would go stale (colors frozen).
                    // drawContent() runs every frame instead; this block saves all the
                    // per-frame Brush/Size/Offset allocations. Masks are fixed: the left one
                    // is drawn with DstOut and alpha = edgeFade, which reproduces the old
                    // per-frame gradient (mask = 1 - edgeFade * ramp). Both bands sit at the
                    // virtual container edges; a positive offset slides its band (and the
                    // content bleed) past the layout bounds -- the parent's own clip decides
                    // how much of that is actually visible.
                    val leftMask = Brush.horizontalGradient(
                        0f to Color.Black,
                        1f to Color.Transparent,
                        startX = start,
                        endX = start + band
                    )
                    val rightMask = Brush.horizontalGradient(
                        0f to Color.Black,
                        1f - band / span to Color.Black,
                        1f to Color.Transparent,
                        startX = start,
                        endX = end
                    )

                    onDrawWithContent {
                        val offset = scroll.floatValue
                        val advance = content + spacingPx - offset
                        // Copies fully outside the virtual container are skipped.
                        val drawLeader = content - offset > start
                        val drawFollower = advance < end

                        if (!fading) {
                            clipRect(0f, 0f, width, height) {
                                if (drawLeader) translate(left = -offset) { this@onDrawWithContent.drawContent() }
                                if (drawFollower) translate(left = advance) { this@onDrawWithContent.drawContent() }
                            }
                        } else {
                            val canvas = drawContext.canvas
                            canvas.saveLayer(Rect(start, 0f, end, height), MaskLayerPaint)
                            try {
                                clipRect(start, 0f, end, height) {
                                    if (drawLeader) translate(left = -offset) { this@onDrawWithContent.drawContent() }
                                    if (drawFollower) translate(left = advance) { this@onDrawWithContent.drawContent() }
                                }
                                drawRect(
                                    brush = leftMask,
                                    topLeft = Offset(start, 0f),
                                    size = Size(band, height),
                                    alpha = edgeFade.floatValue,
                                    blendMode = BlendMode.DstOut
                                )
                                drawRect(
                                    brush = rightMask,
                                    topLeft = Offset(start, 0f),
                                    size = Size(span, height),
                                    blendMode = BlendMode.DstIn
                                )
                            } finally {
                                canvas.restore()
                            }
                        }
                    }
                }
            }
    }
    return this.then(chain)
}

private val MaskLayerPaint = Paint()

private const val MaxFrameSeconds = 0.05f

// Smoothstep integral: ramps from 0 to 1 with zero slope at both ends and slope 2 at x = 1.
private fun ease(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * t * (2f - t)
}
