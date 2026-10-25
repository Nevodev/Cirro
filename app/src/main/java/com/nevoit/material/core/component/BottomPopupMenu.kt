package com.nevoit.material.core.component

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nevoit.kinetic.layout.rememberKineticFlingBehavior
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.navigation.shape.deviceCornerShape
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.local.ProvideContentColor
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class BottomPopupMenuScope internal constructor(
    private val onDismiss: () -> Unit,
    val contentColor: ColorProducer,
    val contentVariantColor: ColorProducer,
    val actionContainerColor: ColorProducer,
    val actionContentColor: ColorProducer,
) {
    fun dismiss() = onDismiss()
}

object BottomPopupMenuDefaults {
    val Padding = 8.dp
}

internal class BottomPopupMenuRequest(
    val owner: Any,
    val originInWindow: Offset,
    val padding: Dp,
    val shape: androidx.compose.runtime.State<Shape>,
    val content: @Composable BottomPopupMenuScope.() -> Unit,
)

internal class BottomPopupMenuHostState {
    var request: BottomPopupMenuRequest? by mutableStateOf(null)
        private set
    var expanded: Boolean by mutableStateOf(false)
        private set

    fun show(request: BottomPopupMenuRequest) {
        this.request = request
        expanded = true
    }

    fun dismiss(owner: Any) {
        if (request?.owner === owner) expanded = false
    }

    fun finishDismiss(request: BottomPopupMenuRequest) {
        // A replaced menu or a reopened menu must survive an older exit animation's completion.
        if (this.request === request && !expanded) this.request = null
    }
}

private val LocalBottomPopupMenuHost = staticCompositionLocalOf<BottomPopupMenuHostState> {
    error("BottomPopupMenu must be inside BottomPopupMenuHost")
}

/** [content] receives the callback to attach to the caller's button. */
@Composable
fun BottomPopupMenu(
    menuContent: @Composable BottomPopupMenuScope.() -> Unit,
    modifier: Modifier = Modifier,
    padding: Dp = BottomPopupMenuDefaults.Padding,
    shape: Shape = deviceCornerShape(padding = padding, minRadius = 24.dp),
    enabled: Boolean = true,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    require(padding.value.isFinite() && padding >= 0.dp)
    val host = LocalBottomPopupMenuHost.current
    val owner = remember { Any() }
    val origin = remember { mutableStateOf<Offset?>(null) }
    val latestMenu = rememberUpdatedState(menuContent)
    val latestShape = rememberUpdatedState(shape)
    val callerLocals = currentCompositionLocalContext

    val openMenu = remember(host, owner, padding, enabled, callerLocals) {
        {
            val center = origin.value
            if (enabled && center != null) {
                host.show(
                    BottomPopupMenuRequest(owner, center, padding, latestShape) {
                        CompositionLocalProvider(callerLocals) {
                            ProvideContentColor(MaterialTheme.colors.content) {
                                latestMenu.value(this@BottomPopupMenuRequest)
                            }
                        }
                    }
                )
            }
        }
    }
    DisposableEffect(host, owner) {
        onDispose { host.dismiss(owner) }
    }
    LaunchedEffect(enabled) {
        if (!enabled) host.dismiss(owner)
    }
    Box(modifier = modifier.onGloballyPositioned { origin.value = it.boundsInWindow().center }) {
        content(openMenu)
    }
}

