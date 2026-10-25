package com.nevoit.cirro.playback

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle
import com.nevoit.material.clip.cornerMaskClip
import com.nevoit.material.navigation.shape.deviceCornerShape

internal val MiniPlayerCoverRadius = 8.dp

class PlayerExpansionState internal constructor(
    internal val controller: PlayerUiController,
    internal val foregroundLayer: GraphicsLayer,
    internal val sourceCoverLayer: GraphicsLayer,
    internal val sourceColor: ColorProducer,
) {
    private var sourceCoordinates by mutableStateOf<LayoutCoordinates?>(null)
    private var sourceCoverCoordinates by mutableStateOf<LayoutCoordinates?>(null)
    private var underlayCoordinates by mutableStateOf<LayoutCoordinates?>(null)
    private var pageCoordinates by mutableStateOf<LayoutCoordinates?>(null)
    private var contentCoordinates by mutableStateOf<LayoutCoordinates?>(null)
    private var targetCoverCoordinates by mutableStateOf<LayoutCoordinates?>(null)
    private var pagePlaced by mutableStateOf(false)
    private var targetCoverPlaced by mutableStateOf(false)
    private var takenOver by mutableStateOf(false)

    internal var sourceBounds by mutableStateOf(Rect.Zero)
        private set
    internal var sourceCoverBounds by mutableStateOf(Rect.Zero)
        private set
    internal var targetCoverBounds by mutableStateOf(Rect.Zero)
        private set
    internal var pageSize by mutableStateOf(IntSize.Zero)
        private set
    internal var targetCoverLayer by mutableStateOf<GraphicsLayer?>(null)
    internal var targetCoverTransform by mutableStateOf<(GraphicsLayer.() -> Unit)?>(null)
    internal var targetCoverBackground by mutableStateOf(Color.Transparent)
    internal var hasCover by mutableStateOf(false)

    internal val ownsVisual: Boolean get() = controller.present && takenOver
    internal val ready: Boolean
        get() = pagePlaced && contentCoordinates?.isAttached == true && targetCoverPlaced &&
            targetCoverLayer != null && !sourceBounds.isEmpty && !sourceCoverBounds.isEmpty

    internal fun onSourcePositioned(coordinates: LayoutCoordinates) {
        sourceCoordinates = coordinates
        updateGeometry()
    }

    internal fun onUnderlayPositioned(coordinates: LayoutCoordinates) {
        underlayCoordinates = coordinates
        updateGeometry()
    }

    internal fun onSourceCoverPositioned(coordinates: LayoutCoordinates) {
        sourceCoverCoordinates = coordinates
        updateGeometry()
    }

    internal fun onPagePositioned(coordinates: LayoutCoordinates) {
        pageCoordinates = coordinates
        pageSize = coordinates.size
        updateGeometry()
    }

    internal fun onTargetCoverPositioned(coordinates: LayoutCoordinates) {
        targetCoverCoordinates = coordinates
        targetCoverPlaced = true
        updateGeometry()
    }

    internal fun onContentPositioned(coordinates: LayoutCoordinates) {
        contentCoordinates = coordinates
        updateGeometry()
    }

    internal fun onPagePlaced() {
        pagePlaced = true
    }

    internal fun takeOver() {
        takenOver = true
    }

    internal fun releasePage() {
        takenOver = false
        pagePlaced = false
        targetCoverPlaced = false
        pageCoordinates = null
        contentCoordinates = null
        targetCoverCoordinates = null
        targetCoverLayer = null
        targetCoverTransform = null
        targetCoverBounds = Rect.Zero
    }

    internal fun bounds(size: Size): Rect {
        val progress = controller.progress.coerceIn(0f, 1f)
        val source = sourceBounds
        return Rect(
            lerp(source.left, 0f, progress),
            lerp(source.top, 0f, progress),
            lerp(source.right, size.width, progress),
            lerp(source.bottom, size.height, progress),
        )
    }

    private fun updateGeometry() {
        val page = pageCoordinates?.takeIf { it.isAttached } ?: return
        // Both roots fill the same viewport. Sampling inside the underlay cancels its animated
        // scale, keeping the mini-player's transition anchors independent of that scale.
        val sourceRoot = underlayCoordinates?.takeIf { it.isAttached } ?: page
        sourceCoordinates?.takeIf { it.isAttached }?.let {
            sourceBounds = sourceRoot.localBoundingBoxOf(it, clipBounds = false)
        }
        sourceCoverCoordinates?.takeIf { it.isAttached }?.let {
            sourceCoverBounds = sourceRoot.localBoundingBoxOf(it, clipBounds = false)
        }
        val content = contentCoordinates?.takeIf { it.isAttached } ?: return
        // The content moves under the clip. Sampling the target in its own content coordinates
        // keeps that motion out of the next frame's interpolation.
        targetCoverCoordinates?.takeIf { it.isAttached }?.let {
            targetCoverBounds = content.localBoundingBoxOf(it, clipBounds = false)
        }
    }
}

