package com.nevoit.cirro.lyrics.data

import com.nevoit.cirro.data.model.Song
import java.text.Normalizer
import kotlin.math.abs

/**
 * 从 Apple 搜索的候选里挑出「就是这首歌」的那一条。
 *
 * ## 为什么要单独一个匹配器
 * 搜索是**模糊**的：拿「歌名 歌手」去查，回来的 12 条里往往有同一首歌的十几个版本
 * （录音室 / Live / 翻唱 / 钢琴版 / 精选集重发）。挑错一条 = 用户看到**另一首歌的歌词**，
 * 比取不到还糟。所以宁可挑不出（返回 null，往下回落网易），也不硬凑。
 *
 * ## 打分依据（实测 apple_search 探针得出）
 * - **时长是主判据**：同一首歌的不同版本，时长差是最能分辨的信号（Live 版能差几十秒）；
 * - **`hasLyrics` 只加权、不做门槛**：Apple 标称有歌词 ≠ 有逐字（`syllable-lyrics`），
 *   而标称没有的基本可以排除，所以当加权项用；
 * - 歌名 / 歌手做**归一化**后再比 —— 不归一化根本对不上：Apple 的标题普遍带出处后缀
 *   （`孤勇者 (《英雄联盟:双城之战》动画剧集中文主题曲)`），歌手名还会**转简体**
 *   （我们传 `米津玄師`、它回 `米津玄师`）。
 */
internal object AppleSongMatcher {
    private val recordingVersions = listOf(
        Regex("""(?i)\blive\b|\bunplugged\b|现场|演唱会"""),
        Regex("""(?i)\bremix\b|\bmixed\b|混音"""),
        Regex("""(?i)\binstrumental\b|伴奏|纯音乐"""),
        Regex("""(?i)\bacoustic\b|不插电"""),
        Regex("""(?i)\bcover\b|翻唱"""),
        Regex("""(?i)\bpiano\s*(?:&|and)\s*vocal\b|钢琴版"""),
    )

    /**
     * 接受一条候选所需的最低总分。
     *
     * 标定：歌名完全相等(100) + 歌手完全相等(60) + 时长差 ≤1s(70) = 230，是「教科书式命中」；
     * 只有时长对得上（100 上下）不该算数。所以门槛卡在「歌名对上 + 时长大致对上」这一档。
     */
    private const val AcceptScore = 110

    /** 时长差超过这个秒数就不再考虑 —— 同一首歌的版本不会有这么大出入。 */
    private const val MaxDurationDeltaSec = 15L

    /**
     * 挑出最像 [song] 的候选；没有够格的返回 `null`。
     *
     * [candidates] 按 Apple 的相关度排序，同分时**靠前的赢**，
     * 相当于把 Apple 自己的排序当平局决胜。
     */
    fun best(candidates: List<AppleSongCandidate>, song: Song): AppleSongCandidate? =
        ranked(candidates, song).firstOrNull()

    fun ranked(candidates: List<AppleSongCandidate>, song: Song): List<AppleSongCandidate> {
        val wantName = normalize(song.name)
        val wantArtists = song.artists.map(::normalize).filter { it.isNotEmpty() }
        val wantDurationSec = song.durationMs.takeIf { it > 0 }?.let { it / 1000 }

        // 没有歌名就无从匹配（靠歌手碰运气更容易挑错歌）
        if (wantName.isEmpty() || wantArtists.isEmpty()) return emptyList()

        return candidates.mapNotNull { candidate ->
            if (candidate.id.isEmpty()) return@mapNotNull null
            if (recordingVersions.any {
                it.containsMatchIn("${song.name} ${song.album}") !=
                    it.containsMatchIn("${candidate.name} ${candidate.album}")
            }) return@mapNotNull null
            val score = score(candidate, wantName, wantArtists, wantDurationSec)
            if (score >= AcceptScore) candidate to score else null
        }.sortedByDescending { it.second }.map { it.first }.distinctBy { it.id }
    }

