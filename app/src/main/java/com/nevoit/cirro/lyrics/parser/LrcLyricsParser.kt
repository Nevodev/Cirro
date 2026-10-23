package com.nevoit.cirro.lyrics.parser

import com.nevoit.cirro.lyrics.model.LyricLine
import com.nevoit.cirro.lyrics.model.LyricMetadata
import com.nevoit.cirro.lyrics.model.LyricSyllable
import com.nevoit.cirro.lyrics.model.LyricTranslatedText
import com.nevoit.cirro.lyrics.model.Lyrics
import com.nevoit.cirro.lyrics.model.LyricsSource
import com.nevoit.cirro.lyrics.model.SyncLevel
import kotlin.math.abs

/**
 * LRC 解析器 —— **一套覆盖网易云 LRC 与 lrclib**。
 *
 * 网易那边用户明确要**只要逐行**（不要 yrc 逐字），所以主路径就是普通 `[mm:ss.xx]` 行级歌词；
 * 翻译（`tlyric`）与罗马音（`romalrc`）是**另给的字符串**，按时间戳并回主行。
 *
 * 支持：
 * - `[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss:xx]`，一行**多个**时间戳（`[t1][t2]词`）；
 * - `[ti:]/[ar:]/[al:]/[by:]/[offset:]` 等标签（`offset` 会真的移动时间轴）；
 * - **只有时间戳没有文本的行**：不产出歌词行，但会作为上一行的结束时间切分点；
 * - 增强型 LRC 的行内逐字 `<mm:ss.xx>`（lrclib 有时会给）：解析成音节，同时从显示文本里剥掉；
 * - 整行被括号包住 → 背景人声（[LyricLine.isBackground]）；
 * - 结束时间统一在这里推导完（末行封顶），模型里不放哨兵值。
 *
 * 🔴 网易那份 `lrc` 前面（有时还有后面）会混进制作人员表与版权声明，`[00:00.000] 作词 : XXX`
 * 这种，在 [readEntries] 之后、[buildLines] 之前由 [LyricInfoLines] 剔掉 —— 判据与实测数据
 * 都在那个类里。
 */
object LrcLyricsParser {

    /** 元数据标签：键必须含字母，避免把 `[00:12.34]` 当标签。 */
    private val TAG_PATTERN = Regex("""^\[([A-Za-z_]+):(.*)]$""")

    /** 行首时间戳。 */
    private val TIMESTAMP_PATTERN = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** 增强型 LRC 的行内逐字时间戳。 */
    private val INLINE_PATTERN = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    /** 翻译 / 罗马音按时间戳并入主行时允许的偏差。 */
    private const val MERGE_TOLERANCE_MS = 400L

    /**
     * 解析带时间轴的 LRC。
     *
     * [translation] / [roman] 分别是网易 `tlyric` / `romalrc` 那两份独立文本，可为空。
     * [track] 只用来认「歌名 - 歌手」标题行，拿不到就传 null（见 [LyricTrackRef]）。
     */
    fun parse(
        lrc: String,
        source: LyricsSource,
        translation: String? = null,
        roman: String? = null,
        track: LyricTrackRef? = null,
    ): Result<Lyrics> = runCatching {
        val parsed = readEntries(lrc).withoutInfoLines(track)
        var lines = buildLines(parsed.entries)

        translation?.takeIf { it.isNotBlank() }?.let {
            lines = mergeByTime(lines, readEntries(it).withoutInfoLines(track))
        }
        roman?.takeIf { it.isNotBlank() }?.let { romanEntries ->
            // 罗马音塞进 romanizations 而不是 translations
            lines = mergeByTime(
                lines,
                readEntries(romanEntries).withoutInfoLines(track),
                intoRomanization = true,
            )
        }

        Lyrics(
            source = source,
            lines = lines,
            syncLevel = syncLevelOf(lines),
            metadata = metadataOf(parsed.tags),
        )
    }

