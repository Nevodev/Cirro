package com.nevoit.cirro.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.nevoit.cirro.R
import com.nevoit.cirro.data.model.Song
import com.nevoit.kinetic.layout.overscroll.OverscrollVisualOffset
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.interaction.AlphaIndication
import com.nevoit.material.core.modifier.drawBackground
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

internal val CollectionSearchHeight = 48.dp
internal val CollectionSearchPullThreshold = 96.dp
private val SearchLineBreaks = Regex("\\r\\n|[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]")

@Stable
internal class CollectionSearchState(
    private val thresholdPx: Float,
    private val dismissThresholdPx: Float,
    revealed: Boolean = false,
    query: String = "",
) {
    var revealed by mutableStateOf(revealed)
        private set
    private var singleLineQuery by mutableStateOf(SearchLineBreaks.replace(query, " "))
    // singleLine controls the field layout; pasted paragraph separators must also leave the model.
    var query: String
        get() = singleLineQuery
        set(value) {
            singleLineQuery = SearchLineBreaks.replace(value, " ")
        }
    var animationVelocity by mutableStateOf<Float?>(null)
        private set
    private var pullDistance = 0f
    private var dismissDistance = 0f
    private var fingerVelocity: (() -> Float)? = null
    private var revealedInGesture = false

    fun beginGesture(velocity: () -> Float) {
        release()
        fingerVelocity = velocity
    }

    fun pullBy(distance: Float) {
        val velocity = fingerVelocity ?: return
        if (revealed) {
            if (revealedInGesture) {
                dismissDistance = (dismissDistance - distance).coerceAtLeast(0f)
                if (dismissDistance > dismissThresholdPx) close(velocity())
            }
            return
        }
        pullDistance = (pullDistance + distance).coerceIn(0f, thresholdPx)
        if (pullDistance >= thresholdPx) {
            animationVelocity = velocity() / thresholdPx
            revealed = true
            revealedInGesture = true
        }
    }

    fun release() {
        pullDistance = 0f
        dismissDistance = 0f
        fingerVelocity = null
        revealedInGesture = false
    }

    fun close(velocityPx: Float? = null) {
        query = ""
        animationVelocity = velocityPx?.div(thresholdPx)
        revealed = false
        pullDistance = 0f
        dismissDistance = 0f
        revealedInGesture = false
    }

    companion object {
        fun saver(thresholdPx: Float, dismissThresholdPx: Float) =
            listSaver<CollectionSearchState, Any>(
                save = { listOf(it.revealed, it.query) },
                restore = {
                    CollectionSearchState(
                        thresholdPx,
                        dismissThresholdPx,
                        it[0] as Boolean,
                        it[1] as String
                    )
                },
            )
    }
}

internal fun Modifier.collectionSearchGestures(state: CollectionSearchState): Modifier =
    pointerInput(state) {
        val tracker = VelocityTracker()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            tracker.resetTracking()
            tracker.addPointerInputChange(down)
            state.beginGesture { tracker.calculateVelocity().y }
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial)
                        .changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    // Initial 先于列表处理拖动，跨阈值的那次 scroll 能读到同一事件的速度。
                    tracker.addPointerInputChange(change)
                }
            } finally {
                state.release()
            }
        }
    }

internal class CollectionSearchOverscrollEffect(
    private val effect: OverscrollEffect,
    private val search: CollectionSearchState,
    private val canScrollBackward: () -> Boolean,
) : OverscrollEffect by effect, OverscrollVisualOffset {
    override val visualOffset: Offset
        get() = (effect as? OverscrollVisualOffset)?.visualOffset ?: Offset.Zero

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        var contentConsumed = Offset.Zero
        val consumed = effect.applyToScroll(delta, source) {
            performScroll(it).also { used -> contentConsumed += used }
        }
        if (source == NestedScrollSource.UserInput) {
            // 已有拉伸会在 nested scroll 之前吃掉后续位移，必须在同一个 effect 外观察原始拖动。
            if (delta.y < 0f) search.pullBy(delta.y)
            else if (!canScrollBackward()) search.pullBy(
                (delta.y - contentConsumed.y).coerceAtLeast(
                    0f
                )
            )
        }
        return consumed
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        search.release()
        effect.applyToFling(velocity, performFling)
    }
}

