package com.nevoit.cirro.playback

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImagePainter
import coil3.BitmapImage
import coil3.compose.rememberAsyncImagePainter
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.nevoit.cirro.R
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.lyrics.data.LyricsRepository
import com.nevoit.cirro.lyrics.ui.LyricsPanel
import com.nevoit.cirro.lyrics.ui.internal.rendering.LocalLyricsRenderCache
import com.nevoit.cirro.lyrics.ui.internal.rendering.LyricsRenderCache
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.cirro.playback.pearmesh.PearMeshState
import com.nevoit.cirro.playback.pearmesh.PearMeshSurface
import com.nevoit.material.core.animation.rememberPressAnimatable
import com.nevoit.material.core.component.CircleIconButton
import com.nevoit.material.core.component.HGap
import com.nevoit.material.core.component.Icon
import com.nevoit.material.core.component.Text
import com.nevoit.material.core.component.VGap
import com.nevoit.material.core.component.WavyLinearProgress
import com.nevoit.material.core.modifier.drawBackground
import com.nevoit.material.core.modifier.marquee
import com.nevoit.material.core.utility.rememberColorProducer
import com.nevoit.material.theme.MaterialTheme
import com.nevoit.material.theme.local.ProvideContentColor
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/** 换封面时新旧两张大图的交叉时长。 */
private const val CoverCrossfadeMs = 300

/** 大封面的边长上限。竖屏由可用区域的短边说了算，这个只防横屏/平板把它拉得过大。 */
private val CoverSideCap = 360.dp

/** 大封面下探强度：0 关闭，1 使用整份容器顶部位移形成的弧度。 */
private const val ExpandedCoverDipFraction = 0.75f

/** 正文区的左右边距。歌曲信息、进度条、控制条都跟这条线对齐。 */
private val ContentPadding = 24.dp

/** 名字右边那两颗圆钮（喜欢/更多）的尺寸。比顶排的浮钮小一号，跟文字排同高。 */
private val MetaButtonSize = 40.dp

/** 收起（点歌词/待播列表）时封面缩到的小方块边长：落到标题行左侧让出的那一格。 */
private val CollapsedCoverSize = 48.dp

/**
 * 歌词面板退场时的缩放（出场就是从它长到 1）。大小变化走 [LyricsScaleSpring]。
 *
 * 取比 1 略小而不是从 0 起：歌词是「换一块内容」而不是「弹出一个东西」，幅度大了像飞盘。
 */
private const val LyricsPanelScale = 0.92f

/** 歌词面板缩放的弹簧。`Springs.smooth` 的 bounce 是 0，不会过冲——过冲会被外面的裁剪切掉。 */
private val LyricsScaleSpring =
    Springs.smooth<Float>(durationMillis = 400, visibilityThreshold = 0.0001f)

/** 歌词面板出场的不透明度时长。透明走普通 tween，不跟弹簧掺在一起。 */
private const val LyricsFadeInMs = 220

/** 退场比出场快一点：收得利落些，也不至于让被压在下面的封面等太久。 */
private const val LyricsFadeOutMs = 160

/** 新面板淡入相对旧面板淡出结束的间隔；负数表示重叠，单位毫秒。 */
private const val LyricsToQueueGapMs = 0L
private const val QueueToLyricsGapMs = 0L

/** 预热场景迟迟不能发布时，先释放封面；歌词仍须等真实布局就绪。 */
private const val LyricsCoverReadyFallbackMs = 900L

/**
 * 「封面已收回到位」的判定阈值 —— **未预热**时歌词进场等它。
 *
 * 不等到 1：封面那条弹簧的 `visibilityThreshold = 0.0001f` 是为**数学收敛**设的，ζ=0.85 时
 * 要跑到 ~700ms 才停；而视觉上 280ms 左右就已经到位了（这个阻尼比下过冲只有 0.6%，峰值在
 * 379ms，所以 0.98 是在峰前跨过的）。取 0.98 = 「看得出来已经到了」的那一帧。
 *
 * 预热过的那条路不用它：场景发布只剩两三帧，让封面和歌词一起等更整齐。
 */
private const val CollapseSettled = 0.98f

/** 收起时小封面与标题之间的间隔。 */
private val CollapsedCoverGap = 12.dp

/**
 * 底部功能栏那三颗图标（视觉 28dp）的**点击**边长。
 *
 * ⚠️ 不能用"最小触摸目标"那套（`ViewConfiguration.minimumTouchTargetSize` / 老的
 * `ProvideMinimumTouchTargetSize`）：那个机制只产出一个**附近命中**（命中点在节点外、但在最小目标内），
 * 而 Compose 的命中测试在同一个父节点下有兄弟拿到**正命中**时，会把已有的附近命中**删掉**
 * （`HitTestResult.hitInMinimumTouchTarget` 里的 `removeNodesInRange`）。播放页是盖在首页之上的，
 * 图标正下方就是首页那个铺满全屏的 LazyColumn —— 它永远拿得到正命中。于是附近命中被清掉，
 * 可点范围退回图标本身的 28dp。**这就是抬 minimumTouchTargetSize 看不出任何变化的原因。**
 *
 * 所以这里给点击节点一个**真实的** [PanelTouchTargetSize] 框（见 [PlayerPanelIcon]）：真框算正命中，
 * 才活得过上面那条删除。框是居中溢出摆放的，整排的布局尺寸仍是 28dp —— 行高、间距、封面都不动。
 * 三个槽位各占 1/3 屏宽、彼此隔着一百多 dp，这个框再大也压不到隔壁那颗。
 */
private val PanelTouchTargetSize = 56.dp

/** 网格之上的黑蒙层。封面放大后是亮色块，网格得先压暗一层，主题色再压上去才收得住。 */
private const val BackdropScrim = 0.5f

// ---- 主题色：跟详情页同一套逻辑（封面种子 → primary 色带的固定明度档）----
// 数据源相同（CoverPalette 量化封面 → Score 挑种子 → HCT 色带），档位也照抄详情页那一组，
// 所以同一张专辑在详情页和播放页是同一个色调。深浅由种子的明暗判定 + 系统颜色模式一起决定。

private const val BgToneDark = 30

private const val BgToneLight = 80

private const val VariantToneDark = 80

private const val VariantToneLight = 40

private const val ButtonToneDark = 40

private const val ButtonToneLight = 90

private const val ButtonIconToneDark = 90

private const val ButtonIconToneLight = 40

// 播放键是这一页唯一的主操作，刻意脱离「按钮」那一档，站到跟页面底色相反的极点上：
// 深色页（底 T30）它是一块亮胶囊 T90 配深图标 T20；浅色页（底 T80）翻过来，深胶囊 T20 配亮图标 T90。
// 两档都跟自家底色差 50~60，是全页对比度最高的一块。
// 命名跟上面一致：后缀 Dark/Light 指的是「深色页 / 浅色页」用的那一个，不是颜色的明暗。
private const val PlayToneDark = 90

private const val PlayToneLight = 20

private const val PlayIconToneDark = 20

private const val PlayIconToneLight = 90

/** 换歌时的主题色过渡时长，跟详情页一致。 */
private const val ThemeColorMs = 500

/** 进度补间时长。要跟 PlaybackConnection 的位置心跳（500ms）对齐：短了进度头会走走停停，长了会滞后。 */
private const val ProgressTweenMs = 500

/** 波浪进度条：条粗、波峰到波谷的高度、波长、播放头竖线尺寸，以及可拖区域高度。 */
private val TrackThickness = 4.dp

private val TrackWaveHeight = 10.dp

private val TrackWavelength = 40.dp

private val TrackPlayheadWidth = 4.dp

private val TrackPlayheadHeight = 32.dp

private val TrackTouchHeight = 32.dp

// ---- 背景网格 ----

/**
 * 上一次用过的预设，只为了让"随机"躲开连着两次抽到同一个。
 *
 * 竖屏 4 套、横屏 5 套是两组互不相干的外观（见 `PearMeshPresets`），所以"上次那个"要分开记。
 * 不收在 [PlayerUiController] 里：那边管的是展开进度，跟背景长什么样没关系。只在主线程的
 * 组合期读写，不需要同步。
 */
