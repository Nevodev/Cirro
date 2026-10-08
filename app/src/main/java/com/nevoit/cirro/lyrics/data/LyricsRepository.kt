package com.nevoit.cirro.lyrics.data

import android.util.Log
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.lyrics.model.Lyrics
import com.nevoit.cirro.lyrics.model.LyricsSource
import com.nevoit.cirro.lyrics.parser.LrcLyricsParser
import com.nevoit.cirro.lyrics.parser.TtmlLyricsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 歌词的唯一入口：按歌曲自动走四级取词链，解析成统一模型，并缓存 / 落盘。
 *
 * ## 取词链
 * 1. **Apple Music 官方 TTML**（需 `media-user-token`）—— 拿 Apple id 有两条路：
 *    ①a **自己搜** Apple，①b AMLL 的 `amll:meta`（免费）；
 * 2. **AMLL TTML DB** —— 用网易歌曲 id 直取，**一次请求同时拿到 TTML 和 `appleMusicId`**；
 * 3. **网易 LRC**（只逐行）；
 * 4. **lrclib**。
 *
 * Apple 搜索、AMLL 和网易同时开始。官方命中立即返回；官方未命中时再采用 AMLL、
 * 已在请求中的网易歌词，最后查询 lrclib。AMLL 提供的 Apple id 仍可补充搜索未命中的结果。
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

    private val inFlight = ConcurrentHashMap<Long, Deferred<Result<Lyrics>>>()

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
    suspend fun lyrics(song: Song): Result<Lyrics> {
        cached(song.id)?.let { return Result.success(it) }
        val request = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            cached(song.id)?.let { return@async Result.success(it) }
            store.read(song.id)?.let { snapshot ->
                cache[song.id] = snapshot
                return@async Result.success(snapshot)
            }
            runCatching { resolve(song) }.getOrElse {
                if (it is CancellationException) throw it
                Result.failure(it)
            }
        }
        val existing = inFlight.putIfAbsent(song.id, request)
        if (existing != null) {
            request.cancel()
            return existing.await()
        }
        request.invokeOnCompletion { inFlight.remove(song.id, request) }
        return request.await()
    }

    /**
     * 后台预取（切歌时调）。同一首歌已有请求在跑就直接跳过。
     */
    fun prefetch(song: Song) {
        if (cache.containsKey(song.id) || inFlight.containsKey(song.id)) return
        scope.launch {
            lyrics(song).onFailure { Log.d(Tag, "预取 ${song.id} 未取到：${it.message}") }
        }
    }

    // -----------------------------------------------------------------------
    // 取词链本体
    // -----------------------------------------------------------------------

    private suspend fun resolve(song: Song): Result<Lyrics> {
        val amll = fetchAsync { LyricsBridge.amll(song.id) }
        val netease = fetchAsync { LyricsBridge.netease(song.id) }
        try {
            return resolve(song, amll, netease)
        } finally {
            amll.cancel()
            netease.cancel()
        }
    }

    // JNI 调用无法被协程取消。使用应用作用域，避免官方词已到手还要等其他阻塞调用退出。
    private fun <T> fetchAsync(block: () -> Result<T>): Deferred<Result<T>> =
        scope.async(Dispatchers.IO) {
            runCatching { block() }.getOrElse {
                if (it is CancellationException) throw it
                Result.failure(it)
            }
        }

    private suspend fun resolve(
        song: Song,
        amllRequest: Deferred<Result<AmllTtml?>>,
        neteaseRequest: Deferred<Result<NeteaseLyric?>>,
    ): Result<Lyrics> {
        val failures = mutableListOf<String>()

        // 取词链的轨迹。这条链跨了 JNI 与四个远程源，而调用方只拿到一个 `Result`；
        // 没有这份轨迹，「取不到」到底是没网、没凭据、还是这首歌真没有官方词，无从分辨
        // —— 日志里按 tag `LyricsRepository` 过滤即可。
        val trail = mutableListOf<String>()

        /** 收尾：记轨迹、进缓存、落盘。四个源的命中点都从这儿出去，日志格式才是同一条。 */
        fun deliver(lyrics: Lyrics): Result<Lyrics> {
            Log.d(Tag, "取词链 ${song.id} → ${lyrics.source}（${lyrics.lines.size} 行）｜${trail.joinToString("｜")}")
            cache[song.id] = lyrics
            store.write(song.id, lyrics)
            return Result.success(lyrics)
        }

        // 引擎自检排最前：`.so` 的版本、以及 Apple 凭据在不在原生层 —— 两样都不在 Kotlin
        // 侧，只能问它，而它们共同决定了「到底有没有可能拿到官方词」。`null` = 调用抛了
        // （`UnsatisfiedLinkError` 之类，多半是 .so 没打进包 / 与 Kotlin 侧签名对不上）。
        val configured = runCatching { LyricsBridge.appleConfigured() }.getOrNull()
        trail += "engine=${runCatching { LyricsBridge.version() }.getOrNull() ?: "-"}/apple=$configured"

        val tried = LinkedHashSet<String>()

        suspend fun takeApple(id: String, via: String): Lyrics? {
            if (id.isEmpty() || !tried.add(id)) return null
            val hit = attempt("Apple($id)", failures) { LyricsBridge.apple(id) }
            if (hit == null) {
                trail += "Apple($id)=请求失败"
                return null
            }
            val where = "sf=${hit.storefront.ifEmpty { "-" }}" +
                if (hit.storefrontProbed) "" else "!"
            trail += "Apple($id)=" + if (hit.ttml.isBlank()) {
                "无词(reason=${hit.reason.ifEmpty { "-" }}, $where, $via)"
            } else {
                "命中(${hit.ttml.length}B, $where, $via)"
            }
            if (hit.ttml.isBlank()) return null
            return parseTtml(hit.ttml, LyricsSource.APPLE_MUSIC, failures)
        }

        if (configured != true) {
            trail += "Apple=跳过(凭据未就绪)"
        } else {
            val term = searchTerm(song)
            if (term.isEmpty()) {
                trail += "Apple搜索=跳过(无可搜信息)"
            } else {
                val found = attempt("Apple搜索", failures) { LyricsBridge.appleSearch(term) }
                if (found == null) {
                    trail += "Apple搜索($term)=请求失败"
                } else {
                    val picked = AppleSongMatcher.best(found.songs, song)
                    val where = "sf=${found.storefront.ifEmpty { "-" }}" +
                        if (found.storefrontProbed) "" else "!"
                    trail += "Apple搜索($term)=${found.songs.size}条/$where/" +
                        (picked?.let { "选中${it.id}(${it.name}-${it.artist})" } ?: "无够格候选")
                    picked?.let { takeApple(it.id, via = "via=search") }?.let { return deliver(it) }
                }
            }
        }

        val amll = attempt("AMLL", failures) { amllRequest.await() }
        trail += if (amll == null) {
            "AMLL=未命中"
        } else {
            "AMLL=命中(${amll.ttml.length}B, appleIds=${amll.appleMusicIds})"
        }
        if (configured == true) {
            for (appleId in amll?.appleMusicIds.orEmpty()) {
                takeApple(appleId, via = "via=amll")?.let { return deliver(it) }
            }
        }

        if (amll != null) {
            val parsed = parseTtml(amll.ttml, LyricsSource.AMLL_TTML_DB, failures)
            trail += "AMLL解析=${if (parsed == null) "失败" else "成功"}"
            parsed?.let { return deliver(it) }
        }

        // ④ 网易 LRC（只逐行）
        val netease = attempt("网易", failures) { neteaseRequest.await() }
        if (netease == null) {
            trail += "网易=未命中"
        } else {
            trail += "网易=${if (netease.isEmpty) "空" else "${netease.lrc.length}B"}"
            LrcLyricsParser
                .parse(netease.lrc, LyricsSource.NETEASE, netease.translation, netease.roman)
                .onFailure { failures += "网易解析: ${it.message}" }
                .getOrNull()
                ?.takeIf { it.lines.isNotEmpty() }
                ?.let { return deliver(it) }
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
        if (lrclib == null) {
            trail += "lrclib=未命中"
        } else {
            trail += "lrclib=${if (lrclib.synced.isNotBlank()) "synced" else "plain"}"
            val result = if (lrclib.synced.isNotBlank()) {
                LrcLyricsParser.parse(lrclib.synced, LyricsSource.LRCLIB)
            } else {
                LrcLyricsParser.parsePlain(lrclib.plain, LyricsSource.LRCLIB, lrclib.instrumental)
            }
            result
                .onFailure { failures += "lrclib 解析: ${it.message}" }
                .getOrNull()
                ?.takeIf { it.lines.isNotEmpty() || it.instrumental }
                ?.let { return deliver(it) }
        }

        val reason = failures.joinToString("; ").ifEmpty { "四个源都明确没有这首" }
        Log.d(Tag, "取词链 ${song.id} → 全失败｜${trail.joinToString("｜")}｜$reason")
        return Result.failure(IllegalStateException("取词失败（${trail.joinToString("｜")}）"))
    }

    /**
     * 搜索串：「歌名 主歌手」。
     *
     * 只给这两个词 —— Apple 的 `term` 是全文搜索，关键词给多了反而稀释相关度
     * （实测把全部歌手名拼进去会搜出一堆同专辑的其他歌）。歌名为空直接放弃：
     * 光靠歌手搜必然挑错歌。
     */
    private fun searchTerm(song: Song): String {
        if (song.name.isBlank()) return ""
        return listOf(song.name, song.artists.firstOrNull().orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" ")
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
     * 返回 null 有三种情形，这里**故意不区分**：这一级没有词（正常回落）、这一级请求失败、
     * 或者这一级的桥接调用直接抛了（也往下回落）。失败原因只记进口袋，最后一起报。
     */
    private suspend fun <T> attempt(
        label: String,
        failures: MutableList<String>,
        block: suspend () -> Result<T?>,
    ): T? = runCatching { block().onFailure { failures += "$label: ${it.message}" }.getOrNull() }
        .getOrElse {
            if (it is CancellationException) throw it
            // 🔴 兜 `Error`（`UnsatisfiedLinkError` 之类）：`.so` 没打进包、或它与 Kotlin 侧
            // 的签名对不上时，异常不是 `Result.failure` 而是直接抛 —— 不接住就会从 [resolve]
            // 冒出去，把整条四级链一起带走，退化成「一首歌都取不到」。接住之后最差也只是逐级回落。
            failures += "$label: ${it.javaClass.simpleName}: ${it.message}"
            null
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
