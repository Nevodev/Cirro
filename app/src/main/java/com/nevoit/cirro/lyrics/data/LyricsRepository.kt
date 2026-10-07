package com.nevoit.cirro.lyrics.data

import android.util.Log
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.lyrics.model.Lyrics
import com.nevoit.cirro.lyrics.model.LyricsSource
import com.nevoit.cirro.lyrics.parser.LrcLyricsParser
import com.nevoit.cirro.lyrics.parser.TtmlLyricsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 歌词的唯一入口：按歌曲自动走四级取词链，解析成统一模型，并缓存 / 落盘。
 *
 * ## 取词链
 * 1. **Apple Music 官方 TTML**（需 `media-user-token`）；
 * 2. **AMLL TTML DB** —— 用网易歌曲 id 直取，**一次请求同时拿到 TTML 和 `appleMusicId`**；
 * 3. **网易 LRC**（只逐行）；
 * 4. **lrclib**。
 *
 * ⚠️ 顺序上有个必须说清的点：① 的 Apple id **只能从 ② 的响应里拿到**（`amll:meta` 里带着
 * `appleMusicId`），所以每次都是先发 AMLL 那一次请求：
 * - 拿到 id → 走 Apple；Apple 有词就用 Apple 的，没有就用手里这份 AMLL TTML；
 * - 没有 id（约 30% 条目没有 Apple 版本）→ 直接用 AMLL TTML。
 *
 * 也就是说 AMLL 既是「第 2 优先源」又是「第 1 优先源的索引」，一次请求两用，不额外开销。
 *
 * ## 缓存
 * 进程内 + 磁盘快照（[LyricsStore]）。同一首歌重复播放零请求；
 * [prefetch] 在切歌时提前把歌词取回来，界面要显示时通常已经就绪。
 *
 * 🔴 所有桥接调用都是**同步阻塞**的（Rust 侧 `block_on`），一律在 IO 线程上跑。
 */
