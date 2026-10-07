package com.nevoit.cirro.lyrics.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.unit.dp
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.lyrics.core.model.ISyncedLine
import com.nevoit.cirro.lyrics.core.model.SyncedLyrics
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeAlignment
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeSyllable
import com.nevoit.cirro.lyrics.data.LyricsRepository
import com.nevoit.cirro.lyrics.model.LyricBackground
import com.nevoit.cirro.lyrics.model.LyricLine
import com.nevoit.cirro.lyrics.model.LyricSyllable
import com.nevoit.cirro.lyrics.model.Lyrics
import com.nevoit.cirro.lyrics.model.LyricsSource
import com.nevoit.cirro.lyrics.ui.composable.list.LyricsScrollChain
import com.nevoit.cirro.lyrics.ui.composable.list.rememberLyricsLazyListState
import com.nevoit.cirro.lyrics.ui.composable.lyrics.KaraokeLyricsView
import com.nevoit.cirro.lyrics.ui.composable.lyrics.LyricsAnchor
import com.nevoit.cirro.lyrics.ui.composable.lyrics.LyricsFade
import com.nevoit.cirro.playback.PlaybackConnection
import com.nevoit.material.core.component.Text
import com.nevoit.material.theme.MaterialTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds

/** 颜色过渡停下来多久才算「稳定」。见 [rememberSettledLyricsColor]。 */
private const val ColorSettleMs = 120L

/**
 * 歌词跟随的手感，调参就改这一行（换算见 [LyricsScrollChain.of]）。
 *
 * `durationMillis` 是一个自然周期 `2π/ω₀` —— 不是"多久落定"，ζ=0.5 时实际停稳要比它长 47%；
 * `bounce` 是 `1 - ζ`：`0` 完全不弹（显得木）、`0.5` 过冲约 16%。
 *
 * 这只管**每行各自的链式弹簧**。整体基准位移的时长是 `KaraokeLyricsView` 的
 * `scrollAnimationSpec`（`Springs.smooth`，同样是"感知时长"）—— 嫌整体慢/快时两个要一起看。
 *
 * 取 `private val` 而不是每次组合现算：引用恒定，免得那条 `LaunchedEffect(state, scrollChain)`
 * 因新实例被反复重启。
 */
private val LyricsScrollSpring = LyricsScrollChain.of(durationMillis = 300f, bounce = 0.3f)

/**
 * 歌词面板：从 [LyricsRepository] 取词，适配成渲染层模型，交给 `KaraokeLyricsView`。
 *
 * 渲染实现整体搬自 `6xingyv/accompanist-lyrics-ui`（已落到 `lyrics/ui/composable`、
 * `lyrics/ui/internal`、`lyrics/ui/profile`），这里只负责把 Cirro 的东西接上去：
 * 取词、模型适配、颜色、位置时钟。
 *
 * @param songId 当前这首歌的网易云 id。换歌时列表状态整个重建。
 * @param connection 播放连接。位置订阅在面板内部完成——把 `positionMs` 当参数传进来会让
 *   播放页每 500ms 重组一次，而那一页最贵的封面舞台经不起这个。
 * @param color 正文色。收 `ColorProducer` 而不是 `Color`：换歌那 500ms 它是逐帧变的。
 */