@Composable
fun BottomPopupMenuHost(
    modifier: Modifier = Modifier,
    scrimColor: Color = Color.Black.copy(alpha = 0.2f),
    containerColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.elevatedCardBackground),
    contentColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.content),
    contentVariantColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.contentVariant),
    actionContainerColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.menuActionBackground),
    actionContentColor: ColorProducer = rememberColorProducer(MaterialTheme.colors.onMenuAction),
    content: @Composable BoxScope.() -> Unit,
) {
    val state = remember { BottomPopupMenuHostState() }
    val window = LocalActivity.current?.window
    val view = LocalView.current
    val menuExpanded = state.expanded
    DisposableEffect(window, view, menuExpanded) {
        if (window == null || !menuExpanded) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val navigationBars = WindowInsetsCompat.Type.navigationBars()
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(navigationBars)
        onDispose {
            controller.systemBarsBehavior = previousBehavior
            // A rapidly reopened menu can capture stale visibility while the bar is reappearing.
            controller.show(navigationBars)
        }
    }
    CompositionLocalProvider(LocalBottomPopupMenuHost provides state) {
        Box(
            modifier = modifier
                .fillMaxSize(),
        ) {
            content()
            val request = state.request
            if (request != null) {
                key(request) {
                    BottomPopupMenuLayer(
                        request = request,
                        expanded = state.expanded,
                        scrimColor = scrimColor,
                        containerColor = containerColor,
                        contentColor = contentColor,
                        contentVariantColor = contentVariantColor,
                        actionContainerColor = actionContainerColor,
                        actionContentColor = actionContentColor,
                        onDismiss = { state.dismiss(request.owner) },
                        onClosed = { state.finishDismiss(request) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomPopupMenuLayer(
    request: BottomPopupMenuRequest,
    expanded: Boolean,
    scrimColor: Color,
    containerColor: ColorProducer,
    contentColor: ColorProducer,
    contentVariantColor: ColorProducer,
    actionContainerColor: ColorProducer,
    actionContentColor: ColorProducer,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
) {
    val translationProgress = remember { Animatable(0f) }
    val scaleProgress = remember { Animatable(0f) }
    val opacity = remember { Animatable(0f) }
    val menuLayer = rememberGraphicsLayer()
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var viewportPosition by remember { mutableStateOf(Offset.Zero) }

    val menuScope = remember(
        onDismiss,
        contentColor,
        contentVariantColor,
        actionContainerColor,
        actionContentColor,
    ) {
        BottomPopupMenuScope(
            onDismiss,
            contentColor,
            contentVariantColor,
            actionContainerColor,
            actionContentColor,
        )
    }
    // Keyed on "has a size" rather than on the size itself: the content can change size while the
    // menu closes, and re-keying here would cancel and relaunch the exit animation mid-flight.
    LaunchedEffect(expanded, menuSize != IntSize.Zero) {
        if (menuSize == IntSize.Zero) {
            if (!expanded) onClosed()
            return@LaunchedEffect
        }
        coroutineScope {
            launch {
                translationProgress.animateTo(
                    targetValue = if (expanded) 1f else 0f,
                    animationSpec = Springs.smooth(
                        durationMillis = if (expanded) 300 else 400,
                        extraBounce = 0.1,
                        visibilityThreshold = 0.0001f,
                    ),
                )
            }
            launch {
                scaleProgress.animateTo(
                    targetValue = if (expanded) 1f else 0f,
                    animationSpec = Springs.smooth(
                        durationMillis = if (expanded) 300 else 400,
                        visibilityThreshold = 0.0001f,
                    ),
                )
            }
            launch {
                opacity.animateTo(
                    targetValue = if (expanded) 1f else 0f,
                    animationSpec = if (expanded) {
                        Springs.smooth(durationMillis = 200, visibilityThreshold = 0.0001f)
                    } else {
                        tween(durationMillis = 150)
                    },
                )
            }
        }
        if (!expanded) onClosed()
    }
    BackHandler(enabled = expanded, onBack = onDismiss)
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = opacity.value.coerceIn(0f, 1f) }
                .background(scrimColor)
                .then(
                    if (expanded) {
                        Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismiss,
                            )
                            .semantics { contentDescription = "关闭菜单" }
                    } else {
                        Modifier
                    }
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(request.padding)
                .consumeWindowInsets(PaddingValues(request.padding))
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    .onGloballyPositioned { viewportPosition = it.positionInWindow() },
            ) {
                Layout(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { if (expanded) menuSize = it }
                        .graphicsLayer {
                            val scale = scaleProgress.value
                            val translation = bottomPopupMenuTranslation(
                                request.originInWindow,
                                viewportPosition,
                                viewportSize,
                                menuSize,
                                translationProgress.value,
                            )
                            transformOrigin = TransformOrigin.Center
                            scaleX = scale
                            scaleY = scale
                            translationX = translation.x
                            translationY = translation.y
                            alpha = opacity.value.coerceIn(0f, 1f)
                            this.shape = request.shape.value
                            clip = true
                        }
                        .semantics {
                            paneTitle = "菜单"
                            isTraversalGroup = true
                        }
                        .drawWithContent {
                            if (expanded) {
                                menuLayer.record { this@drawWithContent.drawContent() }
                            }
                            drawLayer(menuLayer)
                        }
                        .drawBehind { drawRect(containerColor()) },
                    content = {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable(interactionSource = null, indication = null) {})
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(
                                        state = rememberScrollState(),
                                        flingBehavior = rememberKineticFlingBehavior(),
                                        overscrollEffect = rememberOverscrollEffect()
                                    )
                            ) {
                                request.content(menuScope)
                            }
                        }
                    },
                ) { measurables, constraints ->
                    val menu = measurables.single().measure(constraints)
                    // The exit draws the snapshot recorded while expanded, so the node has to report
                    // that snapshot's size as well: measuring the live content can give a different
                    // height (a like resolving, a submenu collapsing on the closing frame) and
                    // `drawLayer` anchors at the top-left, so the excess shows up as a blank strip
                    // at the bottom. Unplaced children cannot intercept taps — that is exactly why
                    // the exit never places them — so nothing is lost by ignoring the measured size.
                    val snapshot = menuLayer.size
                    val useSnapshot = !expanded && snapshot.width > 0
                    layout(
                        width = if (useSnapshot) snapshot.width else menu.width,
                        height = if (useSnapshot) snapshot.height else menu.height,
                    ) {
                        // Exit keeps the recorded drawing, but unplaced children cannot intercept taps.
                        if (expanded) menu.place(0, 0)
                    }
                }
            }
        }
    }
}

internal fun bottomPopupMenuTranslation(
    originInWindow: Offset,
    viewportPositionInWindow: Offset,
    viewportSize: IntSize,
    menuSize: IntSize,
    progress: Float,
): Offset {
    // The viewport excludes the status bar and menu padding. Transformed menu bounds would feed
    // the animation back into its own coordinate calculation.
    val menuCenter = viewportPositionInWindow +
            Offset(viewportSize.width / 2f, viewportSize.height - menuSize.height / 2f)
    return (originInWindow - menuCenter) * (1f - progress)
}
