package com.nevoit.cirro.lyrics.ui.composable.lyrics

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nevoit.cirro.lyrics.core.model.ISyncedLine
import com.nevoit.cirro.lyrics.core.model.SyncedLyrics
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeAlignment
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.core.model.synced.SyncedLine
import com.nevoit.cirro.lyrics.ui.LyricsFontFamily
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsLazyListState
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsListItem
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsScrollChain
import com.nevoit.cirro.lyrics.ui.internal.effects.lyricsContentOffset
import com.nevoit.cirro.lyrics.ui.internal.effects.lyricsEdgeFade
import com.nevoit.cirro.lyrics.ui.internal.layout.LineHorizontalPaddingDp
import com.nevoit.cirro.lyrics.ui.internal.layout.LineVerticalPaddingDp
import com.nevoit.cirro.lyrics.ui.internal.layout.LyricsFocusHeightUpdates
import com.nevoit.cirro.lyrics.ui.internal.layout.LyricsLazyColumn
import com.nevoit.cirro.lyrics.ui.internal.layout.lyricsAutoScroll
import com.nevoit.cirro.lyrics.ui.internal.layout.onMeasured
import com.nevoit.cirro.lyrics.ui.internal.layout.settledHeight
import com.nevoit.cirro.lyrics.ui.internal.layout.settledLeadingHeight
import com.nevoit.cirro.lyrics.ui.internal.rendering.LyricsTexturePrefetch
import com.nevoit.cirro.lyrics.ui.internal.rendering.PreparedLineText
import com.nevoit.cirro.lyrics.ui.internal.scene.LyricsLayoutRequest
import com.nevoit.cirro.lyrics.ui.internal.scene.rememberLyricsScene
import com.nevoit.cirro.lyrics.ui.internal.text.isRtl
import com.nevoit.cirro.lyrics.ui.profile.DefaultLyricsProfiles
import com.nevoit.cirro.lyrics.ui.profile.LyricsProfile
import com.nevoit.material.theme.local.LocalTextStyle
import com.nevoit.material.theme.tokens.Springs
import kotlin.math.ceil