@Composable
fun LyricsPanel(
    songId: Long,
    connection: PlaybackConnection,
    color: ColorProducer,
    modifier: Modifier = Modifier,
) {
    val repository = koinInject<LyricsRepository>()
    val listState = key(songId) { rememberLyricsLazyListState() }
    val position = rememberLyricsPosition(connection)
    val textColor = rememberSettledLyricsColor(color)

    // 缓存命中是同步的（切歌时 PlaybackService 已经预取过），所以绝大多数情况下第一帧就有词。
    var lyrics by remember(songId) { mutableStateOf(repository.cached(songId)) }

    // 失败原因要看得见。「取不到」可能是没网、可能是没凭据、可能四级都没有 —— 只写
    // 「暂无歌词」的话这三种在界面上长得一模一样，排不了障。
    var failure by remember(songId) { mutableStateOf<String?>(null) }

    LaunchedEffect(songId) {
        if (lyrics != null) return@LaunchedEffect
        failure = null
        // 队列里那条才是完整的 Song：lrclib 那级兜底要靠曲目信息模糊查，只有 id 是查不到的
        val queue = connection.queue.value
        val song = queue.entries.getOrNull(queue.currentIndex)?.song ?: Song(id = songId)
        repository.lyrics(song)
            .onSuccess { lyrics = it }
            .onFailure { failure = it.message }
    }

    // 回调必须稳定，否则 `KaraokeLyricsView` 里那些按参数跳过重组的判断全失效。
    val currentPosition = remember(position) { { position.intValue } }
    val onLineClicked =
        remember(connection) { { line: ISyncedLine -> connection.seekTo(line.start.toLong()) } }
    val onLinePressed = remember { { _: ISyncedLine -> } }

    Box(modifier = modifier) {
        val source = lyrics
        val model = remember(source, songId) {
            source?.takeIf { !it.instrumental }?.toSyncedLyrics(songId)
        }
        when {
            source == null -> LyricsPlaceholder(failure ?: "正在找歌词…", textColor)
            source.instrumental -> LyricsPlaceholder("纯音乐，请欣赏", textColor)
            model == null || model.lines.isEmpty() -> LyricsPlaceholder("暂无歌词", textColor)
            else ->
                KaraokeLyricsView(
                    listState = listState,
                    lyrics = model,
                    currentPosition = currentPosition,
                    onLineClicked = onLineClicked,
                    onLinePressed = onLinePressed,
                    textColor = textColor,
                    // 锚点与淡出长度取参考库 sample 那一组（`PlayerLyrics` 的调用参数）。
                    anchor = LyricsAnchor.Fraction(0.3f),
                    bottomFade = LyricsFade.Fraction(0.2f),
                    scrollChain = LyricsScrollSpring,
                    modifier = Modifier.fillMaxSize(),
                )
        }

//        // 来源标注。这次到底是拿的 Apple 官方逐字、还是兜底的网易 LRC，一眼可辨 ——
//        // 取词链有四档，光看画面分不出来（第 2 档的 AMLL TTML 同样是逐字的）。
//        source?.takeIf { !it.instrumental }?.let {
//            Text(
//                text = it.source.label,
//                style = MaterialTheme.type.footnote,
//                color = textColor.copy(alpha = 0.3f),
//                modifier =
//                    Modifier
//                        .align(Alignment.BottomStart)
//                        .padding(start = 20.dp, bottom = 14.dp),
//            )
//        }
    }
}

/**
 * 只订阅「稳定下来」的颜色。
 *
 * 参考库把颜色**烘焙进文本位图**（`LyricsRenderResources` → `prepareLineRaster`），所以颜色一变
 * 就要把整首重新排版。而 [color] 是 `animateColorAsState` 出来的**动画值**，过渡那 500ms 里
 * 逐帧都在变——在组合期直接读它，等于每帧重建一次场景。
 *
 * 所以这里用 `snapshotFlow` 把读取挪出组合期（顺带符合 `ColorProducer` 的使用约定），
 * 再用 `collectLatest` + 一个短延时把过渡中的中间值全部丢掉，只留停下来的那个。
 * 代价是颜色在过渡结束后才换上，但封面切换本身也在做 Crossfade，看不出来。
 */
@Composable
private fun rememberSettledLyricsColor(color: ColorProducer): Color {
    var settled by remember(color) { mutableStateOf(color()) }
    LaunchedEffect(color) {
        snapshotFlow { color() }.collectLatest { value ->
            delay(ColorSettleMs.milliseconds)
            settled = value
        }
    }
    return settled
}

@Composable
private fun LyricsPlaceholder(text: String, color: Color) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.type.body,
            color = color.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** 取词来源的短标签，给面板左下角那个标注用。 */
private val LyricsSource.label: String
    get() = when (this) {
        LyricsSource.APPLE_MUSIC -> "Apple Music 官方"
        LyricsSource.AMLL_TTML_DB -> "AMLL TTML DB"
        LyricsSource.NETEASE -> "网易云"
        LyricsSource.LRCLIB -> "lrclib"
    }

// ---------------------------------------------------------------------------
// 模型适配：Cirro 的 Lyrics → 渲染层的 SyncedLyrics
// ---------------------------------------------------------------------------

/**
 * 时间一律**毫秒 Long**（Cirro 的规矩），但渲染层模型用的是 `Int`。
 *
 * 不会溢出：Cirro 解析阶段就把时间戳封在 `Lyrics.MAX_TIMESTAMP_MS`（≈16.7 小时）以内，
 * 离 `Int.MAX_VALUE` 还有一个数量级。这里仍然夹一道，只为了让 `require(end >= start)`
 * 这类构造断言永远不会因为脏数据把进程带走。
 */
