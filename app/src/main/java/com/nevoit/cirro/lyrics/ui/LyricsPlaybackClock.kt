package com.nevoit.cirro.lyrics.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.nevoit.cirro.playback.PlaybackConnection
import com.nevoit.cirro.playback.PlaybackState
import kotlinx.coroutines.flow.collectLatest

/**
 * 歌词的播放位置：**逐帧**、且**只许前进**。
 *
 * ## 为什么不直接把 `PlaybackState.positionMs` 喂给歌词
 *
 * 那个位置有两个毛病，都会让歌词"突然跳回去一点"：
 *
 * 1. **太粗**：它是 2Hz 采样 + 事件驱动，逐字擦除需要逐帧的精度；
 * 2. **会倒退**：重新缓冲、以及事件回调与 500ms 轮询的时序错位，都会让某次采样比上一次小。
 *
 * 第 2 条对参考库尤其致命——`LyricsPlaybackTimeline.update()` 把"时间比上一次小"一律当成
 * seek（`val seek = ... || time < previousTime || ...`），于是一次几毫秒的采样抖动会触发
 * 整条时间轴的全量重算，视觉上就是猛地跳一下。
 *
 * ## 做法
 *
 * 在 [LyricsPositionCursor] 里：**单调钳制**（丢掉没被标记的回退）+ **放行真跳**
 * （[PlaybackState.discontinuity] 变了才允许任意跳）。然后在这里把采样点当锚，
 * 按帧时间线性外推补齐两拍之间。
 *
 * 返回值供 `KaraokeLyricsView(currentPosition = { it.intValue })` 使用——**读的地方在绘制期，
 * 千万不要在组合期读它**，否则整个歌词页每帧重组。
 */
@Composable
internal fun rememberLyricsPosition(connection: PlaybackConnection): IntState {
    // 初值直接取播放器的当前位置，**别从 0 起**。从 0 起的话时间轴会先按 0 建立，
    // 等第一个采样到了才跳到真值——那期间 scene 就绪、列表按「第一行」算出落点，
    // 于是画面停在顶部再滚一下。`state` 是 StateFlow，组合期读 `.value` 是同步快照；
    // 光标（`LyricsPositionCursor`）首次采样本就直接采纳，所以这里的种子不会跟它打架。
    val position =
        remember(connection) {
            mutableIntStateOf(
                connection.state.value.positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            )
        }
    LaunchedEffect(connection) {
        val cursor = LyricsPositionCursor()
        // 每收到一个采样就重启一个帧循环：循环体捕获的是**这一份**采样，
        // 所以外推用的 position / sampledAtMillis 始终自洽，不会一边推一边换锚。
        connection.state.collectLatest { sample ->
            position.intValue = cursor.advance(sample, SystemClock.uptimeMillis())
            if (!sample.isPlaying) return@collectLatest
            val end = sample.durationMs.takeIf { it > 0L }
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?: Int.MAX_VALUE.toLong()
            while (position.intValue < end) {
                withFrameNanos {
                    position.intValue = cursor.advance(sample, SystemClock.uptimeMillis())
                }
            }
        }
    }
    return position
}

/**
 * 把播放器报的位置整形成一个只许前进的时间。
 *
 * 两条规则必须成对出现，缺一个都会坏：
 * - **只钳制不放行**：拖进度条 / 单曲循环 / 切歌之后，回退被丢掉，歌词永远卡在原地；
 * - **只放行不钳制**：采样一抖画面就跟着往回跳（就是我们要治的那个毛病）。
 *
 * 抄的是参考库作者 sample 里的 `PlaybackPositionCursor`（库本体不管这件事，
 * 它把"喂进来的时间必须单调"当成调用方的契约）。
 */
internal class LyricsPositionCursor {

    /** 上一次见到的 [PlaybackState.discontinuity]。变了 = 发生了真跳。 */
    private var revision: Long? = null

    /** 上一次输出。单调钳制拿它比。 */
    private var previous = 0

    /** 第一次采样直接采纳——`previous` 初始是 0，会被 `maxOf` 把真实位置吃掉。 */
    private var started = false

    fun advance(sample: PlaybackState, nowMillis: Long): Int {
        val candidate = estimate(sample, nowMillis)

        // 控制器有可能先发循环 / 换曲事件、隔一拍才发第一个位置采样（那一刻还没打上标记），
        // 于是"从曲尾跳回曲首"看起来像一次非法回退。单独放行这一种：只认「从结尾退到开头」，
        // 且窗口不超过曲长的四分之一——避免短曲里把正常前进误判成重开。
        val window = minOf(1_000L, sample.durationMs / 4)
        val restarted =
            sample.durationMs > 0L &&
                window > 0L &&
                previous.toLong() >= sample.durationMs - window &&
                candidate.toLong() <= window

        previous =
            if (!started || revision != sample.discontinuity || restarted) candidate
            else maxOf(previous, candidate)
        revision = sample.discontinuity
        started = true
        return previous
    }

    /** 暂停时外推量取 0——位置就该停在原地，等下一个采样。 */
    private fun estimate(sample: PlaybackState, nowMillis: Long): Int {
        val elapsed =
            if (sample.isPlaying) (nowMillis - sample.sampledAtMillis).coerceAtLeast(0L) else 0L
        val end =
            sample.durationMs.takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?: Int.MAX_VALUE.toLong()
        return (sample.positionMs + elapsed).coerceIn(0L, end).toInt()
    }
}