@Composable
fun KaraokeLyricsView(
    listState: LyricsLazyListState,
    lyrics: SyncedLyrics,
    currentPosition: () -> Int,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    modifier: Modifier = Modifier,
    normalLineTextStyle: TextStyle =
        LocalTextStyle.current.copy(
            fontFamily = LyricsFontFamily,
            fontSize = 28.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.W800,
            textMotion = TextMotion.Animated,
        ),
    accompanimentLineTextStyle: TextStyle =
        LocalTextStyle.current.copy(
            fontFamily = LyricsFontFamily,
            fontSize = 18.sp,
            lineHeight = TextUnit.Unspecified,
            fontWeight = FontWeight.W800,
            textMotion = TextMotion.Animated,
        ),
    textColor: Color = Color.White,
    breathingDotsDefaults: KaraokeBreathingDotsDefaults = KaraokeBreathingDotsDefaults(),
    phoneticTextStyle: TextStyle =
        normalLineTextStyle.copy(
            fontSize = 15.sp,
            lineHeight = TextUnit.Unspecified,
            fontWeight = FontWeight.Normal,
        ),
    blendMode: BlendMode = BlendMode.Plus,
    useBlurEffect: Boolean = true,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    anchor: LyricsAnchor = LyricsAnchor.Fixed(40.dp),
    topFade: LyricsFade = LyricsFade.ToAnchor(16.dp),
    bottomFade: LyricsFade = LyricsFade.Fraction(0.5f),
    keepAliveZone: Dp = 100.dp,
    blurDelta: Float = 3f,
    showDebugRectangles: Boolean = false,
    renderProfiles: List<LyricsProfile> = DefaultLyricsProfiles,
    itemSpacing: Dp = 0.dp,
    scrollAnimationSpec: AnimationSpec<Float> = Springs.smooth(500),
    autoScrollResumeDelayMillis: Long = 2500,
    scrollChain: LyricsScrollChain? = LyricsScrollChain(),
    isSeeking: Boolean = false,
    /**
     * 面板级进/出场的不透明度与缩放。**调用方不要 `by`**，这两个值每一帧都在变，
     * 组合期读一次就等于整个歌词页每帧重组；它们只在 [graphicsLayer] 段被读。
     */
    panelAlpha: () -> Float = { 1f },
    panelScale: () -> Float = { 1f },
    /** 歌词内容**测量完成**时回调一次（每帧布局都会调，调用方自己保证幂等）。*/
    contentReady: (() -> Unit)? = null,
) {
    val currentProvider by rememberUpdatedState(currentPosition)
    val timeProvider = remember { { currentProvider() } }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val profiles =
        remember(renderProfiles) {
            renderProfiles + DefaultLyricsProfiles.filter { it !in renderProfiles }
        }
    BoxWithConstraints(modifier.clipToBounds()) {
        val anchorOffset = when (anchor) {
            is LyricsAnchor.Fixed -> anchor.offset.coerceAtMost(maxHeight)
            is LyricsAnchor.Fraction -> maxHeight * anchor.fraction
        }
        val width =
            with(density) { (maxWidth - LineHorizontalPaddingDp.dp * 2).toPx() }
                .coerceAtLeast(1f)
        val scene = rememberLyricsScene(
            LyricsLayoutRequest(
                lyrics, profiles, measurer, normalLineTextStyle, accompanimentLineTextStyle,
                phoneticTextStyle, width, density, direction,
            ),
            textColor,
            timeProvider
        ) ?: return@BoxWithConstraints
        val timeline = scene.timeline
        // A single main-vocal row, excluding phonetics, translation and nested vocals.
        // Reuse prepared metrics so font size, line height and font scale stay consistent.
        val breathingLineHeight = remember(scene.lyrics, density) {
            val height = scene.lyrics.lines.asSequence()
                .filterNotNull()
                .filter { it.source is KaraokeLine.MainKaraokeLine }
                .flatMap { it.rows.asSequence() }
                .map { it.height - it.phoneticHeight }
                .maxOrNull()
            if (height == null) 40.dp else with(density) { height.toDp() }
        }
        val breathingHeightPx = with(density) {
            breathingLineHeight.coerceAtLeast(breathingDotsDefaults.size.coerceAtLeast(1.dp))
                .roundToPx() + itemSpacing.coerceAtLeast(0.dp).roundToPx()
        }
        val previousCaptions =
            remember(timeline, listState) { booleanArrayOf(showTranslation, showPhonetic) }
        SideEffect {
            if (previousCaptions[0] != showTranslation || previousCaptions[1] != showPhonetic) {
                listState.preserveFollowAnchorForContentChange()
                previousCaptions[0] = showTranslation
                previousCaptions[1] = showPhonetic
            }
        }
        val focus by timeline.focus
        val mapping = scene.items
        val sourceIndices = mapping.sourceIndices
        // 首帧落位：列表 state 建在 0，只靠自动跟随去滚就是「先出现、再滚一下」。这里在布局
        // 之前（SideEffect 早于本帧测量）把列表一次性摆到当前行，无动画；随后的首次跟随目标
        // 与当前位置相同，弹簧成为空操作。scene 就绪那一帧 `timeline.focus` 已被真位置刷过，
        // 所以这一句算出的就是当前行而不是第一行。
        SideEffect {
            listState.snapToItemOnNextLayout(mapping.itemIndex(timeline.focus.value.followIndex))
        }
        val translationShown = rememberUpdatedState(showTranslation)
        LyricsTexturePrefetch(
            mapping.lines,
            scene.resources,
            listState,
        ) {
            mapping.itemIndex(timeline.focus.value.followIndex)
        }
        val phoneticShown = rememberUpdatedState(showPhonetic)
        val listItems =
            remember(scene.lyrics, timeline, mapping, density.density, breathingHeightPx) {
                sourceIndices.map { index ->
                    val line = scene.lyrics.lines[index]
                    val focusedHeight = line?.settledHeight(true, true, density.density, true) ?: 0f
                    val unfocusedHeight =
                        line?.settledHeight(true, true, density.density, false) ?: 0f
                    val minimumHeight =
                        line?.settledHeight(false, false, density.density, false) ?: 0f
                    val focusHeight = (focusedHeight - unfocusedHeight).toInt()
                    val leadingHeight =
                        line?.settledLeadingHeight(true, true, density.density, true)?.toInt() ?: 0
                    val breathingHeight = {
                        val currentFocus = timeline.focus.value
                        if ((index == 0 && currentFocus.activeIntro) ||
                            currentFocus.activeInterludeIndex == index
                        ) breathingHeightPx else 0
                    }
                    LyricsListItem(
                        key = "${lyrics.lines[index].start}-${lyrics.lines[index].end}-$index",
                        seekStartMillis = lyrics.lines[index].takeUnless {
                            it is KaraokeLine.AccompanimentKaraokeLine
                        }?.start,
                        preserveAnchorOnHeightChange = false,
                        focusHeightPx = line?.let {
                            {
                                if (index in timeline.focus.value.layoutIndices) focusHeight else 0
                            }
                        },
                        focusLeadingHeightPx = line?.let {
                            {
                                if (index in timeline.focus.value.layoutIndices) leadingHeight else 0
                            }
                        },
                        breathingHeightPx = breathingHeight,
                        // Exit drawing survives collapsed backing vocals and hidden captions.
                        visualOverflowPx = ceil(focusedHeight - minimumHeight).toInt() + leadingHeight +
                                if ((index == 0 && timeline.introEnd > 5000) ||
                                    (index > 0 && timeline.interludeEnds[index].toLong() -
                                            timeline.interludeStarts[index] > 5000)
                                ) breathingHeightPx else 0,
                        settledHeightPx = {
                            (line?.settledHeight(
                                translationShown.value,
                                phoneticShown.value,
                                density.density,
                                index in timeline.focus.value.layoutIndices,
                            )?.toInt() ?: 0) + breathingHeight()
                        },
                        // Start with the same focus occupancy as the geometry model.
                        estimatedHeightPx =
                            (if (index in timeline.focus.value.layoutIndices) focusedHeight
                            else unfocusedHeight).toInt() + breathingHeight(),
                    )
                }
            }
        DisposableEffect(listState, listItems, timeline, mapping) {
            val updates =
                LyricsFocusHeightUpdates(listItems, { timeline.focus.value }, mapping::itemIndex)
            listState.focusHeightUpdates = updates
            onDispose {
                if (listState.focusHeightUpdates === updates) listState.focusHeightUpdates = null
            }
        }
        val followModifier =
            lyricsAutoScroll(
                listState,
                targetIndex = {
                    mapping.itemIndex(timeline.focus.value.followIndex)
                },
                animationSpec = scrollAnimationSpec,
                resumeDelayMillis = autoScrollResumeDelayMillis,
                focusEndIndex = {
                    mapping.itemIndex(
                        timeline.focus.value.followEndIndex
                    )
                },
                playbackPosition = timeProvider,
                firstWordStartsLater = { timeline.focus.value.firstWordStartsLater },
                isSeeking = isSeeking,
            )
        // One shared animation, sampled only by graphics layers; no per-frame item recomposition.
        val cancelFocusBlur = listState.isManualScrolling || isSeeking
        val focusBlurFactor =
            animateFloatAsState(
                targetValue = if (cancelFocusBlur) 0f else 1f,
                animationSpec = if (isSeeking) snap() else tween(if (cancelFocusBlur) 100 else 300),
                label = "manualScrollFocusBlur",
            )
        LyricsLazyColumn(
            items = listItems,
            state = listState,
            itemSpacing = itemSpacing,
            scrollChain = scrollChain,
            beyondBounds = keepAliveZone,
            contentPadding = PaddingValues(top = anchorOffset, bottom = maxHeight + keepAliveZone),
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(followModifier)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        this.blendMode = blendMode
                        // 面板进出场**必须挂在这一层上**。这一层正是 `Plus` 的合成层：它的内容
                        // 先画进离屏缓冲，再以叠加上色合成到底色上。若在它外面再包一层
                        // `alpha`/`scale`，叠加对象就从「底色」变成了那层空缓冲 —— 发光会整个失效
                        // （屏幕上看就是歌词忽然不亮了）。挂在这一层则 alpha 只是把叠加量整体
                        // 缩放、scale 只是缩放叠加内容，`Plus` 全程成立。
                        alpha = panelAlpha().coerceIn(0f, 1f)
                        val panelScaleValue = panelScale().coerceAtLeast(0f)
                        scaleX = panelScaleValue
                        scaleY = panelScaleValue
                    }
                    .lyricsEdgeFade(topFade, bottomFade, anchorOffset)
                    .onMeasured(contentReady),
        ) { itemIndex ->
            val index = sourceIndices[itemIndex]
            val line = lyrics.lines[index]
            val prepared = scene.lyrics.lines[index]
            val breathingExtent = if (
                (index == 0 && timeline.introEnd > 5000) ||
                (index > 0 && timeline.interludeEnds[index].toLong() -
                        timeline.interludeStarts[index] > 5000)
            ) breathingHeightPx.toFloat() else 0f
            val focused = index in focus.allIndices
            val distance =
                maxOf(
                    0,
                    (focus.allIndices.firstOrNull() ?: focus.firstIndex) - index,
                    index - (focus.allIndices.lastOrNull() ?: focus.firstIndex),
                )
            val blur by
            animateFloatAsState(if (useBlurEffect) distance * blurDelta else 0f, tween(300))
            Column {
                if (index == 0 && focus.activeIntro)
                    KaraokeBreathingDots(
                        alignment =
                            if (prepared?.rightAligned == true)
                                KaraokeAlignment
                                    .End
                            else
                                KaraokeAlignment
                                    .Start,
                        startTimeMs = 0,
                        endTimeMs = timeline.introEnd,
                        currentTimeProvider = timeProvider,
                        defaults = breathingDotsDefaults,
                        trailingSpacing = itemSpacing,
                        lineHeight = breathingLineHeight,
                    )
                if (focus.activeInterludeIndex == index && index > 0)
                    KaraokeBreathingDots(
                        alignment =
                            if (prepared?.rightAligned == true)
                                KaraokeAlignment
                                    .End
                            else
                                KaraokeAlignment
                                    .Start,
                        startTimeMs = timeline.interludeStarts[index],
                        endTimeMs = timeline.interludeEnds[index],
                        currentTimeProvider = timeProvider,
                        defaults = breathingDotsDefaults,
                        trailingSpacing = itemSpacing,
                        lineHeight = breathingLineHeight,
                    )
                if (prepared !in scene.lyrics.embeddedLines)
                    LyricsLineItem(
                        modifier = Modifier.lyricsContentOffset(
                            breathingExtent,
                            prepared?.settledHeight(true, true, density.density, true) ?: 0f,
                        ) { listState.chain.breathingOffset(itemIndex) },
                        isFocused = focused,
                        inactiveAlpha = if ((line as? KaraokeLine)?.hasSyllableTiming == true) 0.4f else 0.3f,
                        isRightAligned =
                            prepared?.rightAligned
                                ?: ((line as? SyncedLine)?.content?.isRtl() == true),
                        onLineClicked = {
                            listState.resumeAutoScroll(line.start)
                            onLineClicked(line)
                        },
                        onLinePressed = { onLinePressed(line) },
                        blurRadius = { if (isSeeking) 0f else blur * focusBlurFactor.value },
                        visualHeight = prepared?.settledHeight(
                            true, true, density.density, true,
                        ) ?: 0f,
                        verticalOverflow = prepared?.settledLeadingHeight(
                            true, true, density.density, true,
                        ) ?: 0f,
                    ) {
                        if (prepared != null)
                            PreparedLineText(
                                prepared,
                                timeline.state,
                                scene.resources,
                                ownerFocused = { index in timeline.focus.value.layoutIndices },
                                leadingContentOffset = { listState.chain.leadingOffset(itemIndex) },
                                verticalPadding = LineVerticalPaddingDp.dp,
                                // Top-level rows take the inset here; the nested ones inherit the
                                // enclosing column's padding and pass 0 of their own.
                                modifier =
                                    if (line is KaraokeLine.AccompanimentKaraokeLine)
                                        Modifier.padding(horizontal = LineHorizontalPaddingDp.dp)
                                    else Modifier,
                                showTranslation = showTranslation,
                                showPhonetic = showPhonetic,
                                showDebugRectangles = showDebugRectangles,
                            )
                    }
            }
        }
    }
}