@Composable
internal fun rememberPlayerExpansionState(
    controller: PlayerUiController,
    sourceColor: ColorProducer,
): PlayerExpansionState {
    val foreground = rememberGraphicsLayer()
    val sourceCover = rememberGraphicsLayer()
    return remember(controller, foreground, sourceCover, sourceColor) {
        PlayerExpansionState(controller, foreground, sourceCover, sourceColor)
    }
}

@Composable
internal fun PlayerExpansion(
    state: PlayerExpansionState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val deviceShape = deviceCornerShape()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val rectangle = remember { RoundedRectangle(0.dp) }
    val pageLayer = rememberGraphicsLayer()
    val coverPresentation = rememberGraphicsLayer()
    val coverFallback = rememberGraphicsLayer()
    val interactive = state.controller.acceptsInput
    val expansion = remember(state, deviceShape, density, direction, interactive, pageLayer, coverPresentation, coverFallback) {
        Modifier
            .onGloballyPositioned(state::onPagePositioned)
            .layout { measurable, constraints ->
                val page = measurable.measure(constraints)
                layout(page.width, page.height) {
                    page.place(0, 0)
                    state.onPagePlaced()
                }
            }
            .cornerMaskClip(
                bounds = state::bounds,
                shape = {
                    val progress = state.controller.progress.coerceIn(0f, 1f)
                    if (progress >= 1f) {
                        rectangle
                    } else {
                        val end = deviceShape.corners(state.pageSize.toSize(), direction, density)
                        val start = state.sourceBounds.height / 2f
                        val ltr = direction == LayoutDirection.Ltr
                        with(density) {
                            UnevenRoundedRectangle(
                                topStart = lerp(start, if (ltr) end.topLeft else end.topRight, progress).toDp(),
                                topEnd = lerp(start, if (ltr) end.topRight else end.topLeft, progress).toDp(),
                                bottomStart = lerp(start, if (ltr) end.bottomLeft else end.bottomRight, progress).toDp(),
                                bottomEnd = lerp(start, if (ltr) end.bottomRight else end.bottomLeft, progress).toDp(),
                            )
                        }
                    }
                },
            )
            .drawWithContent {
                if (!state.ownsVisual) return@drawWithContent
                if (interactive) {
                    drawContent()
                } else {
                    translate(top = state.bounds(size).top) { drawLayer(pageLayer) }
                }

                val progress = state.controller.progress.coerceIn(0f, 1f)
                val bounds = state.bounds(size)
                // Dissolve a solid colour over the already opaque page. Fading the page itself
                // would reveal the home screen and isolate the lyrics' additive blend layers.
                val wash = 1f - expansionFraction(progress, 0f, 0.7f)
                if (wash > 0f) drawRect(state.sourceColor(), alpha = wash)

                val foregroundAlpha = 1f - expansionFraction(progress, 0f, 0.3f)
                if (foregroundAlpha > 0f) {
                    val cover = state.sourceCoverBounds.translate(-state.sourceBounds.topLeft)
                    state.foregroundLayer.alpha = foregroundAlpha
                    translate(bounds.left, bounds.top) {
                        clipRect(cover.left, cover.top, cover.right, cover.bottom, ClipOp.Difference) {
                            drawLayer(state.foregroundLayer)
                        }
                    }
                }

                // The cover carries its own motion from the mini-player anchor. Keep its replay
                // in overlay coordinates so the content's top offset does not move it a second time.
                val target = state.targetCoverBounds
                state.targetCoverLayer?.let { cover ->
                    if (cover.size.width > 0 && cover.size.height > 0) {
                        coverPresentation.record(size = cover.size) {
                            drawRect(state.targetCoverBackground, alpha = progress)
                            val source = state.sourceCoverLayer
                            if (source.size.width > 0 && source.size.height > 0) {
                                coverFallback.record(size = cover.size) {
                                    scale(
                                        size.width / source.size.width,
                                        size.height / source.size.height,
                                        pivot = Offset.Zero,
                                    ) { drawLayer(source) }
                                }
                                coverFallback.alpha = if (state.hasCover) 1f else 1f - progress
                                drawLayer(coverFallback)
                            }
                            drawLayer(cover)
                        }
                        state.targetCoverTransform?.invoke(coverPresentation)
                        translate(target.left, target.top) { drawLayer(coverPresentation) }
                    }
                }
            }
            .then(if (interactive) Modifier.pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!state.ownsVisual || !state.bounds(size.toSize()).contains(down.position)) {
                        down.consume()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            } else Modifier)
    }
    Layout(modifier = modifier.then(expansion), content = {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = state.bounds(size).top }
                .drawWithContent {
                    pageLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(pageLayer)
                },
        ) {
            // This anchor must be a child of the moving layer. A callback on the layer's own
            // modifier can report its outer coordinator, leaking translation into the target bounds.
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned(state::onContentPositioned),
                content = content,
            )
        }
    }) { measurables, constraints ->
        val page = measurables.single().measure(constraints)
        layout(page.width, page.height) {
            // Exit replays the recorded page and moves the cover independently. Unplaced
            // controls cannot win hit testing over the mini-player or underlying navigation.
            if (interactive) page.place(0, 0)
        }
    }
}

private fun expansionFraction(progress: Float, start: Float, end: Float): Float {
    val fraction = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}
