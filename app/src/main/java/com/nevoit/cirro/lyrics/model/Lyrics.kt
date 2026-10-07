package com.nevoit.cirro.lyrics.model

import kotlinx.serialization.Serializable

/**
 * 歌词来源，按取词优先级排列（越小越优先）。
 *
 * `durationMs` 之类字段的来源展示、调试日志都靠它。
 */
enum class LyricsSource {
    /** Apple Music 官方 TTML（需 cookie）。 */
    APPLE_MUSIC,

    /** Steve-xmh 的 AMLL TTML DB。 */
    AMLL_TTML_DB,

    /** 网易云 LRC（只取逐行，不用逐字 yrc）。 */
    NETEASE,

    /** lrclib。 */
    LRCLIB,
}

/**
 * 同步粒度。
 *
 * 由解析器判定后写入，渲染层直接读，不必自己猜有没有逐字时间。
 */
enum class SyncLevel {
    /** 逐字同步：行内每个 [LyricSyllable] 有各自的时间。 */
    SYLLABLE,

    /** 逐行同步：只有整行一个时间段，[LyricLine.syllables] 为空。 */
    LINE,

    /** 纯文本：完全没有时间（lrclib 的 `plainLyrics`）。 */
    PLAIN,
}

/**
 * 一个音节（字 / 词）及其自身时间。
 *
 * 有逐字时间时 [startMs] / [endMs] 才有意义；只有行级同步的行不会产出音节。
 */
@Serializable
data class LyricSyllable(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** 逐字注音（AMLL / Apple `tts:ruby`），无则为空。 */
    val ruby: List<LyricRuby> = emptyList(),
    /** 是否为不雅用语（AMLL `amll:obscene`），用于「干净版」过滤。 */
    val obscene: Boolean = false,
)

/**
 * 注音片段（假名 / 罗马音标注），挂在某个 [LyricSyllable] 上。
 */
@Serializable
data class LyricRuby(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * 带语言标记的附属文本（翻译 / 音译）。
 *
 * [language] 为 BCP-47 标签，如 `"zh-Hans"` / `"ja-Latn"`；来源不给语言时留空。
 */
@Serializable
data class LyricTranslatedText(
    val text: String,
    val language: String? = null,
)

/**
 * 背景人声 / 和声，挂在所属主行上。
 *
 * 对应 Apple / AMLL TTML 里 `<p>` 内的 `<span ttm:role="x-bg">`，时间区间可以
 * 与主行不同（有时比主行更早进入）。
 */
@Serializable
data class LyricBackground(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val syllables: List<LyricSyllable> = emptyList(),
    val translations: List<LyricTranslatedText> = emptyList(),
    val romanizations: List<LyricTranslatedText> = emptyList(),
)

/**
 * 一行歌词。
 *
 * [text] 是权威的显示文本：无逐字时就是原文，有逐字时应等于 [syllables] 拼接的结果
 * （解析器保证一致，这里不做校验）。
 */
@Serializable
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** 逐字音节；空 = 行级同步（整行一个时间段，用 [startMs] / [endMs]）。 */
    val syllables: List<LyricSyllable> = emptyList(),
    val translations: List<LyricTranslatedText> = emptyList(),
    val romanizations: List<LyricTranslatedText> = emptyList(),
    val background: LyricBackground? = null,
    /** 对唱行（Apple `ttm:agent` person 交替成对侧 / AMLL `isDuet`）。 */
    val isDuet: Boolean = false,
    /** 该行本身是被括号包裹的背景人声（LRC 侧靠整行括号识别）。 */
    val isBackground: Boolean = false,
)

/**
 * 曲目元数据。
 *
 * 强字段来自各来源的通用部分；[platformIds] / [extra] 收纳来源特有的、暂时没人消费的标签，
 * 保留下来方便以后做多源匹配与合并，不必为此改模型。
 */
@Serializable
data class LyricMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val isrc: String? = null,
    val durationMs: Long? = null,
    val language: String? = null,
    val songwriters: List<String> = emptyList(),
    /** 各平台 id（AMLL `amll:meta` 的 `ncmMusicId` / `appleMusicId` 等）。 */
    val platformIds: Map<String, List<String>> = emptyMap(),
    /** 未归类的原始标签（LRC `[ti:]` / `[by:]`、AMLL rawProperties）。 */
    val extra: Map<String, List<String>> = emptyMap(),
)

/**
 * 一首歌解析后的完整歌词。
 *
 * 这是所有来源（Apple / AMLL TTML、网易 LRC、lrclib）统一收敛到的内部格式，
 * 渲染层只认它。时间一律毫秒 [Long] —— 与歌曲 id 同一条规矩，永不经过 Int。
 *
 * 结束时间在解析阶段就推导好（末行按下一行起点或 [MAX_TIMESTAMP_MS] 封顶），
 * 模型里不出现 `-1` 这类哨兵值。
 */
@Serializable
data class Lyrics(
    val source: LyricsSource,
    val lines: List<LyricLine>,
    val syncLevel: SyncLevel,
    val metadata: LyricMetadata = LyricMetadata(),
    /** 纯音乐 / 无人声歌词（lrclib 的 `instrumental`）。 */
    val instrumental: Boolean = false,
) {
    companion object {
        /** 时间戳封顶（`999:59.999`），推导末行结束时间时兜底用。 */
        const val MAX_TIMESTAMP_MS: Long = 999L * 60_000 + 59_000 + 999
    }
}