private fun Long.toLyricsMillis(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/**
 * 逐行同步（网易 LRC 那条链）的行没有 syllable——整行就是一个时间区间。
 * 造一个覆盖整行的 syllable 交给渲染层，它内部的 profile 会按字形边界再切分组
 * （CJK 一个字一组），逐字效果照常成立。
 */
private fun lineSyllables(line: LyricLine, start: Int, end: Int): List<KaraokeSyllable> =
    if (line.syllables.isEmpty()) listOf(KaraokeSyllable(line.text, start, end))
    else line.syllables.map { it.toKaraokeSyllable() }

private fun LyricSyllable.toKaraokeSyllable(): KaraokeSyllable {
    val from = startMs.toLyricsMillis()
    return KaraokeSyllable(
        content = text,
        start = from,
        end = endMs.toLyricsMillis().coerceAtLeast(from),
        // Cirro 的逐字注音是 `ruby` 列表，与渲染层「整行一个音译串」的模型对不上——
        // 逐字注音的渲染等以后再接，这里先不给。
        phonetic = null,
    )
}

/**
 * 背景人声 / 和声（`<span ttm:role="x-bg">`）。
 *
 * 挂在所属主行上；时间区间可以比主行更早进入，渲染层照它自己的可见窗口处理。
 */
private fun LyricBackground.toAccompanimentLine(
    alignment: KaraokeAlignment
): KaraokeLine.AccompanimentKaraokeLine {
    val from = startMs.toLyricsMillis()
    val to = endMs.toLyricsMillis().coerceAtLeast(from)
    val syllables =
        if (this.syllables.isEmpty()) listOf(KaraokeSyllable(text, from, to))
        else this.syllables.map { it.toKaraokeSyllable() }
    return KaraokeLine.AccompanimentKaraokeLine(
        syllables = syllables,
        translation = translations.firstOrNull()?.text?.takeIf { it.isNotEmpty() },
        alignment = alignment,
        start = from,
        end = to,
        phonetic = romanizations.firstOrNull()?.text?.takeIf { it.isNotEmpty() },
    )
}

/**
 * 一行 → 渲染层的行。
 *
 * [LyricLine.isBackground]（整行就是被括号包起来的和声）走 `AccompanimentKaraokeLine`，
 * 它跟主行是两条独立的行；[LyricLine.background]（主行内嵌的和声）则挂到主行的
 * `accompanimentLines` 上——两种在渲染层是不同路径，别混。
 *
 * [LyricLine.isDuet] 映射成 `KaraokeAlignment.End`：对唱的成对侧要右对齐。
 */
private fun LyricLine.toKaraokeLine(): KaraokeLine {
    val from = startMs.toLyricsMillis()
    val to = endMs.toLyricsMillis().coerceAtLeast(from)
    val alignment = if (isDuet) KaraokeAlignment.End else KaraokeAlignment.Start
    val translation = translations.firstOrNull()?.text?.takeIf { it.isNotEmpty() }
    val phonetic = romanizations.firstOrNull()?.text?.takeIf { it.isNotEmpty() }
    val syllables = lineSyllables(this, from, to)

    return if (isBackground) {
        KaraokeLine.AccompanimentKaraokeLine(
            syllables = syllables,
            translation = translation,
            alignment = alignment,
            start = from,
            end = to,
            phonetic = phonetic,
        )
    } else {
        KaraokeLine.MainKaraokeLine(
            syllables = syllables,
            translation = translation,
            alignment = alignment,
            start = from,
            end = to,
            phonetic = phonetic,
            accompanimentLines = background?.let { listOf(it.toAccompanimentLine(alignment)) },
        )
    }
}

/**
 * Cirro 的歌词模型 → 渲染层模型。
 *
 * 只做字段搬运，**不做任何时间推导**——结束时间在解析阶段就推好了（末行按下一行起点或
 * `MAX_TIMESTAMP_MS` 封顶），模型里不出现哨兵值，这里也不需要兜。
 */
private fun Lyrics.toSyncedLyrics(songId: Long): SyncedLyrics =
    SyncedLyrics(
        lines = lines.map { it.toKaraokeLine() },
        title = metadata.title.orEmpty(),
        id = songId.toString(),
    )