    /**
     * 完全没有时间轴的纯文本（lrclib 的 `plainLyrics`）。
     *
     * 一行一条，时间字段保持 0 —— [SyncLevel.PLAIN] 下渲染层不用时间。
     */
    fun parsePlain(
        text: String,
        source: LyricsSource,
        instrumental: Boolean = false,
        track: LyricTrackRef? = null,
    ): Result<Lyrics> = runCatching {
        // 没有时间轴也照样有信息块（lrclib 的纯文本常带一段 `作词：…`），位置判据一样能用
        val raw = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val flags = LyricInfoLines.markInfoLines(raw, track)

        val lines = raw
            .filterIndexed { index, _ -> !flags[index] }
            .map { LyricLine(startMs = 0L, endMs = 0L, text = it) }

        Lyrics(
            source = source,
            lines = lines,
            syncLevel = SyncLevel.PLAIN,
            instrumental = instrumental,
        )
    }

    // -----------------------------------------------------------------------
    // 信息行清洗
    // -----------------------------------------------------------------------

    /**
     * 剔掉信息行（作词 / 作曲 / 编曲 / 混音 …）。判据见 [LyricInfoLines]。
     *
     * 🔴 必须在 [readEntries] 之后、[buildLines] 之前做 —— 被删的行会参与结束时间推导，
     * 而网易的 credit 常常连排同一个时间戳（`[00:00.000]` 四条），首行会因此拿到整首歌的时长。
     *
     * 翻译 / 罗马音也各过一遍：它们同样是 LRC 文本、同样可能带着那几行 credit；不过这一遍
     * 只影响「翻译行会不会被并到主行上」（[mergeByTime] 按时间戳找宿主），去掉是安全的。
     */
    private fun Parsed.withoutInfoLines(track: LyricTrackRef?): Parsed {
        if (entries.isEmpty()) return this
        val flags = LyricInfoLines.markInfoLines(entries.map { it.text }, track)
        if (flags.none { it }) return this
        return Parsed(entries.filterIndexed { index, _ -> !flags[index] }, tags)
    }

    // -----------------------------------------------------------------------
    // 读入
    // -----------------------------------------------------------------------

    /** 一条原始记录（时间已含 offset）。 */
    private class Entry(val startMs: Long, val text: String)

    private class Parsed(
        val entries: List<Entry>,
        val tags: Map<String, String>,
    )

    private fun readEntries(text: String): Parsed {
        val entries = mutableListOf<Entry>()
        val tags = linkedMapOf<String, String>()
        var offsetMs = 0L

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.isEmpty()) continue

            // 1) 元数据标签
            val tagMatch = TAG_PATTERN.matchEntire(line)
            if (tagMatch != null) {
                val key = tagMatch.groupValues[1].lowercase()
                val value = tagMatch.groupValues[2].trim()
                if (key == "offset") {
                    offsetMs = value.toLongOrNull() ?: 0L
                } else {
                    tags[key] = value
                }
                continue
            }

            // 2) 行首的连续时间戳
            var cursor = 0
            val times = mutableListOf<Long>()
            for (match in TIMESTAMP_PATTERN.findAll(line)) {
                if (match.range.first != cursor) break
                cursor = match.range.last + 1
                times += parseStamp(match)
            }
            if (times.isEmpty()) continue

