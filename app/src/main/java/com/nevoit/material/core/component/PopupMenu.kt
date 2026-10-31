package com.nevoit.material.core.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.kinetic.layout.overscroll.OffsetOverscrollAnchor
import com.nevoit.kinetic.layout.overscroll.OffsetOverscrollBinding
import com.nevoit.kinetic.layout.overscroll.rememberOffsetOverscrollEffect
import com.nevoit.kinetic.layout.rememberKineticFlingBehavior
import com.nevoit.material.clip.cornerMaskClip
import com.nevoit.material.core.interaction.DimIndication
import com.nevoit.material.core.interaction.LocalIndicationColorProducer
import com.nevoit.material.core.interaction.rememberRetainedDimIndication
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

sealed interface PopupMenuEntry {
    data class Item(
        val text: String,
        val icon: Painter? = null,
        val subtitle: String? = null,
        val color: ColorProducer? = null,
        val subtitleColor: ColorProducer? = null,
        val enabled: Boolean = true,
        val submenu: List<PopupMenuEntry> = emptyList(),
        val leadingContent: (@Composable () -> Unit)? = null,
        val selected: Boolean = false,
        val onClick: () -> Unit = {},
    ) : PopupMenuEntry {
        val hasSubmenu: Boolean get() = submenu.isNotEmpty()
    }

    data class Divider(val color: ColorProducer? = null) : PopupMenuEntry
}

/** Start/end follow layout direction; the vertical side flips when the other side has more room. */
enum class PopupMenuAnchor { BottomStart, BottomCenter, BottomEnd, TopStart, TopCenter, TopEnd }

object PopupMenuDefaults {
    val Padding = 12.dp
    val Width = 280.dp
    val MinWidth = 224.dp
    val ItemMinHeight = 48.dp
    val Gap = 12.dp
}

internal class PopupMenuRequest(
    val owner: Any,
    val callerInWindow: Rect,
    val anchor: PopupMenuAnchor,
    val padding: Dp,
    val width: Dp?,
    val items: List<PopupMenuEntry>,
    val gap: Dp = PopupMenuDefaults.Gap,
    val containerColor: ColorProducer? = null,
    val contentColor: ColorProducer? = null,
    val contentVariantColor: ColorProducer? = null,
    val minWidth: Dp = PopupMenuDefaults.MinWidth,
    val offset: DpOffset = DpOffset.Zero,
)

internal class PopupMenuHostState {
    var request: PopupMenuRequest? by mutableStateOf(null)
        private set
    var expanded by mutableStateOf(false)
        private set

    fun show(request: PopupMenuRequest) {
        this.request = request
        expanded = true
    }

    fun dismiss(owner: Any) {
        if (request?.owner === owner) expanded = false
    }

    fun finishDismiss(request: PopupMenuRequest) {
        if (this.request === request && !expanded) this.request = null
    }
}

private val LocalPopupMenuHost = staticCompositionLocalOf<PopupMenuHostState> {
    error("PopupMenu must be inside PopupMenuHost or BottomPopupMenuHost")
}