private object LastMeshPreset {
    var portrait = PearMeshState.DefaultPortraitPresetIndex
    var landscape = PearMeshState.DefaultLandscapePresetIndex
}

/**
 * 从 `0 until count` 里抽一个**不等于** [last] 的索引。
 *
 * 竖屏只有 4 套，纯随机有 25% 的概率连着两次抽中同一个——那看起来就跟"没随机"一样。
 * 做法是先排掉 [last]，在剩下的 `count - 1` 个里均匀取一个（`offset`），再跳过 `last` 那个坑：
 * 结果在"除 last 之外的所有值"上仍然是均匀的，只是排除了一个值。
 */
private fun pickMeshPreset(count: Int, last: Int): Int {
    if (count <= 1) return 0
    val skip = last.coerceIn(0, count - 1)
    val offset = Random.nextInt(count - 1)
    return if (offset < skip) offset else offset + 1
}

/**
 * 播放页接入点：把它挂在 [androidx.compose.foundation.layout.Box] 的最上层（盖住导航和常驻 chrome）。
 *
 * 返回键在这里收——播放页不是导航目的地，退回系统默认行为会直接退出 Activity。
 */
@Composable
fun PlayerLayer(
    controller: PlayerUiController,
    connection: PlaybackConnection,
    expansion: PlayerExpansionState,
    modifier: Modifier = Modifier,
) {
    val header by remember(connection) {
        connection.state.map { it.song to it.isPlayingOptimistic }.distinctUntilChanged()
    }.collectAsState(initial = remember(connection) { connection.state.value.let { it.song to it.isPlayingOptimistic } })
    // 链接解析留在常驻层；收起页面后保留匹配结果，视频和 GL 资源仍随播放页释放。
    val motionSources = rememberPlayerMotionArtworkSources(
        header.first?.id, controller.motionArtworkEnabled, controller.present, connection,
    )
    val motionSource = motionSources.current
    val frameContext = LocalContext.current.applicationContext
    val frameScope = rememberCoroutineScope()
    val motionFrames = remember(controller, frameContext, frameScope) {
        val store = PlayerMotionFrameStore(java.io.File(frameContext.filesDir, "apple-music/player-motion"))
        PlayerMotionFrameCache(frameScope, store::read, store::write)
    }
    SideEffect { if (motionSource.resolved) motionFrames.select(motionSource.url, motionSources.next.url) }
    if (!controller.present) return

    BackHandler(enabled = controller.acceptsInput) { controller.close() }
    DisposableEffect(expansion) {
        onDispose { expansion.releasePage() }
    }

    // 开合动画在这里跑：`LaunchedEffect` 的协程自带帧时钟，弹簧取下一帧要靠它。
    // 手指按下去时，控制器的 snapTo 会把这个动画打断，collectLatest 也跟着把这一轮取消，
    // 所以拖拽和动画不会打架。
    LaunchedEffect(controller, expansion) {
        snapshotFlow { controller.animationRequest to controller.isDragging }
            .collectLatest {
                if (controller.isDragging || controller.animationTarget().first > 0f) {
                    snapshotFlow { expansion.ready }.first { it }
                    expansion.takeOver()
                    if (!controller.isDragging) {
                        snapshotFlow { expansion.backgroundReady }.first { it }
                    }
                }
                if (controller.isDragging) return@collectLatest
                controller.animate()
                controller.onAnimationSettled()
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                if (expansion.ownsVisual) {
                    drawRect(Color.Black, alpha = 0.6f * expansion.progress.coerceIn(0f, 1f))
                }
            },
    )
    PlayerOverlay(
        controller = controller,
        connection = connection,
        expansion = expansion,
        song = header.first,
        isPlaying = header.second,
        motionSource = motionSource,
        nextMotionSource = motionSources.next,
        motionFrames = motionFrames,
        modifier = modifier,
    )
}

/**
 * 建一个主题色的补间，并**直接包成** [ColorProducer]。
 *
 * 要点是全程不读 `.value`：`animateColorAsState` 只负责建出 State，值留给绘制段
 * （`color()`）或者叶子文字 composable 去读。写成 `by animateColorAsState` 就等于在组合期
 * 读数，颜色每动一帧就把调用方重组一次——主题色是换歌时动 500ms 的，那 500ms 里整棵子树
 * 会每帧重组，连最贵的封面舞台都躲不掉。
 */
@Composable
private fun rememberThemedColor(target: Color, label: String): ColorProducer =
    rememberColorProducer(
        animateColorAsState(
            targetValue = target,
            animationSpec = tween(ThemeColorMs),
            label = label,
        )
    )