            // 时间戳后面的正文两端去空白。🔴 必须**在拆音节之前**去掉：行内逐字那条路的
            // `plain` 与 `syllables` 都是从这串文本派生的，在这儿裁，两者长度才永远对得上
            // （`buildSweeps` 靠这个长度相等来决定这一行能不能逐字擦除）。
            val content = line.substring(cursor).trim()
            times.forEach { entries += Entry(it + offsetMs, content) }
        }

        return Parsed(entries.sortedBy { it.startMs }, tags)
    }

    private fun parseStamp(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fraction = fractionToMillis(match.groupValues[3])
        return (minutes * 60 + seconds) * 1000 + fraction
    }

    private fun parseInlineStamp(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fraction = fractionToMillis(match.groupValues[3])
        return (minutes * 60 + seconds) * 1000 + fraction
    }

    /** 小数部分：两位是百分秒、三位是毫秒。 */
    private fun fractionToMillis(raw: String): Long = when (raw.length) {
        0 -> 0L
        1 -> (raw.toLongOrNull() ?: 0L) * 100
        2 -> (raw.toLongOrNull() ?: 0L) * 10
        else -> raw.take(3).toLongOrNull() ?: 0L
    }

    // -----------------------------------------------------------------------
    // 组装
    // -----------------------------------------------------------------------

    private fun buildLines(entries: List<Entry>): List<LyricLine> {
        // 含「只有时间戳」的行：它们不产出歌词，但决定上一行的结束时间，
        // 所以先一起排进时间轴，等结束时间推导完再滤掉。
        val drafts = entries.map { entry ->
            val (plainText, syllables) = splitInlineSyllables(entry.text)
            LyricLine(
                startMs = entry.startMs,
                endMs = 0L,
                text = plainText,
                syllables = syllables,
                isBackground = ParserSupport.isBracketedBackground(plainText),
            )
        }

        return ParserSupport.resolveEndTimes(drafts)
            .filter { it.text.isNotBlank() }
            .map { line -> line.copy(syllables = fixSyllableEnds(line)) }
    }

    /** 行内逐字：从文本里剥掉 `<mm:ss.xx>`，同时产出音节。 */
    private fun splitInlineSyllables(text: String): Pair<String, List<LyricSyllable>> {
        val matches = INLINE_PATTERN.findAll(text).toList()
        if (matches.isEmpty()) return text to emptyList()

        val plain = StringBuilder()
        val leading = text.substring(0, matches.first().range.first)
        if (leading.isNotEmpty()) plain.append(leading)

        val syllables = mutableListOf<LyricSyllable>()
        matches.forEachIndexed { index, match ->
            val segmentStart = match.range.last + 1
            val segmentEnd = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val segment = text.substring(segmentStart, segmentEnd)
            if (segment.isEmpty()) return@forEachIndexed
            plain.append(segment)
            syllables += LyricSyllable(
                text = segment,
                startMs = parseInlineStamp(match),
                endMs = 0L,
            )
        }

        return plain.toString() to syllables
    }

    /**
     * 补足行内逐字的结束时间：取下一个音节的起点，最后一个用整行结束时间。
     *
     * 行内时间戳只标「这一段从哪开始」，结束时间本来就该这样推。
     */
    private fun fixSyllableEnds(line: LyricLine): List<LyricSyllable> {
        val syllables = line.syllables
        if (syllables.isEmpty()) return syllables
        return syllables.mapIndexed { index, syllable ->
            if (syllable.endMs > syllable.startMs) {
                syllable
            } else {
                val nextStart = syllables.getOrNull(index + 1)?.startMs
                syllable.copy(endMs = nextStart?.takeIf { it > syllable.startMs } ?: line.endMs)
            }
        }
    }

    private fun syncLevelOf(lines: List<LyricLine>): SyncLevel =
        if (lines.any { it.syllables.isNotEmpty() }) SyncLevel.SYLLABLE else SyncLevel.LINE

    private fun metadataOf(tags: Map<String, String>): LyricMetadata = LyricMetadata(
        title = tags["ti"],
        artist = tags["ar"],
        album = tags["al"],
        extra = buildMap {
            tags["by"]?.let { put("by", listOf(it)) }
            tags["length"]?.let { put("length", listOf(it)) }
        },
    )

    /**
     * 把翻译 / 罗马音按时间戳并进主行。
     *
     * 优先精确同时间戳；找不到时允许 [MERGE_TOLERANCE_MS] 以内的最近一条 —— 网易的
     * `tlyric` 偶尔比主行晚几十毫秒，卡死精确匹配会丢翻译。
     */
    private fun mergeByTime(
        lines: List<LyricLine>,
        other: Parsed,
        intoRomanization: Boolean = false,
    ): List<LyricLine> {
        val table = other.entries
            .filter { it.text.isNotBlank() }
            .associate { it.startMs to it.text }
        if (table.isEmpty()) return lines

        val times = table.keys.sorted()
        return lines.map { line ->
            val text = table[line.startMs] ?: nearest(times, line.startMs)?.let { table[it] }
            if (text.isNullOrBlank()) return@map line

            val extra = LyricTranslatedText(text)
            if (intoRomanization) {
                line.copy(romanizations = line.romanizations + extra)
            } else {
                line.copy(translations = line.translations + extra)
            }
        }
    }

    private fun nearest(times: List<Long>, target: Long): Long? {
        if (times.isEmpty()) return null
        val hit = times.binarySearch(target)
        if (hit >= 0) return times[hit]
        val insertion = -hit - 1
        return listOfNotNull(times.getOrNull(insertion - 1), times.getOrNull(insertion))
            .minByOrNull { abs(it - target) }
            ?.takeIf { abs(it - target) <= MERGE_TOLERANCE_MS }
    }
}