/** Like [BottomPopupMenu], [content] receives the callback to attach to the caller. */
@Composable
fun PopupMenu(
    items: List<PopupMenuEntry>,
    modifier: Modifier = Modifier,
    anchor: PopupMenuAnchor = PopupMenuAnchor.BottomStart,
    padding: Dp = PopupMenuDefaults.Padding,
    width: Dp? = null,
    enabled: Boolean = true,
    gap: Dp = PopupMenuDefaults.Gap,
    containerColor: ColorProducer? = null,
    contentColor: ColorProducer? = null,
    contentVariantColor: ColorProducer? = null,
    minWidth: Dp = PopupMenuDefaults.MinWidth,
    /** Positive x moves toward layout end (left in RTL); positive y moves down. */
    offset: DpOffset = DpOffset.Zero,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    require(padding.value.isFinite() && padding >= 0.dp)
    require(width == null || (width.value.isFinite() && width > 0.dp))
    require(minWidth.value.isFinite() && minWidth >= 0.dp)
    require(gap.value.isFinite() && gap >= 0.dp)
    require(offset.x.value.isFinite() && offset.y.value.isFinite())
    val host = LocalPopupMenuHost.current
    val owner = remember { Any() }
    val bounds = remember { mutableStateOf<Rect?>(null) }
    val latestItems = rememberUpdatedState(items)
    val openMenu = remember(
        host, owner, anchor, padding, width, enabled, gap, minWidth, offset,
        containerColor, contentColor, contentVariantColor,
    ) {
        {
            val caller = bounds.value
            if (enabled && caller != null && latestItems.value.isNotEmpty()) {
                // Freeze this opening's data so asynchronous caller updates cannot relayout an entrance.
                host.show(
                    PopupMenuRequest(
                        owner,
                        caller,
                        anchor,
                        padding,
                        width,
                        latestItems.value.toList(),
                        gap,
                        containerColor,
                        contentColor,
                        contentVariantColor,
                        minWidth,
                        offset,
                    )
                )
            }
        }
    }
    DisposableEffect(host, owner) { onDispose { host.dismiss(owner) } }
    LaunchedEffect(enabled) { if (!enabled) host.dismiss(owner) }
    Box(modifier.onGloballyPositioned { bounds.value = it.boundsInWindow() }) {
        content(openMenu)
    }
}

@Composable
fun PopupMenuHost(
    modifier: Modifier = Modifier,
    containerColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.elevatedCardBackground),
    contentColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.content),
    contentVariantColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.contentVariant),
    content: @Composable BoxScope.() -> Unit,
) {
    val state = remember { PopupMenuHostState() }
    CompositionLocalProvider(LocalPopupMenuHost provides state) {
        Box(modifier.fillMaxSize()) {
            content()
            val request = state.request
            if (request != null) key(request) {
                val menuContentColor = request.contentColor ?: contentColor
                CompositionLocalProvider(LocalIndicationColorProducer provides menuContentColor) {
                    PopupMenuLayer(
                        request, state.expanded,
                        request.containerColor ?: containerColor,
                        menuContentColor,
                        request.contentVariantColor ?: contentVariantColor,
                        onDismiss = { state.dismiss(request.owner) },
                        onClosed = { state.finishDismiss(request) },
                    )
                }
            }
        }
    }
}

private class PopupMenuGeometry {
    var viewportPosition by mutableStateOf(Offset.Zero)
}

private class PopupMenuItemSlot(
    val contentColor: ColorProducer,
    val contentVariantColor: ColorProducer,
    val modifier: Modifier = Modifier,
    val enabled: Boolean,
    val arrowProgress: State<Float>? = null,
    val onClick: () -> Unit,
)

private class PopupMenuRow(val item: PopupMenuEntry.Item) {
    val interactionSource = MutableInteractionSource()
    var pointerPressed by mutableStateOf(false)
    var size = IntSize.Zero
    var snapshot: GraphicsLayer? = null

    // Move the entire row, including its indication node and remembered content, between pages.
    val content = movableContentOf<PopupMenuItemSlot> { slot ->
        val defaultIndication = LocalIndication.current
        val indication = if (item.hasSubmenu && defaultIndication is DimIndication) {
            rememberRetainedDimIndication(defaultIndication, interactionSource)
        } else defaultIndication
        val modifier = if (item.hasSubmenu) {
            val layer = rememberGraphicsLayer()
            DisposableEffect(layer) {
                snapshot = layer
                onDispose { snapshot = null }
            }
            slot.modifier
                .onGloballyPositioned { size = it.size }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
        } else slot.modifier
        PopupMenuItem(
            item = item,
            contentColor = slot.contentColor,
            contentVariantColor = slot.contentVariantColor,
            modifier = modifier.then(
                if (slot.enabled && item.enabled) Modifier.trackMenuRowPress(this@PopupMenuRow)
                else Modifier
            ),
            enabled = slot.enabled,
            arrowProgress = slot.arrowProgress,
            interactionSource = interactionSource,
            indication = indication,
            onClick = slot.onClick,
        )
    }
}

