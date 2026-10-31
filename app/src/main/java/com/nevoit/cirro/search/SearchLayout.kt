package com.nevoit.cirro.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.nevoit.cirro.R
import com.nevoit.cirro.library.LibrarySongSearchHeight
import com.nevoit.cirro.ui.TopShade
import com.nevoit.kinetic.layout.KineticLazyRow
import com.nevoit.kinetic.layout.indicator.ScrollIndicatorSpec
import com.nevoit.kinetic.layout.overscroll.OverscrollVisualOffset
import com.nevoit.kinetic.layout.overscroll.rememberOffsetOverscrollEffect
import com.nevoit.kinetic.layout.snap.rememberKineticSnapFlingBehavior
import com.nevoit.material.core.component.CircleIconButton
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.layout.VerticalStack
import com.nevoit.material.core.layout.rememberNavigationTailPadding
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.flow.first
import kotlin.math.floor
import kotlin.math.roundToInt

private val SearchActiveHeaderHeight = 128.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchLayout(
    presentation: SearchPresentation,
    visible: Boolean,
    onSubmit: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val progress = presentation.activationProgress
    var idle by remember { mutableStateOf(!presentation.active) }
    var exitFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val controlsOpacity = remember { Animatable(if (presentation.active) 1f else 0f) }
    var controlsPresent by remember { mutableStateOf(presentation.active) }
    val focusRequester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    var wasImeVisible by remember { mutableStateOf(imeVisible) }
    var imeHidePending by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val currentSubmit by rememberUpdatedState(onSubmit)
    val currentVisible by rememberUpdatedState(visible)
    val density = LocalDensity.current
    val idleTop = with(density) { 160.dp.toPx() }
    val activeTop = with(density) { 12.dp.toPx() }
    val fieldHeight = with(density) { LibrarySongSearchHeight.roundToPx() }
    val actionGap = with(density) { 12.dp.roundToPx() }
    val closeInteractions = remember { MutableInteractionSource() }
    val contentLayer = rememberGraphicsLayer()

    LaunchedEffect(presentation.active, visible) {
        if (presentation.active && visible) {
            progress.animateTo(1f, Springs.crisp(visibilityThreshold = 0.0001f))
        } else {
            imeHidePending = imeVisible
            focus.clearFocus()
            keyboard?.hide()
            progress.animateTo(0f, Springs.crisp(visibilityThreshold = 0.0001f))
        }
    }
    LaunchedEffect(presentation.active, visible, idle) {
        if (presentation.active && visible && !idle) {
            // A previous hide must finish before this activation starts another input session.
            snapshotFlow { imeHidePending }.first { !it }
            withFrameNanos {}
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(imeVisible) {
        if (!imeVisible) {
            if (imeHidePending) imeHidePending = false
            else if (wasImeVisible && fieldFocused) focus.clearFocus()
        }
        wasImeVisible = imeVisible
    }
    BackHandler(enabled = visible && presentation.active, onBack = presentation::clearOrClose)
    LaunchedEffect(presentation.active) {
        if (presentation.active) {
            idle = false
            exitFrame = null
            controlsPresent = true
            controlsOpacity.animateTo(1f, tween(200))
        } else if (controlsPresent) {
            // The live list is reused for the idle page; detach the exit drawing from its child layers.
            exitFrame = if (contentLayer.size.width > 0 && contentLayer.size.height > 0) {
                contentLayer.toImageBitmap()
            } else null
            listState.scrollToItem(0)
            idle = true
            controlsOpacity.animateTo(0f, tween(200))
            controlsPresent = false
            exitFrame = null
        }
    }

    // Both positions host this same editor, so moving it keeps the IME session and selection.
    val editor = remember(presentation) {
        movableContentOf<Modifier> { modifier ->
            SearchField(
                presentation,
                focusRequester,
                { currentSubmit() },
                modifier,
                inputEnabled = currentVisible && presentation.active && !imeHidePending,
                onFocusChange = { fieldFocused = it },
            )
        }
    }
    val fieldGroup = remember(presentation, fieldHeight, actionGap) {
        movableContentOf<Modifier> { modifier ->
            Layout(
                modifier = modifier.fillMaxWidth(),
                content = {
                    editor(Modifier.height(LibrarySongSearchHeight))
                    CircleIconButton(
                        iconRes = R.drawable.xmark,
                        label = if (presentation.input.text.isNotEmpty()) "清除内容" else "关闭搜索",
                        background = MaterialTheme.colors.iconButtonBackground,
                        tint = MaterialTheme.colors.onIconButton,
                        onClick = presentation::clearOrClose,
                        enabled = currentVisible && presentation.active,
                        interactionSource = closeInteractions,
                        modifier = Modifier.testTag("search-close"),
                    )
                },
            ) { measurables, constraints ->
                val close = measurables[1].measure(
                    Constraints(maxWidth = constraints.maxWidth, maxHeight = fieldHeight)
                )
                val width = (constraints.maxWidth - (close.width + actionGap) * progress.value)
                    .roundToInt().coerceAtLeast(0)
                val field = measurables[0].measure(Constraints.fixed(width, fieldHeight))
                layout(constraints.maxWidth, fieldHeight) {
                    field.placeRelative(0, 0)
                    if (presentation.active || progress.value != 0f) {
                        close.placeRelative(width + actionGap, (fieldHeight - close.height) / 2)
                    }
                }
            }
        }
    }
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val tail = rememberNavigationTailPadding()
    val contentPresent = presentation.active || controlsPresent
    Box(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        if (!idle) Box(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(160.dp)
                .graphicsLayer { alpha = 1f - progress.value.coerceIn(0f, 1f) }
                .then(if (presentation.active) Modifier.clearAndSetSemantics {} else Modifier)
                .testTag("search-title"),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                "搜索", style = MaterialTheme.type.largeTitleEmphasized,
                modifier = Modifier.padding(start = 24.dp, bottom = 16.dp)
            )
        }
        if (idle && controlsPresent) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        translationY = (idleTop - activeTop) * (1f - progress.value)
                        alpha = controlsOpacity.value
                    }
                    .drawWithContent { exitFrame?.let { drawImage(it) } },
            )
        }
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    translationY = if (idle) 0f else (idleTop - activeTop) * (1f - progress.value)
                    alpha = if (idle) 1f else controlsOpacity.value
                }
                .drawWithContent {
                    if (idle) drawContent()
                    else {
                        if (presentation.active) contentLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(contentLayer)
                    }
                }
                .then(if (visible && (presentation.active || idle)) Modifier else Modifier.clearAndSetSemantics {}),
            content = {
                Box(Modifier.fillMaxSize()) {
                    VerticalStack(
                        state = listState,
                        contentPadding = PaddingValues(top = statusBarHeight, bottom = tail),
                        scrollIndicator = ScrollIndicatorSpec(
                            startPadding = statusBarHeight + SearchActiveHeaderHeight,
                            endPadding = tail,
                        ),
                        userScrollEnabled = visible && (presentation.active || idle),
                        modifier = Modifier.fillMaxSize().testTag("search-candidates"),
                    ) {
                        item(key = "search-header", contentType = "search-header") {
                            if (idle) {
                                Column(Modifier.fillMaxWidth()) {
                                    Box(
                                        Modifier.fillMaxWidth().height(160.dp)
                                            .graphicsLayer { alpha = 1f - progress.value.coerceIn(0f, 1f) }
                                            .testTag("search-title"),
                                        contentAlignment = Alignment.BottomStart,
                                    ) {
                                        Text(
                                            "搜索", style = MaterialTheme.type.largeTitleEmphasized,
                                            modifier = Modifier.padding(start = 24.dp, bottom = 16.dp),
                                        )
                                    }
                                    fieldGroup(
                                        Modifier.padding(horizontal = 12.dp).graphicsLayer {
                                            translationY = (activeTop - idleTop) * progress.value
                                        }
                                    )
                                }
                            } else Spacer(Modifier.fillMaxWidth().height(SearchActiveHeaderHeight))
                        }
                        if (!idle && contentPresent) content()
                    }
                    if (!idle && contentPresent) {
                        TopShade(
                            color = MaterialTheme.colors.pageBackground,
                            listState = listState,
                            threshold = { 0.dp },
                            modifier = Modifier.height(SearchActiveHeaderHeight + 48.dp),
                        )
                        Column(
                            Modifier
                                .statusBarsPadding()
                                .graphicsLayer { translationY = activeTop + fieldHeight },
                        ) {
                            VGap(12.dp)
                            Crossfade(
                                targetState = presentation.submitted,
                                animationSpec = tween(200),
                                label = "searchControls",
                            ) { submitted ->
                                if (submitted) SearchCategoryChips(presentation)
                                else SearchScopeSegmentedControl(presentation)
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val body = measurables.single().measure(constraints)
            layout(body.width, body.height) {
                // Exit replays the last active frame; unplaced live rows cannot update it or take taps.
                if (visible && (presentation.active || idle)) body.place(0, 0)
            }
        }
        if (!idle) {
            fieldGroup(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp)
                    .graphicsLayer {
                        translationY = idleTop + (activeTop - idleTop) * progress.value
                    },
            )
        }
    }
}

@Composable
private fun SearchField(
    presentation: SearchPresentation,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    modifier: Modifier,
    inputEnabled: Boolean,
    onFocusChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier
            .fillMaxWidth()
            .height(LibrarySongSearchHeight)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .clip(Capsule())
            .background(colors.scrimNormal)
            .clickable(interactionSource = null, indication = null) {
                if (!presentation.active) presentation.active = true
                else if (inputEnabled) {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
            }
            .testTag("search-field"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.magnifyingglass), null, tint = colors.contentVariant,
            modifier = Modifier
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 8.dp)
                .size(24.dp)
        )
        BasicTextField(
            value = presentation.input,
            onValueChange = { presentation.input = it },
            readOnly = !inputEnabled,
            singleLine = true,
            textStyle = MaterialTheme.type.body.copy(color = colors.content),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    onFocusChange(it.isFocused)
                    if (it.isFocused) presentation.active = true
                }
                .testTag("search-input"),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (presentation.input.text.isEmpty()) Text(
                        "搜索",
                        style = MaterialTheme.type.body,
                        color = colors.contentVariant,
                        maxLines = 1,
                    )
                    field()
                }
            },
        )
        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun SearchCategoryChips(presentation: SearchPresentation) {
    val colors = MaterialTheme.colors
    val rowState = rememberLazyListState()
    val overscroll = rememberOffsetOverscrollEffect(rowState)
    val chipWidths = remember { mutableStateMapOf<Int, Int>() }
    val indicatorShape = remember { Capsule() }
    val indicatorIndex = remember {
        Animatable(
            initialValue = presentation.category.ordinal.toFloat(),
            visibilityThreshold = 0.0001f,
        )
    }
    val indicatorWidth = remember { Animatable(0f, visibilityThreshold = 0.0001f) }
    val targetWidth = chipWidths[presentation.category.ordinal]?.toFloat()
    LaunchedEffect(presentation.category, targetWidth) {
        if (targetWidth == null) return@LaunchedEffect
        if (indicatorWidth.value == 0f) indicatorWidth.snapTo(targetWidth)
        else indicatorWidth.animateTo(
            targetValue = targetWidth,
            animationSpec = Springs.snappy(
                durationMillis = 300,
                visibilityThreshold = 0.0001f,
            ),
        )
    }
    LaunchedEffect(presentation.category) {
        indicatorIndex.animateTo(
            targetValue = presentation.category.ordinal.toFloat(),
            animationSpec = Springs.snappy(
                durationMillis = 300,
                visibilityThreshold = 0.0001f,
            ),
        )
    }
    KineticLazyRow(
        state = rowState,
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .drawWithCache {
                val textLayer = obtainGraphicsLayer()
                val indicatorPath = Path()
                val inactiveTextPaint = Paint().apply {
                    alpha = colors.content.alpha
                }
                val activeTextPaint = Paint().apply {
                    colorFilter = ColorFilter.tint(colors.onPrimary)
                }
                onDrawWithContent {
                    textLayer.record { this@onDrawWithContent.drawContent() }
                    indicatorPath.reset()
                    val info = rowState.layoutInfo
                    val visible = info.visibleItemsInfo
                    val stretch = (overscroll as? OverscrollVisualOffset)?.visualOffset?.x ?: 0f
                    val spacing = info.mainAxisItemSpacing
                    fun chipBounds(index: Int): Pair<Float, Float>? {
                        visible.firstOrNull { it.index == index }?.let {
                            return (it.offset.toFloat() - info.viewportStartOffset) to it.size.toFloat()
                        }
                        val anchor =
                            visible.minByOrNull { kotlin.math.abs(it.index - index) } ?: return null
                        var start = anchor.offset.toFloat() - info.viewportStartOffset
                        if (index > anchor.index) {
                            for (i in anchor.index until index) start += (chipWidths[i]
                                ?: return null) + spacing
                        } else {
                            for (i in index until anchor.index) start -= (chipWidths[i]
                                ?: return null) + spacing
                        }
                        return start to (chipWidths[index] ?: return null).toFloat()
                    }

                    fun drawIndicator(start: Float, width: Float) {
                        val x =
                            if (layoutDirection == LayoutDirection.Rtl) size.width - start - width else start
                        val outline = indicatorShape.createOutline(
                            Size(width.coerceAtLeast(0f), size.height), layoutDirection, this,
                        )
                        translate(left = x + stretch) {
                            drawOutline(outline, colors.primary)
                        }
                        indicatorPath.addOutline(outline)
                        indicatorPath.translate(Offset(x + stretch, 0f))
                    }
                    // Animate in content coordinates; scrolling and overscroll must follow without lag.
                    val animatedIndex = indicatorIndex.value
                    val lower = floor(animatedIndex).toInt()
                        .coerceIn(0, SearchCategory.entries.lastIndex - 1)
                    val fraction = animatedIndex - lower
                    val from = chipBounds(lower)
                    if (from != null) {
                        // The first draw precedes the effect that initializes the animated width.
                        val width = indicatorWidth.value.takeIf { it > 0f } ?: from.second
                        // The next start is known even when the next chip has not been measured.
                        drawIndicator(
                            from.first + (from.second + spacing) * fraction,
                            width,
                        )
                    }
                    // Keep the glyph mask opaque; inactive text alpha must not weaken the onPrimary tint.
                    clipPath(indicatorPath, clipOp = ClipOp.Difference) {
                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(Rect(Offset.Zero, size), inactiveTextPaint)
                            drawLayer(textLayer)
                            canvas.restore()
                        }
                    }
                    clipPath(indicatorPath) {
                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(Rect(Offset.Zero, size), activeTextPaint)
                            drawLayer(textLayer)
                            canvas.restore()
                        }
                    }
                }
            }
            .selectableGroup()
            .testTag("search-categories"),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        flingBehavior = rememberKineticSnapFlingBehavior(
            rowState,
            snapPosition = SnapPosition.Start
        ),
        overscrollEffect = overscroll,
        scrollIndicator = null,
    ) {
        items(SearchCategory.entries, key = { it.name }) { category ->
            val selected = presentation.category == category
            Box(
                Modifier
                    .height(44.dp)
                    .onSizeChanged { chipWidths[category.ordinal] = it.width }
                    .clip(Capsule())
                    .selectable(
                        selected = selected,
                        enabled = presentation.active && presentation.submitted,
                        interactionSource = null,
                        indication = null,
                        role = Role.Tab,
                        onClick = { presentation.category = category },
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    category.title,
                    style = MaterialTheme.type.subHeadlineEmphasized,
                    color = colors.content.copy(alpha = 1f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SearchScopeSegmentedControl(presentation: SearchPresentation) {
    val colors = MaterialTheme.colors
    val segmentSpacing = 4.dp
    val selectedIndex by animateFloatAsState(
        targetValue = presentation.scope.ordinal.toFloat(),
        animationSpec = Springs.smooth(
            durationMillis = 250,
            extraBounce = 0.1,
            visibilityThreshold = 0.0001f,
        ),
        label = "searchScopeIndicator",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(44.dp)
            .clip(Capsule())
            .background(colors.segmentedControlBackground)
            .padding(4.dp)
            .selectableGroup()
            .testTag("search-scope"),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val spacing = segmentSpacing.roundToPx()
                    val width =
                        (constraints.maxWidth - spacing * (SearchScope.entries.size - 1)) / SearchScope.entries.size
                    val indicator =
                        measurable.measure(Constraints.fixed(width, constraints.maxHeight))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        indicator.placeRelative(((width + spacing) * selectedIndex).roundToInt(), 0)
                    }
                }
                .clip(Capsule())
                .background(colors.segmentedControlIndicator),
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(segmentSpacing)) {
            SearchScope.entries.forEach { scope ->
                val selected = presentation.scope == scope
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(Capsule())
                        .selectable(
                            selected = selected,
                            enabled = presentation.active && !presentation.submitted,
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { presentation.scope = scope },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        scope.title, style = MaterialTheme.type.subHeadlineEmphasized,
                        color = if (selected) colors.onSegmentedControlIndicator else colors.onSegmentedControlBackground
                    )
                }
            }
        }
    }
}