@Composable
internal fun rememberCollectionSongSearchResults(
    songs: List<Song>,
    query: String,
): CollectionSongSearchResult? {
    val index by produceState<CollectionSongSearchIndex?>(null, songs) {
        value = withContext(Dispatchers.Default) { CollectionSongSearchIndex.build(songs) }
    }
    val result by produceState<CollectionSongSearchResult?>(null, songs, index, query) {
        if (query.isBlank()) {
            value = null
            return@produceState
        }
        val currentIndex = index ?: return@produceState
        if (currentIndex.songs !== songs) return@produceState
        delay(60.milliseconds)
        value = withContext(Dispatchers.Default) {
            CollectionSongSearchResult(songs, query, currentIndex.find(query))
        }
    }
    // 防抖和筛选期间保留上一批行，避免空列表打断 animateItem 的 key 与位移连续性。
    return result?.takeIf { it.songs === songs }
}

@Composable
internal fun CollectionSearchField(
    state: CollectionSearchState,
    background: ColorProducer,
    tint: ColorProducer,
    modifier: Modifier = Modifier,
) {
    val progress = remember(state) { Animatable(if (state.revealed) 1f else 0f) }
    val opacity = remember(state) { Animatable(if (state.revealed) 1f else 0f) }
    LaunchedEffect(state.revealed, state.animationVelocity) {
        if (state.revealed) {
            val spec = Springs.smooth(extraBounce = 0.15, visibilityThreshold = 0.0001f)
            val velocity = (state.animationVelocity?.div(2)) ?: progress.velocity
            coroutineScope {
                launch { opacity.animateTo(1f, tween(300), initialVelocity = velocity) }
                progress.animateTo(1f, spec, initialVelocity = velocity)
            }
        } else {
            coroutineScope {
                launch { opacity.animateTo(0f, tween(durationMillis = 200)) }
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = 400,
                        easing = CubicBezierEasing(0.2f, 0.2f, 0f, 1f)
                    )
                )
            }
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    if (state.revealed || progress.isRunning || progress.value > 0f) {
        Row(
            modifier = modifier
                .graphicsLayer {
                    val fraction = progress.value
                    alpha = opacity.value.coerceIn(0f, 1f)
                    scaleX = 1f + 0.25f * (1f - fraction)
                    scaleY = scaleX
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .fillMaxWidth()
                .height(CollectionSearchHeight)
                .clip(Capsule())
                .drawBackground(background)
                .testTag("collection-search"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.magnifyingglass),
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .padding(12.dp)
                    .size(24.dp),
            )
            BasicTextField(
                value = state.query,
                onValueChange = { state.query = it },
                enabled = state.revealed,
                singleLine = true,
                textStyle = MaterialTheme.type.body.copy(color = tint()),
                cursorBrush = SolidColor(tint()),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("collection-search-input"),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (state.query.isEmpty()) {
                            Text(
                                text = "搜索",
                                style = MaterialTheme.type.body,
                                color = tint().copy(alpha = 0.5f),
                                maxLines = 1,
                            )
                        }
                        field()
                    }
                },
            )
            Icon(
                painter = painterResource(R.drawable.xmark_bold_circle_fill),
                contentDescription = if (state.query.isEmpty()) "关闭搜索" else "清除内容",
                tint = tint,
                modifier = Modifier
                    .size(48.dp)
                    .clip(Capsule())
                    .clickable(
                        enabled = state.revealed,
                        role = Role.Button,
                        interactionSource = null,
                        indication = AlphaIndication()
                    ) {
                        if (state.query.isNotEmpty()) state.query = ""
                        else {
                            keyboard?.hide()
                            focus.clearFocus()
                            state.close()
                        }
                    }
                    .padding(15.dp),
            )
        }
    }
}