/**
 * 播放页本体。
 *
 * 页面始终按最终尺寸布局；展开进度只改变裁剪边界、背景交接和封面图层，避免动画期间
 * 反复排版正文或改变背景 Surface 的尺寸。
 *
 * 内容按"多久变一次"分层订阅：换歌/播放状态在最外层，位置心跳下沉到进度条，队列（模式 + 列表）
 * 由待播列表面板自己订。子块之间的参数是稳定的（`song`、`isPlaying`），所以重组只落在真正变了
 * 的那一块上——**回调必须 `remember` 固定**，否则每次都是新 lambda，Compose 的跳过就全失效了。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerOverlay(
    controller: PlayerUiController,
    connection: PlaybackConnection,
    expansion: PlayerExpansionState,
    song: NowPlaying?,
    isPlaying: Boolean,
    motionSource: PlayerMotionSource,
    nextMotionSource: PlayerMotionSource,
    motionFrames: PlayerMotionFrameCache,
    modifier: Modifier = Modifier,
) {
    // 外层只传换歌和播放状态。位置心跳（500ms 一次）不进这里，
    // 它只喂给进度条那一小块（见 PlayerProgress）——整页重组会重新 subcompose 封面区，
    // 而 BoxWithConstraints 是 SubcomposeLayout，那是这一页最贵的一步。
    // 播放状态取「乐观播放」：切歌缓冲期 `isPlaying` 是 false，直接拿它画会让播放键闪一下暂停，
    // 网格背景也跟着停一下再走（见 `PlaybackState.isPlayingOptimistic`）。

    val seekState = remember(connection, song?.id) { PlayerSeekState() }

    // 队列被清空（进程回收后服务重建、歌放完被清掉）时别留一张空壳在这
    LaunchedEffect(song) { if (song == null) controller.close() }
    if (song == null) return
    val motionUrl = motionSource.url

    // 空串比 null 好使：取色、纹理、大图三条链路都只认非空串，归一化一次省掉到处散布的判空。
    val cover = song.cover.orEmpty()

    // ---- 主题色 ----
    // 跟详情页同一套取色：种子进 HCT 生成 primary 色带，再按深浅取固定明度档。
    // 缓存命中（首页/详情页预取过同一张封面）时第一帧就有颜色，所以不会出现"先灰一下再上色"。
    val palette = koinInject<CoverPalette>()
    var seed by remember(cover) { mutableStateOf(palette.cached(cover)) }
    var seedReady by remember(cover) { mutableStateOf(cover.isEmpty() || seed != null) }
    LaunchedEffect(cover) {
        if (cover.isNotEmpty()) seed = palette.seed(cover)
        seedReady = true
    }
    var themeInitialized by remember { mutableStateOf(false) }
    if (!themeInitialized) {
        // Initialise animated colours from the resolved palette, so the first mesh frame's wash
        // cannot tween from the fallback. Once visible, song changes retain their colour transition.
        if (!seedReady) return
        SideEffect { themeInitialized = true }
    }

    val motionOpacity = remember { Animatable(0f) }
    var motionReady by remember { mutableStateOf(false) }
    var reportedMotion by remember { mutableStateOf(motionUrl != null) }
    var reportedMotionUrl by remember { mutableStateOf(motionUrl) }
    val motionReplacesCover = motionSource.keepsMotionLayout(reportedMotionUrl, reportedMotion)
    val onMotionStateChanged = remember {
        { ready: Boolean, replacesCover: Boolean, shownUrl: String? ->
            motionReady = ready
            reportedMotion = replacesCover
            reportedMotionUrl = shownUrl
        }
    }

    // 深浅判定跟详情页一致：种子本身暗（或系统是深色）就整页走深色。这一页的底色就是种子给的，
    // 所以浅色页只会出现在浅封面上，文字色跟着翻过来即可。
    val dark = seed?.dark == true || MaterialTheme.darkTheme

    fun tone(darkTone: Int, lightTone: Int): Color? =
        seed?.primaryTone(if (dark) darkTone else lightTone)?.let(::Color)

    // 颜色在这里只"起个头"：animateColorAsState 建出 State，立刻包成 ColorProducer 往下传，
    // 全程不读 `.value`。读了就是组合期读数——换歌那 500ms 会每帧把这一整层连同内容一起重组。
    // 真正读值的地方只有两处：绘制段（背景 / 图标 / 进度条），和叶子文字 composable。
    val contentColorTarget = if (dark) Color.White else Color.Black
    val backgroundColor = rememberThemedColor(
        tone(BgToneDark, BgToneLight) ?: MaterialTheme.colors.pageBackground,
        label = "playerBackground",
    )
    val contentColor = rememberThemedColor(contentColorTarget, label = "playerContent")
    val contentVariantColor = rememberThemedColor(
        tone(VariantToneDark, VariantToneLight) ?: MaterialTheme.colors.contentVariant,
        label = "playerVariant",
    )
    // 两侧的上一首/下一首跟详情页头部那排按钮同档：底 T40/T90，图标 T90/T40。
    val buttonColor = rememberThemedColor(
        tone(ButtonToneDark, ButtonToneLight) ?: MaterialTheme.colors.cardBackground,
        label = "playerButton",
    )
    val buttonIconColor = rememberThemedColor(
        tone(ButtonIconToneDark, ButtonIconToneLight) ?: MaterialTheme.colors.content,
        label = "playerButtonIcon",
    )
    // 播放键：站到页面底色的对立极上（深色页亮胶囊、浅色页深胶囊），全页对比度最高的一块。
    // 取色还没回来时退回「文字色反相」——那正好也是同一个极，所以不会先闪一下别的颜色再过渡。
    val playButtonColor = rememberThemedColor(
        tone(PlayToneDark, PlayToneLight) ?: contentColorTarget,
        label = "playerPlayButton",
    )
    val playButtonIconColor = rememberThemedColor(
        tone(PlayIconToneDark, PlayIconToneLight) ?: if (dark) Color.Black else Color.White,
        label = "playerPlayButtonIcon",
    )
    // 网格状态跟播放页同寿命：这一层离开组合，底下的 EGL 线程跟着销毁，不留后台负载。
    // 每次展开抽一套新网格：`remember` 不带键，而收起后 `present` 会翻回 false（开合动画一落定就
    // 摘掉这一层），所以"再展开一次"必定是一次全新的组合、也就是一次新的抽取。
    // ⚠️ 别把随机挂到 `cover` 上：那是换歌，换歌时网格是跟着同一片背景连续地流的；换预设反而是
    // 硬切——渲染器换预设直接重建 mesh，没有过渡（见 PearMeshGlRenderer.ensureSize）。
    val initialBackdropWash = tone(BgToneDark, BgToneLight) ?: MaterialTheme.colors.pageBackground
    val meshBackdrop = remember {
        val portrait = pickMeshPreset(PearMeshState.PortraitPresetCount, LastMeshPreset.portrait)
            .also { LastMeshPreset.portrait = it }
        val landscape = pickMeshPreset(PearMeshState.LandscapePresetCount, LastMeshPreset.landscape)
            .also { LastMeshPreset.landscape = it }
        PearMeshState(
            portraitPresetIndex = portrait,
            landscapePresetIndex = landscape,
        ).apply {
            setScrimAlpha(BackdropScrim)
            setBackdropWashColor(
                initialBackdropWash.red,
                initialBackdropWash.green,
                initialBackdropWash.blue
            )
        }
    }

    val artwork = expansion.artwork?.takeIf { it.cover == cover }
    var meshStarted by remember { mutableStateOf(false) }
    if (artwork != null) SideEffect { meshStarted = true }
    val onBackgroundReady = remember(expansion) {
        { ready: Boolean -> if (ready) expansion.backgroundReady = true }
    }
    var collapsed by remember { mutableStateOf(false) }
    val motionOccludesBackground by remember(motionOpacity, controller, motionUrl) {
        derivedStateOf {
            motionReady && controller.motionArtworkEnabled && motionUrl != null && motionOpacity.value >= 1f &&
                controller.isExpanded && !controller.isClosing && !controller.isDragging && !collapsed
        }
    }

    // 暂停时网格也停住——跟波浪落定是同一条规矩：动的东西只属于"正在播放"。
    LaunchedEffect(isPlaying) { meshBackdrop.setPlaybackPlaying(isPlaying) }

    LaunchedEffect(backgroundColor, meshBackdrop) {
        snapshotFlow { backgroundColor() }.collectLatest { color ->
            meshBackdrop.setBackdropWashColor(color.red, color.green, color.blue)
        }
    }

    val audioAnalysis = koinInject<AudioAnalysisStore>()
    DisposableEffect(audioAnalysis, isPlaying, motionOccludesBackground) {
        if (!isPlaying || motionOccludesBackground) {
            audioAnalysis.setListener(null)
            meshBackdrop.setAudioVisualizationEnabled(false)
        } else {
            audioAnalysis.setListener { power, generation ->
                meshBackdrop.setAudioPower(floatArrayOf(power, power, power, power), generation)
                meshBackdrop.setAudioVisualizationEnabled(true)
            }
        }
        onDispose {
            audioAnalysis.setListener(null)
            meshBackdrop.setAudioVisualizationEnabled(false)
        }
    }

    val heightPx = LocalWindowInfo.current.containerSize.height.toFloat().coerceAtLeast(1f)
    val dragState = rememberDraggableState { delta ->
        // 手指向上（delta 为负）是展开方向
        controller.onDrag(-delta / heightPx)
    }

    // 底部功能栏的选中态，三态：选中歌词 / 选中待播列表 / 都不选。
    // `null` 就是"都不选"，它是一个正常状态（两块面板都收起），不是"还没初始化"——
    // 所以不能拿一个"总有一边选中"的枚举初值来起头。
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }

    val queueVisible = panel == PlayerPanel.QUEUE
    var queuePresent by remember { mutableStateOf(false) }
    var queueReady by remember { mutableStateOf(false) }
    var queueBackdropVisible by remember { mutableStateOf(false) }
    val motionActive = !collapsed
    val queueAlpha = remember { Animatable(0f) }
    val queueScale = remember { Animatable(LyricsPanelScale) }
    val queueAlphaProducer = remember(queueAlpha) { { queueAlpha.value } }
    val queueScaleProducer = remember(queueScale) { { queueScale.value } }
    val onQueueReady: () -> Unit = remember { { queueReady = true } }
    val behindLyricsProgress = remember(meshBackdrop) { Animatable(1f) }
    LaunchedEffect(meshBackdrop, queueBackdropVisible) {
        behindLyricsProgress.animateTo(
            targetValue = if (queueBackdropVisible) 0f else 1f,
            animationSpec = Springs.smooth(durationMillis = 400),
        ) {
            meshBackdrop.setBehindLyricsProgress(value)
        }
    }

    // 歌词面板的进出场：**缩放走 spring、透明度走 tween**，两个值都交给 `LyricsPanel`，
    // 由它转交给 `KaraokeLyricsView` 的 `Plus` 合成层 —— 动画必须挂在那层上，在外面再包一层
    // 会把叠加上色的对象换成空图层，发光就没了。这里只持有动画值和**组合生命周期**：
    // 退出动画跑完才把子树摘掉，否则没有退场可言。
    val lyricsVisible = panel == PlayerPanel.LYRICS
    var lyricsPresent by remember { mutableStateOf(false) }
    val lyricsRepository = koinInject<LyricsRepository>()
    var lyricsResult by remember(song.id, lyricsVisible || lyricsPresent) {
        mutableStateOf(lyricsRepository.cached(song.id)?.let { Result.success(it) })
    }
    val lyrics = lyricsResult?.getOrNull()
    val hasLyrics = lyrics != null && !lyrics.instrumental && lyrics.lines.isNotEmpty()
    LaunchedEffect(song.id, lyricsVisible) {
        if (lyricsVisible && lyricsResult?.isSuccess != true) {
            lyricsResult = null
            // 标题栏只有精简元数据；lrclib 的匹配仍须使用队列中的完整歌曲。
            val queue = connection.queue.value
            val lyricsSong = queue.entries.getOrNull(queue.currentIndex)?.song
                ?.takeIf { it.id == song.id } ?: Song(id = song.id)
            lyricsResult = lyricsRepository.lyrics(lyricsSong)
        }
    }

    // 换歌即重置，避免新歌的场景复用上一首已经结束的入场动画。
    val lyricsAlpha = remember(song.id) { Animatable(0f) }
    val lyricsScale = remember(song.id) { Animatable(LyricsPanelScale) }
    // 生产者保持引用恒定，免得每帧给下游一个新 lambda。
    // `Animatable.value` 是快照状态，在图层段读 → 只重绘不重组。
    val lyricsAlphaProducer = remember(lyricsAlpha) { { lyricsAlpha.value } }
    val lyricsScaleProducer = remember(lyricsScale) { { lyricsScale.value } }

    // 歌词位图的单槽缓存：**挂在播放页的生命周期上**。
    // `PlayerLayer` 在 `present == false` 时整棵子树从组合里摘掉，这个 remember 跟着一起没 ——
    // 所以「页内关掉再打开歌词」是零成本复用，「关掉播放页」自动失效，不需要手工清理。
    // 只留当前这一首（换歌即替换），常驻占用上限与「面板开着」时相同。
    val lyricsRenderCache = remember { LyricsRenderCache() }

    // 这首歌在本播放页里**已经跑过一次真歌词场景**（于是位图缓存已填）。两种时序在这里分岔：
    //
    // - 已预热：场景发布只剩两帧（排版缓存 + 位图缓存都命中）。等它的代价可以忽略，那就让封面
    //   和标题行一起等 —— 歌词落定那一帧，三件事同起。
    // - 未预热：首次要付一次整曲光栅化。等它就是「点下去先僵一段、再一起动」，不如按老办法让
    //   封面先走，收完再放歌词出来。
    //
    // `remember(song.id)`：换歌即重置（位图缓存是单槽，换歌就被顶掉）；关掉播放页整个 remember
    // 一起没。判的是「打开过」而不是逐字段比 key —— 播放页拿不到 `prepared` 引用（那是歌词侧才
    // 有的东西），精确比对在这里做不到。而颜色写死、密度与方向在一次播放页里不变，
    // 「打开过」与「缓存命中」实际等价。
    var lyricsWarmed by remember(song.id) { mutableStateOf(false) }

    // 同起的选择锁在本次打开时。获取中途发布场景只能预热下次，不能跳过这次的封面门槛。
    val lyricsEnterTogether = remember(song.id, lyricsVisible) {
        lyricsVisible && lyricsWarmed && hasLyrics
    }
    // 获取结果会重启动画协程；本次是否从大封面进入不能跟着收起进度改变。
    val lyricsFromCover = remember(song.id, lyricsVisible) { lyricsVisible && !collapsed }
    val lyricsFromColdQueue = remember(song.id, lyricsVisible) {
        lyricsVisible && queuePresent && !lyricsWarmed
    }
    var lyricsReady by remember(song.id, lyrics) { mutableStateOf(false) }
    var lyricsCompositionAllowed by remember(song.id, lyrics) { mutableStateOf(false) }
    var lyricsStatusReady by remember(song.id, hasLyrics) { mutableStateOf(false) }
    var lyricsCoverWaitExpired by remember(song.id, lyricsVisible) { mutableStateOf(false) }
    val onLyricsReady: () -> Unit = remember(song.id, lyrics) {
        {
            lyricsReady = true
            lyricsWarmed = true
        }
    }
    LaunchedEffect(song.id, lyricsVisible) {
        if (lyricsEnterTogether) {
            delay(LyricsCoverReadyFallbackMs.milliseconds)
            if (!lyricsReady) {
                lyricsCoverWaitExpired = true
                collapsed = true
            }
        }
    }

    // 收起进度：0 = 展开（大封面居中 + 信息整行），1 = 收起（封面缩成小方块、落到标题左侧）。
    // 只建 State，值一律留到 graphicsLayer 的 block 里读——它每帧都在变，组合期读一次就等于
    // 整棵子树每帧重组，跟主题色那条规矩一模一样。
    // collapsed 只由动画协程提交，切换目标和等待布局时不能把已经收好的封面重新展开。

    val collapseCover = animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = if (collapsed) Springs.smooth(
            durationMillis = 400,
            visibilityThreshold = 0.0001f,
            extraBounce = 0.15
        ) else Springs.smooth(
            durationMillis = 400,
            visibilityThreshold = 0.0001f
        ),
        label = "PlayerCollapse",
    )
    val motionPanelCover = animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = tween(PlayerMotionArtworkDefaults.PanelTransitionMillis),
        label = "PlayerMotionPanelCover",
    )
    val staticCoverArtwork = remember { PlayerStaticCoverArtwork(motionReplacesCover) }
    SideEffect { if (motionReplacesCover) staticCoverArtwork.reserveMotion() }
    val staticCoverOpacity = animateFloatAsState(
        targetValue = if (motionReplacesCover || staticCoverArtwork.preparingReturn) 0f else 1f,
        animationSpec = tween(PlayerMotionArtworkDefaults.PanelTransitionMillis),
        label = "PlayerStaticCoverHandoff",
    )
    // 开合时沿用普通封面轨迹，淡出完成后才转到页内小封面的固定位置。
    val currentMotionReplacesCover by rememberUpdatedState(motionReplacesCover)
    val holdCoverArtwork = remember(expansion, staticCoverOpacity, motionPanelCover) {
        derivedStateOf {
            currentMotionReplacesCover && staticCoverOpacity.value > 0f &&
                motionPanelCover.value < 1f && expansion.progress >= 1f
        }
    }
    val coverPlacement = remember(expansion, collapseCover, staticCoverOpacity) {
        derivedStateOf {
            if (currentMotionReplacesCover && staticCoverOpacity.value <= 0f) {
                lerp(collapseCover.value, 1f, expansionFraction(expansion.progress, 0.7f, 1f))
            } else collapseCover.value
        }
    }
    val panelCoverProgress = remember(collapseCover, motionPanelCover) {
        derivedStateOf { if (currentMotionReplacesCover) motionPanelCover.value else collapseCover.value }
    }
    val panelCoverScale = remember(staticCoverOpacity, motionPanelCover) {
        derivedStateOf {
            if (currentMotionReplacesCover && staticCoverOpacity.value <= 0f) {
                lerp(PlayerMotionArtworkDefaults.PanelCoverHiddenScale, 1f, motionPanelCover.value.coerceIn(0f, 1f))
            } else 1f
        }
    }
    DisposableEffect(expansion, motionPanelCover, staticCoverOpacity) {
        expansion.targetCoverAlpha = {
            // 静态封面先原地淡出，透明后再改变隐藏位置；开合仍从迷你条的完整封面交接。
            val pageAlpha = lerp(staticCoverOpacity.value, 1f, motionPanelCover.value.coerceIn(0f, 1f))
            lerp(1f, pageAlpha, expansionFraction(expansion.progress, 0f, 0.7f))
        }
        onDispose { expansion.targetCoverAlpha = { 1f } }
    }

    val collapseMeta = animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = Springs.smooth(durationMillis = 400, visibilityThreshold = 0.0001f),
        label = "PlayerCollapse",
    )

    // 「标题行左边给封面占出的那一格」什么时候才算数。
    //
    // 🔴 不能直接用 `collapsed`。这一格是**布局一步到位**的：`PlayerMeta` 在 Row 里是 `weight(1f)`，
    // 格子一占上，标题/歌手的可用宽度立刻少一格（两个 `marquee()` 的可视区跟着变），而**位移**
    // 是由 meta 自己的 `graphicsLayer` 补出来的。宽度跑在位移前面 → 长标题会在**原地**先缩一下、
    // 再滑过去。等待面板布局又给「点开到真的动」之间插了几帧，更看得出来。
    //
    // 所以拿位移本身当开关：收起时等 progress 离开 0，展开时等它离开 1，才换宽度。
    // ⚠️ **位置不受这个开关影响**：两种布局下视觉位置都是 `rowLeft + gap * progress`（见下面
    // `translationX` 的两支），所以这一格什么时候翻都不会把位移弄断。
    var metaCell by remember { mutableStateOf(false) }
    LaunchedEffect(collapsed) {
        if (collapsed) snapshotFlow { collapseMeta.value }.first { it > 0f }
        else snapshotFlow { collapseMeta.value }.first { it < 1f }
        metaCell = collapsed
    }

    LaunchedEffect(song.id, panel, hasLyrics) {
        suspend fun exitLyrics() {
            if (!lyricsPresent) return
            animatePlayerPanel(lyricsAlpha, lyricsScale, visible = false)
            lyricsPresent = false
            lyricsCompositionAllowed = false
            // 退场途中重开会取消协程；只在真正摘子树后清除就绪信号。
            lyricsReady = false
            lyricsStatusReady = false
        }

        suspend fun exitQueue() {
            if (!queuePresent) return
            animatePlayerPanel(queueAlpha, queueScale, visible = false)
            queuePresent = false
            queueReady = false
        }

        val target = panel
        if (target == null) {
            collapsed = false
            queueBackdropVisible = false
            coroutineScope {
                launch { exitLyrics() }
                launch { exitQueue() }
            }
            return@LaunchedEffect
        }

        val coldQueueToLyrics = target == PlayerPanel.LYRICS && lyricsFromColdQueue
        if (coldQueueToLyrics) {
            // 首次光栅化不能阻塞列表退场；退出与歌词准备并行，歌词仍等自身就绪后入场。
            launch { exitQueue() }
            queueBackdropVisible = false
            lyricsAlpha.snapTo(0f)
            lyricsScale.snapTo(LyricsPanelScale)
            lyricsStatusReady = false
        }

        val switchingPanels = !coldQueueToLyrics && if (target == PlayerPanel.QUEUE) {
            lyricsPresent && lyricsAlpha.value > 0f
        } else {
            queuePresent && queueAlpha.value > 0f
        }

        when (target) {
            PlayerPanel.QUEUE -> {
                queuePresent = true
                snapshotFlow { queueReady }.first { it }
            }

            PlayerPanel.LYRICS -> {
                lyricsPresent = true
                if (hasLyrics) {
                    if (!lyricsReady && !lyricsCompositionAllowed) {
                        lyricsAlpha.snapTo(0f)
                        lyricsScale.snapTo(LyricsPanelScale)
                    }
                    // 未预热的首次光栅化仍避开封面收缩；面板互切时先发布目标场景。
                    if (lyricsFromCover && !lyricsEnterTogether) {
                        collapsed = true
                        snapshotFlow { panelCoverProgress.value }.first { it >= CollapseSettled }
                    }
                    lyricsCompositionAllowed = true
                    snapshotFlow { lyricsReady }.first { it }
                    if (lyricsFromCover && lyricsCoverWaitExpired) {
                        snapshotFlow { panelCoverProgress.value }.first { it >= CollapseSettled }
                    }
                } else {
                    if (lyricsFromCover) collapsed = true
                    snapshotFlow { lyricsStatusReady }.first { it }
                }
            }
        }

        val gapMillis = if (!switchingPanels) 0L else when (target) {
            PlayerPanel.QUEUE -> LyricsToQueueGapMs
            PlayerPanel.LYRICS -> QueueToLyricsGapMs
        }
        val enterOffset = if (switchingPanels) LyricsFadeOutMs + gapMillis else 0L
        // 背景跟先开始的一侧同起，不随两侧动画的间隔延后。
        queueBackdropVisible = target == PlayerPanel.QUEUE
        // 两条时间线都从目标布局就绪开始；很大的负间隔允许新面板先进入，再退出旧面板。
        coroutineScope {
            if (!coldQueueToLyrics) {
                launch {
                    delay((-enterOffset).coerceAtLeast(0L).milliseconds)
                    if (target == PlayerPanel.QUEUE) exitLyrics() else exitQueue()
                }
            }
            delay(enterOffset.coerceAtLeast(0L).milliseconds)
            collapsed = true
            if (target == PlayerPanel.QUEUE) {
                animatePlayerPanel(queueAlpha, queueScale, visible = true)
            } else {
                animatePlayerPanel(lyricsAlpha, lyricsScale, visible = true)
            }
        }
    }

    // 标题行的坐标。小封面要落到这一行左侧那一格，而它跟封面不在同一棵子树里（封面在舞台 Box、
    // 标题行是 Column 的第二个孩子），只能拿坐标换算。
    // 🔴 交给封面的是**坐标本身**，不是算好的窗口坐标差 —— 窗口坐标里含着播放页自己的开合位移，
    // 而这两处采样时刻不同，一相减就会把「页面走了多少」错算进收起的位移里。见 `PlayerCover`。
    var metaRow by remember { mutableStateOf<LayoutCoordinates?>(null) }

    PlayerExpansion(
        state = expansion,
        modifier = modifier
            .fillMaxSize()
            .then(
                if (controller.acceptsInput) Modifier.draggable(
                    orientation = Orientation.Vertical,
                    state = dragState,
                    onDragStopped = { velocity -> controller.onDragEnd(-velocity / heightPx) },
                ) else Modifier
            )
    ) {
        // 垫底用主题底色而不是纯黑：渲染器起来之前（EGL 起线程、编译着色器、上传封面）那一两百
        // 毫秒得有人占位，浅色页也不该先闪一下黑。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBackground(backgroundColor)
        )
        if (meshStarted || artwork != null) {
            PearMeshSurface(
                state = meshBackdrop,
                modifier = Modifier.fillMaxSize(),
                artwork = artwork?.bitmap,
                onFrameReady = onBackgroundReady,
                renderingEnabled = !motionOccludesBackground,
            )
        }
        PlayerMotionArtwork(
            url = motionUrl,
            albumId = motionSource.albumId,
            nextUrl = nextMotionSource.url,
            nextAlbumId = nextMotionSource.albumId,
            sourceResolved = motionSource.resolved,
            frameCache = motionFrames,
            active = motionActive,
            opacity = motionOpacity,
            backdropWashColor = backgroundColor,
            onStateChanged = onMotionStateChanged,
            modifier = Modifier.fillMaxSize(),
        )
        val onTogglePlay = remember(connection) { { connection.togglePlayPause() } }
        val onPrevious = remember(connection) { { connection.skipToPrevious() } }
        val onNext = remember(connection) { { connection.skipToNext() } }
        val onSeek = remember(connection) { { ms: Long -> connection.seekTo(ms) } }
        val onLyrics = remember { {} }
        val onQueue = remember { {} }
        val onAirplay = remember { {} }
        val onMore = remember { {} }

        // 面板切换的规则只有一条：**选中的再点一次就取消选中**（回到"都不选"），没选中的点了就
        // 选中它（另一个自然让位，因为状态只有一个）。写成一份，别在两个 onClick 里各抄一遍。
        val togglePanel = remember {
            { target: PlayerPanel -> panel = if (panel == target) null else target }
        }

        // 这里是**目标值**（不带动画）而不是补齐后的颜色：LocalContentColor 只有按下时的
        // 蒙层在用（DimIndication 在绘制段读它），蒙层是 12% 的瞬态，不需要跟着 500ms 过渡。
        ProvideContentColor(contentColorTarget) {
            Column(
                // 水平内边距下沉到各块：顶排要贴着边（12dp，跟详情页浮钮一致），正文要 24dp。
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 舞台区：面板（歌词）铺满，封面浮在上面——收起时它会缩成小方块飞向标题行，
                // 所以两块必须同父，`zIndex` 才管得住谁压谁。Box 默认不裁剪，封面飞出去也不会被切。
                Box(
                    modifier = Modifier
                        .zIndex(999f)
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (lyricsVisible || lyricsPresent) {
                        // 只从这里进——歌词场景深处（`rememberLyricsScene`）读它。
                        // 挂在环境上而不是加参数：`KaraokeLyricsView` 是搬进来的公开 API，
                        // 塞 `internal` 类型进签名编不过，一路降级又会把上游 diff 弄脏。
                        if (hasLyrics && lyricsCompositionAllowed) CompositionLocalProvider(
                            LocalLyricsRenderCache provides lyricsRenderCache
                        ) {
                            LyricsPanel(
                                songId = song.id,
                                lyrics = lyrics,
                                connection = connection,
                                seekPreviewPosition = seekState.previewPosition,
                                isSeeking = seekState.isDragging,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxSize(),
                                panelAlpha = lyricsAlphaProducer,
                                panelScale = lyricsScaleProducer,
                                onContentReady = onLyricsReady,
                            )
                        } else if (!hasLyrics && !(lyricsFromColdQueue && lyricsResult == null)) {
                            val status = when {
                                lyricsResult == null -> "正在获取歌词..."
                                lyricsResult?.isFailure == true ->
                                    lyricsResult?.exceptionOrNull()?.message ?: "暂无歌词"

                                else -> "暂无歌词"
                            }
                            LyricsStatus(
                                text = status,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = lyricsAlpha.value
                                        scaleX = lyricsScale.value
                                        scaleY = lyricsScale.value
                                    }
                                    .onGloballyPositioned { coordinates ->
                                        if (coordinates.size.width > 0 && coordinates.size.height > 0) {
                                            lyricsStatusReady = true
                                        }
                                    },
                            )
                        }
                    }

                    if (queueVisible || queuePresent) {
                        QueuePanel(
                            connection = connection,
                            modifier = Modifier
                                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 12.dp)
                                .fillMaxSize(),
                            panelAlpha = queueAlphaProducer,
                            panelScale = queueScaleProducer,
                            onContentReady = onQueueReady,
                        )
                    }

                    PlayerStage(
                        modifier = Modifier
                            .padding(24.dp),
                        cover = cover,
                        holdArtwork = holdCoverArtwork,
                        artwork = staticCoverArtwork,
                        motion = motionReplacesCover,
                        collapse = coverPlacement,
                        appearanceScale = panelCoverScale,
                        metaRow = metaRow,
                        expansion = expansion,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ContentPadding)
                        .onGloballyPositioned { metaRow = it },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(
                        Modifier.width(
                            if (metaCell) CollapsedCoverSize + CollapsedCoverGap else 0.dp
                        )
                    )
                    PlayerMeta(
                        title = song.title,
                        artist = song.artist,
                        titleColor = contentColor,
                        artistColor = contentVariantColor,
                        modifier = Modifier.graphicsLayer {
                            val shift = (CollapsedCoverSize + CollapsedCoverGap).toPx()
                            val progress = collapseMeta.value.coerceIn(0f, 1f)
                            translationX =
                                if (metaCell) -shift * (1f - progress) else shift * progress
                        },
                    )
                    HGap()
                    PlayerLikeButton(
                        songId = song.id,
                        background = buttonColor,
                        tint = buttonIconColor,
                    )
                    HGap()
                    CircleIconButton(
                        iconRes = R.drawable.ellipsis,
                        label = "更多",
                        background = buttonColor,
                        tint = buttonIconColor,
                        onClick = onMore,
                        size = MetaButtonSize,
                    )
                }
                VGap(8.dp)
                PlayerProgress(
                    stateFlow = connection.state,
                    onSeek = onSeek,
                    seekState = seekState,
                    contentColor = contentColor,
                    contentVariantColor = contentVariantColor,
                    modifier = Modifier.padding(horizontal = ContentPadding),
                )
                VGap(24.dp)
                PlayerControls(
                    isPlaying = isPlaying,
                    onTogglePlay = onTogglePlay,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    buttonColor = buttonColor,
                    buttonIconColor = buttonIconColor,
                    playButtonColor = playButtonColor,
                    playButtonIconColor = playButtonIconColor
                )
                VGap(36.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    PlayerPanelIcon(
                        iconRes = R.drawable.lyrics,
                        label = "歌词",
                        selected = panel == PlayerPanel.LYRICS,
                        tint = contentVariantColor,
                        onClick = {
                            togglePanel(PlayerPanel.LYRICS)
                            onLyrics()
                        },
                    )
                    Icon(
                        painter = painterResource(R.drawable.airplay),
                        contentDescription = "投放",
                        tint = contentVariantColor,
                        modifier = Modifier.size(28.dp)
                    )
                    PlayerPanelIcon(
                        iconRes = R.drawable.list_play,
                        label = "待播列表",
                        selected = queueVisible,
                        tint = contentVariantColor,
                        onClick = {
                            togglePanel(PlayerPanel.QUEUE)
                            onQueue()
                        },
                    )
                }
                VGap(24.dp)
            }
        }
    }
}

/**
 * 播放页底部功能栏里**可以选中**的那两块面板：歌词、待播列表。
 *
 * 三态由调用方持有：选中 [LYRICS]、选中 [QUEUE]、或 `null`（都不选）。
 */