class LyricsRepository(
    private val store: LyricsStore,
    private val scope: CoroutineScope,
    private val appleMusic: AppleMusicAccountRepository,
) {

    /** 进程内缓存。键是歌曲 id —— 歌词只跟歌曲有关，与播放列表上下文无关。 */
    private val cache = ConcurrentHashMap<Long, Lyrics>()

    /** 正在取的歌曲 id，避免快速切歌时同一首重复发请求。 */
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    init {
        // Apple 凭据由 AppleMusicAccountRepository 在 App 启动时下发给原生层，这里不掺和。
        // 但要盯着它的登录态：登上 / 退出 Apple 会改变取词链的**优先级**，已经缓存下来的歌词
        // 必须作废，否则用户登完发现什么都没变（内存与磁盘快照都还在）。
        // `drop(1)` 跳过冷启动那一次状态落定 —— 它只是 restore() 的结果，不是"变化"。
        scope.launch {
            appleMusic.session
                .filter { it != AppleMusicSession.Unknown }
                .drop(1)
                .collect { invalidate() }
        }
    }

    /** 已经在内存里的歌词，命中即同步返回（界面可以先画这一份）。 */
    fun cached(songId: Long): Lyrics? = cache[songId]

    /**
     * 取一首歌的歌词。命中缓存 / 磁盘快照就零请求。
     *
     * 失败只有一种含义：**四级全都没取到**（或全都在网络层失败）。单级失败不算失败，
     * 会继续往下回落 —— 调用方不必区分。
     *
     * 🔴 整条链外面还兜了一层 [runCatching]：桥接调用抛的是 `UnsatisfiedLinkError`
     * 这类 `Error`（比如 .so 没打进包），那种情况必须变成 `Result.failure` ——
     * 否则它会从 [prefetch] 的 `launch` 里冒出去成为未捕获异常，直接带走进程。
     */
    suspend fun lyrics(song: Song): Result<Lyrics> = withContext(Dispatchers.IO) {
        cached(song.id)?.let { return@withContext Result.success(it) }
        store.read(song.id)?.let { snapshot ->
            cache[song.id] = snapshot
            return@withContext Result.success(snapshot)
        }
        runCatching { resolve(song) }
            .getOrElse { Result.failure(it) }
    }

    /**
     * 后台预取（切歌时调）。同一首歌已有请求在跑就直接跳过。
     */
    fun prefetch(song: Song) {
        if (cache.containsKey(song.id) || !inFlight.add(song.id)) return
        scope.launch {
            try {
                lyrics(song).onFailure { Log.d(Tag, "预取 ${song.id} 未取到：${it.message}") }
            } finally {
                inFlight.remove(song.id)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 取词链本体
    // -----------------------------------------------------------------------

    private fun resolve(song: Song): Result<Lyrics> {
        val failures = mutableListOf<String>()

        // ① 先问 AMLL：它同时给出 TTML 和 Apple id（见类注释）
        val amll = attempt("AMLL", failures) { LyricsBridge.amll(song.id) }

        // ② Apple 官方
        if (amll != null && amll.appleMusicIds.isNotEmpty() && LyricsBridge.appleConfigured()) {
            for (appleId in amll.appleMusicIds) {
                val ttml = attempt("Apple($appleId)", failures) {
                    LyricsBridge.apple(appleId).map { it.ttml }
                }
                if (ttml.isNullOrBlank()) continue
                parseTtml(ttml, LyricsSource.APPLE_MUSIC, failures)?.let { return remember(song.id, it) }
            }
        }

        // ③ 回落到手里这份 AMLL TTML
        if (amll != null) {
            parseTtml(amll.ttml, LyricsSource.AMLL_TTML_DB, failures)
                ?.let { return remember(song.id, it) }
        }

        // ④ 网易 LRC（只逐行）
        attempt("网易", failures) { LyricsBridge.netease(song.id) }?.let { netease ->
            LrcLyricsParser
                .parse(netease.lrc, LyricsSource.NETEASE, netease.translation, netease.roman)
                .onFailure { failures += "网易解析: ${it.message}" }
                .getOrNull()
                ?.takeIf { it.lines.isNotEmpty() }
                ?.let { return remember(song.id, it) }
        }

        // ⑤ lrclib（最后兜底：靠曲目信息模糊查）
        val lrclib = attempt("lrclib", failures) {
            LyricsBridge.lrclib(
                trackName = song.name,
                artistName = song.artists.firstOrNull().orEmpty(),
                albumName = song.album,
                durationMs = song.durationMs,
            )
        }
        if (lrclib != null) {
            val result = if (lrclib.synced.isNotBlank()) {
                LrcLyricsParser.parse(lrclib.synced, LyricsSource.LRCLIB)
            } else {
                LrcLyricsParser.parsePlain(lrclib.plain, LyricsSource.LRCLIB, lrclib.instrumental)
            }
            result
                .onFailure { failures += "lrclib 解析: ${it.message}" }
                .getOrNull()
                ?.takeIf { it.lines.isNotEmpty() || it.instrumental }
                ?.let { return remember(song.id, it) }
        }

        val detail = failures.joinToString("; ").ifEmpty { "四个源都明确没有这首" }
        return Result.failure(IllegalStateException("歌词四级取词均失败：$detail"))
    }

    /** 解析 TTML；解析不出来只记一笔，不算这一级失败。 */
    private fun parseTtml(
        ttml: String,
        source: LyricsSource,
        failures: MutableList<String>,
    ): Lyrics? = TtmlLyricsParser
        .parse(ttml, source)
        .onFailure { failures += "$source 解析: ${it.message}" }
        .getOrNull()
        ?.takeIf { it.lines.isNotEmpty() }

    /**
     * 跑一个取词级别。
     *
     * 返回 null 有两种情形，这里**故意不区分**：这一级没有词（正常回落），
     * 或者这一级请求失败（也往下回落）。失败原因只记进口袋，最后一起报。
     */
    private fun <T> attempt(
        label: String,
        failures: MutableList<String>,
        block: () -> Result<T?>,
    ): T? = block()
        .onFailure { failures += "$label: ${it.message}" }
        .getOrNull()

    private fun remember(songId: Long, lyrics: Lyrics): Result<Lyrics> {
        cache[songId] = lyrics
        store.write(songId, lyrics)
        return Result.success(lyrics)
    }

    /** 丢掉内存与磁盘的歌词快照 —— 取词链的优先级变了（比如刚登上 Apple）。 */
    private fun invalidate() {
        cache.clear()
        store.clear()
    }

    private companion object {
        const val Tag = "LyricsRepository"
    }
}