    private fun score(
        candidate: AppleSongCandidate,
        wantName: String,
        wantArtists: List<String>,
        wantDurationSec: Long?,
    ): Int {
        var score = 0

        val titleScore = textScore(normalize(candidate.name), wantName, exact = 100, contains = 60, overlap = 40)
        if (titleScore == 0) return 0
        score += titleScore

        // 歌手：本地可能多个；跟**最像的那个**比就够（Apple 的 artistName 是拼接串）
        val artist = normalize(candidate.artist)
        val artistScore = wantArtists.maxOfOrNull { want ->
            textScore(artist, want, exact = 60, contains = 40, overlap = 25)
        } ?: 0
        if (artistScore == 0) return 0
        score += artistScore

        if (wantDurationSec != null && candidate.durationMs > 0) {
            val delta = abs(candidate.durationMs / 1000 - wantDurationSec)
            if (delta > MaxDurationDeltaSec) return 0
            score += when {
                delta <= 1 -> 70
                delta <= 3 -> 50
                delta <= 5 -> 30
                delta <= 10 -> 10
                else -> -30
            }
        }

        if (candidate.hasLyrics) score += 15

        return score
    }

    /**
     * 文本相似度：完全相等 > 一方包含另一方 > 字符集重叠。
     *
     * 最后那档是为**繁简 / 异体字**准备的：`米津玄師` 与 Apple 回的 `米津玄师` 只差一字，
     * 没有转换表就得漏。看字符集重叠率便宜且够用。
     */
    private fun textScore(actual: String, want: String, exact: Int, contains: Int, overlap: Int): Int {
        if (actual.isEmpty() || want.isEmpty()) return 0
        if (actual == want) return exact
        if (actual.contains(want) || want.contains(actual)) return contains
        // Latin character overlap makes unrelated titles such as Needs and Nobody Wanna
        // Dance Anymore appear similar. This fallback is only for CJK spelling variants.
        return if (actual.any(::isCjk) && want.any(::isCjk) &&
            overlapRatio(actual, want) >= 0.7f && overlapRatio(want, actual) >= 0.7f
        ) overlap else 0
    }

    private fun isCjk(char: Char): Boolean = char in '\u3400'..'\u9fff'

    /** [a] 的字符有多少比例出现在 [b] 里（按 [b] 去重后的字符数算分母）。 */
    private fun overlapRatio(a: String, b: String): Float {
        val unique = b.toSet()
        if (unique.isEmpty()) return 0f
        val hit = a.toSet().count { it in unique }
        return hit.toFloat() / unique.size
    }

    /**
     * 归一化：去括号内容 → NFKC（全角转半角、兼容字符归一）→ 小写 → 只留字母数字与汉字。
     *
     * 🔴 **去括号是这一步的重点**：Apple 的标题几乎都带版本/出处后缀，不剥掉就跟
     * 「孤勇者」对不上。NFKC 顺手把全角括号、罗马数字之类拉平。
     */
    private fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val folded = Normalizer.normalize(stripBrackets(raw), Normalizer.Form.NFKC).lowercase()
        return folded.filter { it.isLetterOrDigit() }
    }

    /**
     * 去掉成对括号（中英文方括号圆括号）及其内容。
     *
     * 不做嵌套处理 —— 歌词元数据里没有嵌套括号。落单的右括号按普通字符保留，
     * 免得把 `Beyond 03 - Since 1983` 这种标题切坏。
     */
    private fun stripBrackets(raw: String): String {
        val out = StringBuilder(raw.length)
        var depth = 0
        for (c in raw) {
            when (c) {
                '(', '（', '[', '【' -> depth++
                ')', '）', ']', '】' -> if (depth > 0) depth--
                else -> if (depth == 0) out.append(c)
            }
        }
        return out.toString()
    }
}