private enum class PlayerPanel { LYRICS, QUEUE }

private suspend fun animatePlayerPanel(
    alpha: Animatable<Float, AnimationVector1D>,
    scale: Animatable<Float, AnimationVector1D>,
    visible: Boolean,
) = coroutineScope {
    launch {
        alpha.animateTo(
            if (visible) 1f else 0f,
            tween(if (visible) LyricsFadeInMs else LyricsFadeOutMs),
        )
    }
    scale.animateTo(if (visible) 1f else LyricsPanelScale, LyricsScaleSpring)
}

@Composable
private fun LyricsStatus(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.type.body,
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/**
 * 底部功能栏里的一颗可选中图标。
 *
 * 选中态是在图标底下垫一圈白，`BlendMode.Plus` 只把那一块**提亮一档**、不盖住底下的网格背景，
 * 所以深色页/浅色页能共用同一个值（跟原来的写法一致，这里只是把两份重复的绘制收成一处）。
 *
 * 结构分两层，各管一件事：外层 [Box] 只管**命中**（28dp 的布局框里塞一个 56dp 的真框，
 * 为什么必须是真的 56dp 见 [PanelTouchTargetSize]），里层 `Icon` 只管**视觉**（28dp + 那圈高亮）。
 * 两层中心重合，所以看上去跟以前一个像素都不差。
 *
 * 圈凭 `drawBehind` 排在 `.size` 之前画出来：绘制块量到的是排完尺寸之后的 28dp 方框，
 * 圆心落在方框中心、半径 20dp，正好探出去一圈。`selected` 是组合期传进来的普通布尔
 * （不是动画值），所以在绘制段读它没有任何问题。
 */
@Composable
private fun PlayerPanelIcon(
    iconRes: Int,
    label: String,
    selected: Boolean,
    tint: ColorProducer,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .wrapContentSize(Alignment.Center, unbounded = true)
            .requiredSize(PanelTouchTargetSize)
            .selectable(
                selected = selected,
                onClick = onClick,
                interactionSource = null,
                indication = null
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = tint,
            modifier = Modifier
                .drawBehind {
                    if (selected) {
                        drawCircle(
                            color = Color.White.copy(0.1f),
                            radius = 20.dp.toPx(),
                            blendMode = BlendMode.Plus
                        )
                    }
                }
                .size(28.dp),
        )
    }
}