private fun Modifier.trackMenuRowPress(row: PopupMenuRow): Modifier = pointerInput(row) {
    awaitEachGesture {
        // Observe DOWN before clickable's optional scroll delay; the row must survive until UP.
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        row.pointerPressed = true
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        } finally {
            row.pointerPressed = false
        }
    }
}

private class PopupMenuPage(
    val items: List<PopupMenuEntry>,
    val headerRow: PopupMenuRow? = null,
    val sourceBounds: Rect? = null,
) {
    val header get() = headerRow?.item
    val rows = items.map { (it as? PopupMenuEntry.Item)?.let(::PopupMenuRow) }
    val reveal = Animatable(0f)
    val placed = CompletableDeferred<Unit>()
    var exiting by mutableStateOf(false)
    var collapsed by mutableStateOf(false)
    var targetBounds by mutableStateOf(Rect.Zero)
    var transformOrigin by mutableStateOf(TransformOrigin.Center)

    fun frame(): Rect = sourceBounds?.let {
        popupSubmenuFrame(it, targetBounds, reveal.value)
    } ?: targetBounds
}

@Composable
private fun PopupMenuLayer(
    request: PopupMenuRequest,
    expanded: Boolean,
    containerColor: ColorProducer,
    contentColor: ColorProducer,
    contentVariantColor: ColorProducer,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val opacity = remember { Animatable(0f) }
    val geometry = remember { PopupMenuGeometry() }
    val root = remember { PopupMenuPage(request.items) }
    var pages by remember { mutableStateOf<List<PopupMenuPage>>(emptyList()) }
    fun closeCurrentMenu() {
        val current = pages.lastOrNull { !it.exiting }
        if (current == null) onDismiss() else current.exiting = true
    }
    LaunchedEffect(expanded) {
        if (expanded) root.placed.await()
        val scaleSpec = Springs.smooth(
            durationMillis = if (expanded) 300 else 250,
            extraBounce = if (expanded) 0.15 else 0.0,
            visibilityThreshold = 0.0001f,
        )
        coroutineScope {
            launch { progress.animateTo(if (expanded) 1f else 0f, scaleSpec) }
            launch {
                opacity.animateTo(
                    if (expanded) 1f else 0f,
                    if (expanded) scaleSpec else tween(durationMillis = 150),
                )
            }
        }
        if (!expanded) onClosed()
    }
    BackHandler(enabled = expanded, onBack = ::closeCurrentMenu)
    Box(Modifier.fillMaxSize()) {
        if (expanded) Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = ::closeCurrentMenu,
                )
                .semantics {
                    contentDescription =
                        if (pages.none { !it.exiting }) "关闭菜单" else "返回上一级菜单"
                },
        )
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars))
                .padding(request.padding)
                .consumeWindowInsets(PaddingValues(request.padding))
                .onGloballyPositioned { geometry.viewportPosition = it.positionInWindow() },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // All pages share the entrance transform. Their geometry stays in the
                        // unscaled safe viewport even when a submenu opens before entrance settles.
                        val pivot = root.targetBounds.topLeft + Offset(
                            root.targetBounds.width * root.transformOrigin.pivotFractionX,
                            root.targetBounds.height * root.transformOrigin.pivotFractionY,
                        )
                        transformOrigin = TransformOrigin(
                            if (size.width > 0f) pivot.x / size.width else 0f,
                            if (size.height > 0f) pivot.y / size.height else 0f,
                        )
                        scaleX = progress.value
                        scaleY = progress.value
                    },
            ) {
                val stack = listOf(root) + pages
                val inputIndex = stack.indexOfLast { !it.exiting }
                stack.forEachIndexed { index, page ->
                    key(page) {
                        val active = index == inputIndex
                        val child = stack.getOrNull(index + 1)
                        val headerPressed = page.headerRow?.interactionSource
                            ?.collectIsPressedAsState()?.value ?: false
                        val headerPointerPressed = page.headerRow?.pointerPressed ?: false
                        if (page !== root) LaunchedEffect(page.exiting, expanded) {
                            if (!expanded) return@LaunchedEffect
                            page.collapsed = false
                            if (!page.exiting) page.placed.await()
                            page.reveal.animateTo(
                                if (page.exiting) 0f else 1f,
                                Springs.smooth(
                                    durationMillis = 300,
                                    visibilityThreshold = 0.0001f
                                ),
                            )
                            page.collapsed = page.exiting
                        }
                        if (page !== root) LaunchedEffect(
                            page.collapsed,
                            page.exiting,
                            expanded,
                            headerPressed,
                            headerPointerPressed,
                        ) {
                            // Reparenting detaches clickable and cancels an in-flight tap. Keep the
                            // collapsed page until release so a late press can still reverse its exit.
                            if (expanded && page.collapsed && page.exiting &&
                                !headerPressed && !headerPointerPressed &&
                                pages.lastOrNull() === page
                            ) pages = pages.dropLast(1)
                        }
                        PopupMenuSurface(
                            page = page,
                            child = child,
                            request = request,
                            geometry = geometry,
                            expanded = expanded,
                            active = active,
                            opacity = opacity.asState(),
                            containerColor = containerColor,
                            contentColor = contentColor,
                            contentVariantColor = contentVariantColor,
                            onBack = { page.exiting = !page.exiting },
                            onItem = { row, bounds ->
                                val entry = row.item
                                val current = pages.lastOrNull { !it.exiting } ?: root
                                if (expanded && current === page) {
                                    if (entry.hasSubmenu) {
                                        val returning = pages.getOrNull(index)
                                        if (returning?.headerRow === row && returning.exiting) {
                                            // Reverse the retained layer instead of jumping back to a new entrance.
                                            returning.exiting = false
                                        } else {
                                            pages = pages.take(index) + PopupMenuPage(
                                                entry.submenu,
                                                row,
                                                bounds
                                            )
                                        }
                                    } else {
                                        onDismiss()
                                        entry.onClick()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PopupMenuSurface(
    page: PopupMenuPage,
    child: PopupMenuPage?,
    request: PopupMenuRequest,
    geometry: PopupMenuGeometry,
    expanded: Boolean,
    active: Boolean,
    opacity: State<Float>,
    containerColor: ColorProducer,
    contentColor: ColorProducer,
    contentVariantColor: ColorProducer,
    onBack: () -> Unit,
    onItem: (PopupMenuRow, Rect) -> Unit,
) {
    val childReveal = child?.reveal?.asState()
    val snapshot = rememberGraphicsLayer()
    val shape = remember { RoundedRectangle(20.dp) }
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    var menuCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val live = expanded && (active || page.exiting)
    val acceptsInput = expanded && active && !page.exiting
    val shadow = remember(shape, layoutDirection) { PopupMenuShadow(shape, layoutDirection) }
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Layout(
                modifier = Modifier
                    .then(
                        page.sourceBounds?.let { source ->
                            Modifier.width(with(density) { source.width.toDp() })
                        } ?: request.width?.let { width ->
                            Modifier
                                .widthIn(min = request.minWidth)
                                .width(width)
                        } ?: Modifier
                            .widthIn(
                                min = request.minWidth,
                                max = maxOf(request.minWidth, PopupMenuDefaults.Width),
                            )
                            .width(IntrinsicSize.Max)
                    )
                    .onGloballyPositioned {
                        menuCoordinates = it
                        page.placed.complete(Unit)
                    }
                    .graphicsLayer {
                        val frame = page.frame()
                        val shrink = 1f - 0.06f * (childReveal?.value ?: 0f).coerceIn(0f, 1f)
                        transformOrigin = page.transformOrigin
                        scaleX = shrink
                        scaleY = shrink
                        translationX = frame.left - page.targetBounds.left
                        translationY = frame.top - page.targetBounds.top
                    }
                    .drawWithContent {
                        val frame = page.frame()
                        val reveal =
                            if (page.header == null) 1f else page.reveal.value.coerceIn(0f, 1f)
                        with(shadow) {
                            draw(frame.size, alpha = opacity.value.coerceIn(0f, 1f) * reveal)
                        }
                        drawContent()
                    }
                    // This layer contains only content; the external shadow must not share its alpha bounds.
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = opacity.value.coerceIn(0f, 1f)
                        val dim = 0.1f * (childReveal?.value ?: 0f).coerceIn(0f, 1f)
                        colorFilter = if (dim > 0f) {
                            ColorFilter.tint(Color.Black.copy(alpha = dim), BlendMode.SrcAtop)
                        } else null
                    }
                    .cornerMaskClip(
                        bounds = {
                            Rect(Offset.Zero, page.frame().size)
                        },
                        shape = { shape },
                    )
                    .semantics {
                        paneTitle = if (page.header == null) "菜单" else "子菜单"
                        isTraversalGroup = true
                        if (!active) hideFromAccessibility()
                    }
                    .drawWithContent {
                        if (live) snapshot.record { this@drawWithContent.drawContent() }
                        drawLayer(snapshot)
                    }
                    .drawBehind { drawRect(containerColor()) },
                content = {
                    val scrollState = rememberScrollState()
                    val overscrollEffect = rememberOffsetOverscrollEffect(remember(scrollState) {
                        object : OffsetOverscrollBinding {
                            override val state = scrollState
                            override val orientation = Orientation.Vertical
                            override val reverseLayout = false
                            override val layoutInfo get() = scrollState.maxValue
                            override fun visibleAnchors() =
                                listOf(OffsetOverscrollAnchor(Unit, -scrollState.value))
                        }
                    })
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (acceptsInput) Modifier.clickable(
                                    interactionSource = null,
                                    indication = null
                                ) {}
                                else Modifier
                            )
                            .then(
                                if (acceptsInput) Modifier.verticalScroll(
                                    scrollState,
                                    enabled = scrollState.canScrollBackward || scrollState.canScrollForward,
                                    flingBehavior = rememberKineticFlingBehavior(),
                                    overscrollEffect = overscrollEffect,
                                ) else Modifier.layout { measurable, constraints ->
                                    // Disabled scrollable nodes still win hit testing over lower pages.
                                    // Keep the scroll geometry and live drawing with no input nodes.
                                    val content = measurable.measure(
                                        constraints.copy(maxHeight = Constraints.Infinity),
                                    )
                                    layout(
                                        content.width,
                                        constraints.constrainHeight(content.height)
                                    ) {
                                        content.placeRelativeWithLayer(0, -scrollState.value) {
                                            translationY = overscrollEffect.displayedOffset.y
                                        }
                                    }
                                }
                            ),
                    ) {
                        page.headerRow?.let { row ->
                            row.content(
                                PopupMenuItemSlot(
                                    contentColor = contentColor,
                                    contentVariantColor = contentVariantColor,
                                    enabled = acceptsInput && child == null,
                                    arrowProgress = page.reveal.asState(),
                                    onClick = onBack,
                                )
                            )
                            PopupMenuDivider(null, contentVariantColor)
                        }
                        page.items.forEachIndexed { index, entry ->
                            when (entry) {
                                is PopupMenuEntry.Divider -> PopupMenuDivider(
                                    entry.color,
                                    contentVariantColor
                                )

                                is PopupMenuEntry.Item -> {
                                    val row = page.rows[index]!!
                                    val bounds = remember(entry) { mutableStateOf<Rect?>(null) }
                                    val modifier = Modifier.onGloballyPositioned {
                                        bounds.value = menuCoordinates?.localBoundingBoxOf(
                                            it, clipBounds = false,
                                        )?.translate(page.targetBounds.topLeft)
                                    }
                                    val onClick = { bounds.value?.let { onItem(row, it) }; Unit }
                                    if (child?.headerRow === row) {
                                        // Keep the parent's geometry and retained drawing while the live
                                        // row belongs to its child. The slot can reopen a closing child.
                                        Layout(
                                            content = {},
                                            modifier = modifier
                                                .drawWithContent { row.snapshot?.let { drawLayer(it) } }
                                                .then(
                                                    if (acceptsInput) Modifier
                                                        .trackMenuRowPress(row)
                                                        .clickable(
                                                            interactionSource = row.interactionSource,
                                                            indication = LocalIndication.current,
                                                            enabled = entry.enabled,
                                                            role = Role.Button,
                                                            onClick = onClick,
                                                        ) else Modifier
                                                )
                                                .semantics {
                                                    text = AnnotatedString(entry.text)
                                                },
                                        ) { _, constraints ->
                                            val width = if (constraints.hasBoundedWidth) {
                                                constraints.maxWidth
                                            } else {
                                                constraints.constrainWidth(row.size.width)
                                            }
                                            layout(
                                                width,
                                                constraints.constrainHeight(row.size.height)
                                            ) {}
                                        }
                                    } else row.content(
                                        PopupMenuItemSlot(
                                            contentColor = contentColor,
                                            contentVariantColor = contentVariantColor,
                                            modifier = modifier,
                                            enabled = acceptsInput,
                                            onClick = onClick,
                                        )
                                    )
                                }
                            }
                        }
                    }
                },
            ) { measurables, constraints ->
                // Content is always measured at its final size. The animated shell only reveals it;
                // covered/closing pages retain their recorded drawing without accepting input.
                val menu = if (live || snapshot.size == IntSize.Zero) {
                    measurables.single().measure(constraints)
                } else null
                val size = menu?.let { IntSize(it.width, it.height) } ?: snapshot.size
                layout(size.width, size.height) { if (live) menu?.place(0, 0) }
            }
        },
    ) { measurables, constraints ->
        val menu = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val viewportSize = IntSize(constraints.maxWidth, constraints.maxHeight)
        layout(constraints.maxWidth, constraints.maxHeight) {
            val source = page.sourceBounds
            if (source == null) {
                val placement = popupMenuPlacement(
                    request.callerInWindow,
                    geometry.viewportPosition,
                    viewportSize,
                    IntSize(menu.width, menu.height),
                    request.anchor,
                    layoutDirection,
                    request.gap.roundToPx(),
                    IntOffset(request.offset.x.roundToPx(), request.offset.y.roundToPx()),
                )
                page.transformOrigin = placement.transformOrigin
                page.targetBounds = Rect(
                    Offset(placement.position.x.toFloat(), placement.position.y.toFloat()),
                    Size(menu.width.toFloat(), menu.height.toFloat()),
                )
            } else {
                page.transformOrigin = TransformOrigin(0f, 0f)
                page.targetBounds = popupSubmenuTarget(
                    source,
                    viewportSize,
                    IntSize(menu.width, menu.height),
                    overlap = 12.dp.roundToPx(),
                )
            }
            menu.place(page.targetBounds.topLeft.round())
        }
    }
}

internal fun popupSubmenuTarget(
    source: Rect,
    viewport: IntSize,
    menu: IntSize,
    overlap: Int = 0,
    preferBelow: Boolean = true,
): Rect {
    // Preserve the preferred edge and shift the whole menu into the safe viewport.
    // Choosing the opposite edge based on height makes long submenus suddenly reverse.
    val top = if (preferBelow) source.top - overlap else source.bottom - menu.height + overlap
    return Rect(
        Offset(
            source.left.coerceIn(0f, (viewport.width - menu.width).coerceAtLeast(0).toFloat()),
            top.coerceIn(0f, (viewport.height - menu.height).coerceAtLeast(0).toFloat()),
        ),
        Size(menu.width.toFloat(), menu.height.toFloat()),
    )
}

internal fun popupSubmenuFrame(source: Rect, target: Rect, progress: Float): Rect =
    lerp(source, target, progress.coerceIn(0f, 1f))

@Composable
private fun PopupMenuItem(
    item: PopupMenuEntry.Item,
    contentColor: ColorProducer,
    contentVariantColor: ColorProducer,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    arrowProgress: State<Float>? = null,
    interactionSource: MutableInteractionSource,
    indication: Indication = LocalIndication.current,
    onClick: () -> Unit,
) {
    val color = item.color ?: contentColor
    val subtitleColor = item.subtitleColor ?: contentVariantColor
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = PopupMenuDefaults.ItemMinHeight)
            .semantics { selected = item.selected }
            .graphicsLayer { alpha = if (item.enabled) 1f else 0.5f }
            // Feedback outlives the input node while a submenu collapses.
            .indication(interactionSource, indication)
            .then(
                if (enabled) Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = item.enabled,
                    role = Role.Button,
                    onClick = onClick
                )
                else Modifier
            )
            .padding(
                start = if (item.leadingContent != null || item.icon != null) 12.dp else 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val leadingContent = item.leadingContent
        if (leadingContent != null) {
            leadingContent()
        } else item.icon?.let {
            Icon(
                it,
                tint = color,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                item.text,
                style = MaterialTheme.type.body.copy(textMotion = TextMotion.Animated),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.subtitle.isNullOrBlank()) {
                BasicText(
                    item.subtitle,
                    style = MaterialTheme.type.footnote.copy(textMotion = TextMotion.Animated),
                    color = subtitleColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.selected) {
            val indicatorColor = MaterialTheme.colors.menuSelectionIndicator
            Icon(
                painter = painterResource(R.drawable.checkmark),
                tint = if (indicatorColor != Color.Unspecified) rememberColorProducer(indicatorColor) else color,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
        if (item.hasSubmenu) Icon(
            rememberVectorPainter(InternalIcons.ChevronRight),
            tint = contentVariantColor,
            contentDescription = if (arrowProgress == null) "打开子菜单" else "收起子菜单",
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer {
                    rotationZ = 90f * (arrowProgress?.value ?: 0f)
                },
        )
    }
}

@Composable
private fun PopupMenuDivider(color: ColorProducer?, fallback: ColorProducer) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(1.dp)
            .drawBehind {
                drawRect(color?.invoke() ?: fallback().let { it.copy(alpha = it.alpha * 0.2f) })
            })
}

internal data class PopupMenuPlacement(
    val position: IntOffset,
    val transformOrigin: TransformOrigin,
)

internal fun popupMenuPlacement(
    callerInWindow: Rect,
    viewportPosition: Offset,
    viewportSize: IntSize,
    menuSize: IntSize,
    anchor: PopupMenuAnchor,
    layoutDirection: LayoutDirection,
    gap: Int = 0,
    offset: IntOffset = IntOffset.Zero,
): PopupMenuPlacement {
    val caller = callerInWindow.translate(-viewportPosition)
    val maxX = (viewportSize.width - menuSize.width).coerceAtLeast(0).toFloat()
    val maxY = (viewportSize.height - menuSize.height).coerceAtLeast(0).toFloat()
    val start =
        if (layoutDirection == LayoutDirection.Ltr) caller.left else caller.right - menuSize.width
    val end =
        if (layoutDirection == LayoutDirection.Ltr) caller.right - menuSize.width else caller.left
    val x = when (anchor) {
        PopupMenuAnchor.BottomStart, PopupMenuAnchor.TopStart -> start
        PopupMenuAnchor.BottomEnd, PopupMenuAnchor.TopEnd -> end
        else -> caller.center.x - menuSize.width / 2f
    }
    val above = caller.top - gap
    val below = viewportSize.height - caller.bottom - gap
    val preferBelow =
        anchor == PopupMenuAnchor.BottomStart || anchor == PopupMenuAnchor.BottomCenter ||
                anchor == PopupMenuAnchor.BottomEnd
    val useBelow = if (preferBelow) below >= menuSize.height || below >= above else
        !(above >= menuSize.height || above >= below)
    val y = if (useBelow) caller.bottom + gap else caller.top - menuSize.height - gap
    val startPivot = if (layoutDirection == LayoutDirection.Ltr) 0f else 1f
    val pivotX = when (anchor) {
        PopupMenuAnchor.BottomStart, PopupMenuAnchor.TopStart -> startPivot
        PopupMenuAnchor.BottomEnd, PopupMenuAnchor.TopEnd -> 1f - startPivot
        else -> 0.5f
    }
    return PopupMenuPlacement(
        position = Offset(
            (x + if (layoutDirection == LayoutDirection.Ltr) offset.x.toFloat() else -offset.x.toFloat())
                .coerceIn(0f, maxX),
            (y + offset.y.toFloat()).coerceIn(0f, maxY),
        ).round(),
        transformOrigin = TransformOrigin(pivotX, if (useBelow) 0f else 1f),
    )
}