/**
 * 大封面所在的舞台。
 *
 * 单独成块是为了让它在主题色过渡（换歌时每帧重组 500ms）期间被整块跳过：
 * `BoxWithConstraints` 是 SubcomposeLayout，重新 subcompose 是这一页最贵的一步，而它的输入
 * （封面串）跟颜色无关。所以这里只收封面串，颜色一律不进这一层。
 *
 * 尺寸由父级（舞台 Box）给：面板打开时它是叠在面板上的那一层，自己不再占布局。
 */
@Composable
private fun PlayerStage(
    modifier: Modifier = Modifier,
    cover: String,
    holdArtwork: State<Boolean>,
    artwork: PlayerStaticCoverArtwork,
    motion: Boolean,
    collapse: State<Float>,
    appearanceScale: State<Float>,
    metaRow: LayoutCoordinates?,
    expansion: PlayerExpansionState,
) {
    // 修饰符固定住，否则每次都是新实例，跳过就失效了
    val stageModifier = remember {
        modifier
            .fillMaxSize()
    }
    BoxWithConstraints(
        modifier = stageModifier,
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight).coerceAtMost(CoverSideCap)
        PlayerCover(
            cover = cover,
            holdArtwork = holdArtwork,
            artwork = artwork,
            motion = motion,
            side = side,
            collapse = collapse,
            appearanceScale = appearanceScale,
            metaRow = metaRow,
            expansion = expansion,
        )
    }
}

/**
 * 大封面。边长由调用方按可用区域的短边算好——竖屏是宽度说了算，横屏/平板上就不会撑破。
 *
 * 换封面时交叉淡入。没用 Coil 自带的 `crossfade`：`CrossfadeTransition.Factory.create` 里碰到
 * `dataSource == MEMORY_CACHE` 会直接返回 `NONE`，也就是说同一个封面看第二次是硬切——第一次
 * 交叉、回头又变回硬切，这种不一致比干脆不做更难受。
 *
 * 这里自己盯加载状态，关键在**什么时候**把新图交出去：必须等它真的解出来。解码期间继续画着上一张，
 * 否则交叉淡入的是一块空占位。
 */
@Composable
private fun PlayerCover(
    cover: String,
    holdArtwork: State<Boolean>,
    artwork: PlayerStaticCoverArtwork,
    motion: Boolean,
    side: Dp,
    collapse: State<Float>,
    appearanceScale: State<Float>,
    metaRow: LayoutCoordinates?,
    expansion: PlayerExpansionState,
) {
    val target = cover.takeIf { it.isNotEmpty() }
    val coverLayer = rememberGraphicsLayer()
    DisposableEffect(expansion, coverLayer) {
        expansion.targetCoverLayer = coverLayer
        onDispose {
            expansion.targetCoverLayer = null
            expansion.targetCoverOpaque = false
        }
    }
    val painter = rememberAsyncImagePainter(model = target, contentScale = ContentScale.Crop)
    val state by painter.state.collectAsState()

    // 已经上画的那张。`State.Success.painter` 每次加载都是新实例，刚好能当 Crossfade 的键。
    val front = artwork.painter
    val frontOpaque = artwork.opaque
    val holdingArtwork = holdArtwork.value
    LaunchedEffect(state, target, holdingArtwork, motion) {
        // 退场时保留原图；完全隐藏后才接收新歌封面，供面板和收起动画使用。
        if (holdingArtwork) return@LaunchedEffect
        val loaded = state as? AsyncImagePainter.State.Success
        // 目标为空时也要跟着清掉，不然上一首的封面会一直留在屏幕上
        if (target == null) {
            artwork.show(null, opaque = false, motion = motion)
        } else if (loaded != null && loaded.result.request.data == target) {
            artwork.show(
                loaded.painter,
                opaque = (loaded.result.image as? BitmapImage)?.bitmap?.hasAlpha() == false,
                motion = motion,
            )
        }
    }
    // Creating the transition with the first loaded painter starts at alpha 1. Later painters
    // update that same transition, preserving crossfades and their interruption behaviour.
    val coverTransition = key(artwork.transitionGeneration) {
        if (front != null) updateTransition(front, label = "PlayerCover") else null
    }

    // 收起目标：**标题行左缘中点，换算进封面自己的坐标系**。收起时就是拿它跟封面自己的中心
    // （`size/2`）相减，得到 graphicsLayer 要走的位移。封面不参与任何布局搬家，整段收起只有
    // 这一层在重画。
    //
    // 🔴 为什么不用两次 `positionInWindow` 相减（原来的写法）：窗口坐标里含着**播放页自己**的
    // 开合位移（外层 `Box` 的 `translationY`），而两个值不是同一时刻采的 ——
    //   · 标题行只在重排时刷新；
    //   · 封面自己的图层每帧都在变（收起弹簧一帧一个 translation，而 `NodeCoordinator` 发现
    //     图层位移变了会**主动**给它这张节点重发 `onGloballyPositioned`），所以收起动画期间它
    //     一帧采一次。
    // 于是页面一平移，「两次采样之间页面走了多少」就被算进取起位移里：封面跟着页面动的同时又被
    // 这层误差推了一把，看起来就是「封面收起来之后，页面下移时封面的移动又多套了一层动画」。
    // 换算进封面坐标系后，两个坐标系的共同祖先（也就是那一整段页面位移）在换算中直接抵消，
    // 采样时刻就不再有任何影响。
    val self = remember { mutableStateOf<LayoutCoordinates?>(null) }
    var anchor by remember { mutableStateOf<Offset?>(null) }
    val expandedShape = remember { RoundedRectangle(16.dp) }
    val coverBackground = MaterialTheme.colors.scrimMedium
    val density = LocalDensity.current
    val transform: GraphicsLayer.() -> Unit =
        remember(expansion, collapse, appearanceScale, anchor, density) {
            {
                with(density) {
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    val collapseProgress = collapse.value
                    val collapseTarget = anchor
                    val collapsedSide = CollapsedCoverSize.toPx()
                    val panelScale = if (collapseTarget != null) {
                        lerp(1f, collapsedSide / width, collapseProgress) * appearanceScale.value
                    } else 1f
                    val panelX = if (collapseTarget != null) {
                        (collapseTarget.x + collapsedSide / 2f - width / 2f) * collapseProgress
                    } else 0f
                    val panelY = if (collapseTarget != null) {
                        (collapseTarget.y - height / 2f) * collapseProgress
                    } else 0f
                    val progress = expansion.progress.coerceIn(0f, 1f)
                    val source = expansion.sourceCoverBounds
                    val targetBounds = expansion.targetCoverBounds
                    scaleX = lerp(source.width / width, panelScale, progress)
                    scaleY = lerp(source.height / height, panelScale, progress)
                    translationX = lerp(source.center.x - targetBounds.center.x, panelX, progress)
                    // Keep the dip out of measured geometry so reversing follows the same path.
                    val dip =
                        expansion.sourceBounds.top.coerceAtLeast(0f) * ExpandedCoverDipFraction *
                                progress * (1f - progress) * (1f - collapseProgress.coerceIn(
                            0f,
                            1f
                        ))
                    translationY =
                        lerp(source.center.y - targetBounds.center.y, panelY, progress) + dip
                }
            }
        }
    val coverShape = remember(expansion, collapse, appearanceScale, anchor, density, expandedShape) {
        { size: Size ->
            with(density) {
                val progress = expansion.progress.coerceIn(0f, 1f)
                if (progress >= 1f) expandedShape else {
                    val panelScale = if (anchor != null) {
                        lerp(1f, CollapsedCoverSize.toPx() / size.width, collapse.value) * appearanceScale.value
                    } else 1f
                    val scale = lerp(expansion.sourceCoverBounds.width / size.width, panelScale, progress)
                    val radius = lerp(MiniPlayerCoverRadius.toPx(), 16.dp.toPx() * panelScale, progress)
                    RoundedRectangle((radius / scale.coerceAtLeast(0.0001f)).toDp())
                }
            }
        }
    }
    SideEffect {
        expansion.targetCoverTransform = transform
        expansion.targetCoverShape = coverShape
        expansion.targetCoverBackground = coverBackground
        expansion.hasCover = target != null
    }

    fun resample(coords: LayoutCoordinates) {
        val row = metaRow ?: return
        if (!coords.isAttached || !row.isAttached) return
        self.value = coords
        // 只取左缘中点：x 加半个小方块就是收起后那一格的中心。
        // ⚠️ 不用再加 `ContentPadding` —— 标题行那个 `onGloballyPositioned` 挂在 `.padding()` 的**里面**，
        // 拿到的已经是正文内容区的左缘（边距已经被吃掉了），再加一次就整整右移一个 24dp。
        anchor = coords.localPositionOf(row, Offset(0f, row.size.height / 2f))
    }

    // 标题行的坐标比封面晚到（首帧顺序不保证）时补采一次。
    LaunchedEffect(metaRow) {
        val coords = self.value ?: return@LaunchedEffect
        resample(coords)
    }

    Box(
        modifier = Modifier
            .size(side)
            .onGloballyPositioned {
                expansion.onTargetCoverPositioned(it)
                resample(it)
            }
            .drawWithContent {
                coverLayer.record { this@drawWithContent.drawContent() }
                expansion.targetCoverOpaque = frontOpaque && coverTransition != null &&
                    coverTransition.currentState == coverTransition.targetState && !coverTransition.isRunning
            },
    ) {
        key(artwork.transitionGeneration) {
            coverTransition?.let { PlayerCoverCrossfade(it, CoverCrossfadeMs) }
        }
    }
}

@Composable
private fun PlayerLikeButton(songId: Long, background: ColorProducer, tint: ColorProducer) {
    val viewModel: PlayerLikeViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(viewModel, context) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val liked = state.songId == songId && state.liked == true
    CircleIconButton(
        iconRes = if (liked) R.drawable.star_fill else R.drawable.star,
        label = if (liked) "取消喜爱" else "喜爱",
        background = background,
        tint = tint,
        onClick = remember(viewModel) { { viewModel.toggle() } },
        enabled = state.songId == songId && !state.busy,
        size = MetaButtonSize,
    )
}

@Composable
private fun RowScope.PlayerMeta(
    title: String,
    artist: String,
    titleColor: ColorProducer,
    artistColor: ColorProducer,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.weight(1f)) {
        PlayerText(
            text = title,
            style = MaterialTheme.type.title4,
            color = titleColor,
            modifier = Modifier
                .fillMaxWidth()
                .marquee()
        )
        VGap(2.dp)
        PlayerText(
            text = artist,
            style = MaterialTheme.type.body,
            color = artistColor,
            modifier = Modifier
                .fillMaxWidth()
                .marquee()
        )
    }
}

// 文字色是组合期输入（要落进 TextStyle），只能在这一层读——颜色动画期间重组的就是这几行文字，
// 再往上的封面、按钮、进度条一次都不动。跟 MiniPlayer 的 MiniPlayerText 是同一个套路：
// 生产和消费之间隔一层，读的地方才会被 Compose 记成"只跟这一层有关"。
@Composable
private fun PlayerText(
    text: String,
    style: TextStyle,
    color: ColorProducer,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = style.merge(textMotion = TextMotion.Animated),
        color = color(),
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * 可拖的进度条 + 两端时间。
 *
 * 播放状态是自己订的（而不是由调用方把值传下来）：位置每 500ms 变一次，这个订阅把重组
 * 范围锁在进度条和两个时间上，上面那层——封面、标题、控制条——一次都不会被吵醒。
 *
 * 拖拽期间进度条与歌词共用预览位置。松手后提交 seek，等播放器确认再恢复播放时钟，
 * 避免控制器尚未回报时短暂跳回旧位置。
 */
@Composable
private fun PlayerProgress(
    stateFlow: StateFlow<PlaybackState>,
    onSeek: (Long) -> Unit,
    seekState: PlayerSeekState,
    contentColor: ColorProducer,
    contentVariantColor: ColorProducer,
    modifier: Modifier = Modifier,
) {
    val state by stateFlow.collectAsState()
    val positionMs = state.positionMs
    val durationMs = state.durationMs

    val dragging by seekState.isDragging
    val previewPosition by seekState.previewPosition

    val target = previewPosition?.takeIf { durationMs > 0L }?.let {
        (it.toFloat() / durationMs).coerceIn(0f, 1f)
    } ?: state.progress

    DisposableEffect(seekState) { onDispose { seekState.cancel() } }
    LaunchedEffect(seekState, state.discontinuity) {
        seekState.onPlaybackDiscontinuity(stateFlow.value.discontinuity)
    }
    LaunchedEffect(seekState, dragging, previewPosition != null) {
        if (!dragging && seekState.previewPosition.value != null) {
            delay(1500.milliseconds)
            seekState.cancel()
        }
    }

    // 位置心跳 500ms 一拍。用同样时长的线性补间把两拍之间填匀，进度头才是匀速走而不是一顿一顿；
    // 拖拽时必须跟手指，所以给 snap 不补。
    // 这里只建 State，`.value` 留给 WavyLinearProgress 在绘制段读——补间每帧变，读在组合期
    // 就等于这一页每帧重组一次。
    val smooth = animateFloatAsState(
        targetValue = target,
        animationSpec = if (dragging) snap() else tween(ProgressTweenMs, easing = LinearEasing),
        label = "PlayerProgress",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        WavyLinearProgress(
            progress = { smooth.value },
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(TrackTouchHeight)
                .pointerInput(seekState, durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            seekState.begin((smooth.value * durationMs).toLong(), durationMs)
                        },
                        onDragEnd = {
                            seekState.commit(stateFlow.value.discontinuity)?.let(onSeek)
                        },
                        onDragCancel = seekState::cancel,
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            seekState.dragBy(delta / size.width.coerceAtLeast(1), durationMs)
                        },
                    )
                },
            activeColor = contentColor,
            trackColor = contentVariantColor,
            strokeWidth = TrackThickness,
            waveHeight = TrackWaveHeight,
            wavelength = TrackWavelength,
            rolling = state.isPlaying,
            interacting = dragging,
            playheadWidth = TrackPlayheadWidth,
            playheadHeight = TrackPlayheadHeight,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            PlayerText(
                text = formatTime(previewPosition ?: positionMs),
                style = MaterialTheme.type.footnote,
                color = contentVariantColor,
            )
            Box(modifier = Modifier.weight(1f))
            PlayerText(
                text = formatTime(durationMs),
                style = MaterialTheme.type.footnote,
                color = contentVariantColor,
            )
        }
    }
}

/**
 * 播放控制条。
 *
 * 「播放/暂停」用实心圆底反白，是这一页唯一的主操作；两侧是纯图标按钮，跟详情页头部那排
 * 按钮同档配（底 T40/T90、图标 T90/T40），视觉上让开。
 *
 * 播放模式（循环 / 随机）挪进了待播列表面板那排按钮，这里不再管 —— 参数也跟着少了两个。
 *
 * 四种颜色都收 producer：条子是换歌时跟着主题色一起过渡的，收值的话这 500ms 里整条控制条
 * 每帧重组，还会顺着参数把上面那层也带上。
 *
 * 播放键按压时会横向撑开 16dp（[rememberPressAnimatable]），左右两颗跟着外移——**这一处的宽度
 * 就让它读在组合期**：撑开本来就要重新测量，延迟读只省下重组那一份，为一次 150ms 的按压不值当。
 */
@Composable
private fun PlayerControls(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    buttonColor: ColorProducer,
    buttonIconColor: ColorProducer,
    playButtonColor: ColorProducer,
    playButtonIconColor: ColorProducer,
    modifier: Modifier = Modifier,
) {
    val press = rememberPressAnimatable(
        atRest = 0f,
        whenPressed = 1f,
        onPressSpec = Springs.smooth(150),
        onReleaseSpec = Springs.smooth(200, 0.3),
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .width(80.dp)
                .height(80.dp)
                .clip(Capsule())
                .drawBackground(buttonColor)
                .clickable(onClick = onPrevious),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.backward_end_fill),
                tint = buttonIconColor,
                contentDescription = "上一首",
                modifier = Modifier.size(32.dp),
            )
        }
        HGap(8.dp)
        Box(
            modifier = Modifier
                .width(128.dp + 16.dp * press.progress.value)
                .height(80.dp)
                .clip(RoundedRectangle(20.dp))
                .drawBackground(playButtonColor)
                .clickable(onClick = onTogglePlay, interactionSource = press.interactionSource),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(
                    if (isPlaying) R.drawable.pause_fill else R.drawable.play_fill
                ),
                tint = playButtonIconColor,
                contentDescription = if (isPlaying) "暂停" else "播放",
                modifier = Modifier.size(32.dp),
            )
        }
        HGap(8.dp)
        Box(
            modifier = Modifier
                .width(80.dp)
                .height(80.dp)
                .clip(Capsule())
                .drawBackground(buttonColor)
                .clickable(onClick = onNext),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.forward_end_fill),
                tint = buttonIconColor,
                contentDescription = "下一首",
                modifier = Modifier.size(32.dp),
            )
        }
//        // 和左边的模式按钮对称：现在的队列入口是「查看队列」，还没做，先留空位
//        Box(modifier = Modifier.size(48.dp))
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}
