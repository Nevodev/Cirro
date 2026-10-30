package com.nevoit.cirro.lyrics.parser

import com.nevoit.cirro.lyrics.model.LyricMetadata
import java.util.Locale

/**
 * 曲目身份信息 —— 只给歌词清洗判断「这一行是不是标题行」用。
 *
 * 网易偶尔把 `歌名 - 歌手` 当成一行歌词塞在信息块中间（实测《夜曲》三条 credit 之后紧跟
 * `[00:00.000]夜曲 - 周杰伦`），它会把信息块的扫描截断，后面十几条 credit 全都留下来。
 *
 * 🔴 歌名与歌手**都要对得上**，而且必须是「横线两边」的结构（见 [looksLikeTitleLine]）。
 * 只要歌名的话，一首叫《Shape of You》的歌，副歌首句 `I'm in love with the shape of you`
 * 就会连同后面的 credit 一起消失（实测踩过）。
 */
data class LyricTrackRef(
    val title: String,
    val artists: List<String>,
)

/**
 * 歌词信息行（作词 / 作曲 / 编曲 / 混音 / 母带 …）的识别与删除。
 *
 * ## 为什么要删
 * 网易的 `lrc` 里混着制作人员表和版权声明：`[00:00.000] 作词 : 周杰伦` 这种。它们不是歌词，
 * 却照样占一行、参与滚动；而且时间戳全挤在开头，结束时间还会被推导得很难看。
 *
 * ## 判据：位置优先，关键词其次
 * 🔴 **位置锚定是防误伤的主手段，不是关键词。** 实测 280 首真实网易歌词：信息行只成块地出现在
 * **开头**与**结尾**两处（头块 credit 挤在 0~5 秒，尾块人员表 / 版权声明挤在最后 10 秒），
 * 中间正文几乎不出现。所以本类**只在头块和尾块里删**，正文一段无论命中什么一律保留 ——
 * 这一条就挡掉了绝大部分误伤面。
 *
 * 位置之内再过一层单行判据（[classify]）：先是「关键词 + 冒号」的硬命中，
 * 硬命中之外才是版权声明 / 英文 credit / 整行括号 / ISRC 这类弱命中。
 *
 * ## 🔴 实测踩出来的坑（改判据前先看这几条）
 * - **冒号左边未必是个干净的关键词**：`词版权管理方：北京梦织音…`、
 *   `Arrangement / Keyboards / Programming：梁翘柏`。所以只能「关键词**子串**命中 + 含冒号」，
 *   不能要求整行等于关键词。
 * - **块里有无冒号的成员**：`Mastered at Metropolis Mastering (London)`、`Published by …`、
 *   `【未经著作权人许可…】`、`ISRC TW-B67-05-01212`。漏了它们，扫描就在那儿断掉，后面整块漏删。
 * - **纯时间戳、没有文本的行（`[00:09.840]`）要跳过而不是终止扫描**：成都、后来、Shape of You
 *   都是这么被截断的。而且这些行本身**不能删** —— 它们还兼职上一行的结束时间锚点。
 * - **不要给块加「后面必须有大空档」的闸门**：Lemon 的 credit 在 0.000 / 0.212 / 0.424 / 0.636，
 *   第一句歌词 0.851 秒就跟上来了，根本没有空档；加了闸门 Lemon 整块都删不掉。
 * - **不要给块加「密集簇」豁免**：喊麦《惊雷》本身就密集，会被从头犁到中间、删掉 7 行真歌词。
 *
 * ## 召回与准确（280 首真实网易歌词实测）
 * 命中 223 首、删 1205 行（全量的 8.0%）；逐条复核**没有一条真歌词被删**。剩下的漏网基本都是
 * 「块里混进一条既没冒号、也不像版权声明的行」把块截断 —— 属于保守方向的漏。
 *
 * 关键词表移植自 `WXRIW/Lyricify-Lyrics-Helper`（Apache-2.0）的 `InfoLines.TitleLineInfoDict`，
 * 按实测补了缺项（`演奏` 等）；判据本身按本项目「只做头尾块」的口径收紧过。
 */
internal object LyricInfoLines {
    private val QQ_ANNOTATIONS = Regex("""\([^)]*\)|（[^）]*）|\[[^]]*]|【[^】]*】""")
    private val QQ_ARTIST_SEPARATOR = Regex("""\s*/\s*""")

    internal fun qqTrack(metadata: LyricMetadata, fallback: LyricTrackRef?): LyricTrackRef? {
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: fallback?.title ?: return fallback
        val artists = metadata.artist?.takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: fallback?.artists.orEmpty()
        // QQ's display title can omit the version/alias annotations present in its own tags.
        return LyricTrackRef(
            QQ_ANNOTATIONS.replace(title, "").trim(),
            artists.flatMap {
                val name = QQ_ANNOTATIONS.replace(it, "").trim()
                listOf(name) + QQ_ARTIST_SEPARATOR.split(name)
            }.filter { it.isNotBlank() }.distinct(),
        )
    }

    /** 单行判定的结论，也是块扫描的推进依据。 */
    private enum class Hit {
        /** 「关键词 + 冒号」的硬命中。 */
        STRICT,

        /** 版权声明 / 英文 credit / 整行括号之类 —— 只有在头尾块里才敢采信。 */
        LOOSE,
    }

    /**
     * 头 / 尾块的最大跨度（条目数）。
     *
     * 不是为了裁掉长块（正常块远小于这个数），而是防判据在畸形歌词上失控。
     */
    private const val MAX_BLOCK_SPAN = 64

    /**
     * 关键词必须出现在行首这么多字符以内。
     *
     * credit 标签一律领行；行中间命中的多半是歌词（`Lead & Background Vocals` 这种少见的写法
     * 会因此落到弱命中上，一样会被删）。
     */
    private const val KEYWORD_HEAD_CHARS = 12

    /** 整行以冒号收尾、且短于此长度的，判为「说话人标签」（歌词里真有 `歌手名：` 这种写法）。 */
    private const val SPEAKER_LABEL_MAX_CHARS = 20

    /** 版权声明至少要命中这么多个词才算，避免误伤含「许可」二字的普通歌词。 */
    private const val COPYRIGHT_MIN_HITS = 3

    private val FULL_WIDTH_COLON = '：'

    /** 行内逐字时间戳（增强型 LRC）—— 出现即说明这是逐字歌词，绝不动。 */
    private val INLINE_TIMESTAMP = Regex("""<\d{1,3}:\d{1,2}""")

    /** 英文 credit 词。`Recorded at …` / `Engineered by …` / `Mixed by …` 这类整句。 */
    private val ENGLISH_CREDIT = Regex(
        """\b(recorded|mixed|mastered|produced|published|engineered|written|arranged|""" +
            """composed|percussion|programming|vocals)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val ENGLISH_CREDIT_LABEL = Regex(
        """^(?:(?:lyrics|words|music|written|composed|produced|arranged)\s+by|""" +
            """lyricist|composer|producer)\s*:\s*\S""",
        RegexOption.IGNORE_CASE,
    )

    private val QQ_TRANSLATION_NOTICE = Regex(
        """^(?:QQ音乐享有本翻译作品的著作权|以下歌词翻译由.{1,40}提供)[。.!！]?$""",
    )

    private val ISRC = Regex("""^ISRC[\s:：]?""", RegexOption.IGNORE_CASE)

    /** 前缀短横线的说明行，如 `-音名：D`。 */
    private val DASH_PREFIX = Regex("""^[-–—]\s*\S{1,8}[:：]""")

    private val COPYRIGHT_WORDS = listOf(
        "未经", "许可", "不得", "请勿", "版权", "翻唱", "重制", "侵权", "必究",
        "著作权", "翻录", "声明", "保留", "授权",
    )

    /** 信息行的标签词表（子串匹配，忽略大小写）。 */
    private val KEYWORDS = listOf(
        // —— 单字：只有位置判据兜着才敢用（`作词 : …` 里命中的其实是「词」）——
        "音", "声", "词", "曲", "鼓", "笛",
        // —— 中文 ——
        "编曲", "吉他", "吉它", "贝斯", "缩混", "胡琴", "扬琴", "提琴", "录音", "弦乐", "键盘",
        "钢琴", "童声", "助理", "古筝", "琵琶", "笛子", "二胡", "唢呐", "出品", "和声", "和音",
        "编写", "混音", "封面", "营销", "发行", "制作", "监制", "策划", "企划", "推广", "母带",
        "文案", "编辑", "统筹", "总监", "鸣谢", "感谢", "设计", "视觉", "小号", "长号", "管乐",
        "管弦", "工程", "簧管", "巴松", "原唱", "配唱", "伴唱", "轨道", "演唱", "尺八", "乐队",
        "调校", "伴奏", "主唱", "曲绘", "呼麦", "校对", "商务", "合作", "合唱", "指挥", "经纪",
        "戏腔", "团队", "协作", "顾问", "翻译", "摄影", "协力", "艺人", "行销", "媒介", "运营",
        "宣传", "管理", "单位", "支持", "平台", "录混", "宣发", "念白", "厂牌", "分轨", "贴混",
        "古琴", "调教", "题字", "海报", "图绘", "影像", "导演", "造型", "妆发", "执行", "拍摄",
        "服装", "制片", "灯光", "道具", "剪辑", "调色", "合成", "承制", "公司", "单簧管",
        "萨克斯", "打击乐", "合成器", "马头琴", "热瓦普", "葫芦丝", "冬不拉", "工作室", "科布兹",
        "专辑介绍", "巴拉莱卡", "演奏",
        // —— 英文 / 缩写 ——
        "OA", "OP", "OT", "PV", "SP", "A&R", "PGM", "ISRC", "Bass", "Drum", "Pads", "Brass",
        "Cello", "Choir", "Horns", "Mixed", "Mixer", "Piano", "Synth", "Viola", "Vocal", "Winds",
        "Kobza", "Assist", "Violin", "Mixing", "String", "Guitar", "Master", "Chorus", "Record",
        "violins", "Arrange", "Conduct", "Editing", "Hormony", "Ocarina", "Produce", "Strings",
        "Whistle", "Stylist", "Engineer", "Keyboard", "Director", "Harmonica", "Mastering",
        "Recording", "Balalaika", "Percussion", "Programing", "Production", "Programming",
        "Additional programming", "Irish whistle",
    )

    /**
     * 标出该删的行。返回与 [texts] 等长的数组，`true` = 信息行。
     *
     * 🔴 [texts] 必须是**渲染顺序**（按时间排好）的纯文本，空文本的行要原样留在里面 ——
     * 它们参与位置判断，但不参与删除。
     *
     * [track] 给了才认标题行；不给（比如 lrclib 那条路拿不到曲目）只是少一条弱判据，不影响其余。
     */
    fun markInfoLines(
        texts: List<String>,
        track: LyricTrackRef? = null,
        titleIsAnchor: Boolean = false,
    ): BooleanArray {
        val flags = BooleanArray(texts.size)
        if (texts.isEmpty()) return flags

        val head = scan(texts, track, forward = true, titleIsAnchor)
        // 头尾块不许互相跨界：剩下的全给尾块也不够时，尾块直接放弃
        val tail = if (head >= texts.size) {
            0
        } else {
            minOf(scan(texts, track, forward = false, titleIsAnchor), texts.size - head)
        }

        for (index in 0 until head) flags[index] = true
        for (index in texts.size - tail until texts.size) flags[index] = true

        // 🔴 空文本行（只有时间戳）永远保留：它们不是歌词，但会决定上一行的结束时间
        for (index in flags.indices) {
            if (texts[index].isBlank()) flags[index] = false
        }
        return flags
    }

    /** 从一端向内扫，返回这一段信息块的跨度（条目数）。 */
    private fun scan(texts: List<String>, track: LyricTrackRef?, forward: Boolean, titleIsAnchor: Boolean): Int {
        var span = 0
        var anchored = false
        var index = if (forward) 0 else texts.lastIndex
        val step = if (forward) 1 else -1

        while (index in texts.indices && span < MAX_BLOCK_SPAN) {
            val text = texts[index]
            if (text.isBlank()) {
                // 只有时间戳的行：不计数、也不终止 —— 但它本身不算块成员
                span++
                index += step
                continue
            }
            val hit = classify(text, track, titleIsAnchor)
            if (hit == null) break
            if (hit == Hit.STRICT) anchored = true
            span++
            index += step
        }

        // 🔴 块里一条硬命中都没有 → 整块作废。防的是歌词首句恰好含英文 credit 词
        // （`Produced by the pain…`）时，光靠弱命中就把第一行吃掉。
        return if (anchored) span else 0
    }

    /** 单行判定。返回 null = 不是信息行。 */
    private fun classify(text: String, track: LyricTrackRef?, titleIsAnchor: Boolean): Hit? {
        val line = text.replace(FULL_WIDTH_COLON, ':').trim()
        if (line.isEmpty()) return null

        if (INLINE_TIMESTAMP.containsMatchIn(line)) return null
        if (line.endsWith(':') && line.length < SPEAKER_LABEL_MAX_CHARS) return null

        if (hasKeywordAndColon(line)) return Hit.STRICT
        if (ENGLISH_CREDIT_LABEL.containsMatchIn(line)) return Hit.STRICT
        // QQ emits these as timed translation rows, often without an adjacent personnel block.
        if (titleIsAnchor && QQ_TRANSLATION_NOTICE.matches(line)) return Hit.STRICT

        if (copyrightHits(line) >= COPYRIGHT_MIN_HITS) return Hit.LOOSE
        if (ENGLISH_CREDIT.containsMatchIn(line)) return Hit.LOOSE
        if (line.startsWith('【') && line.endsWith('】')) return Hit.LOOSE
        if (line.startsWith('（') && line.endsWith('）')) return Hit.LOOSE
        if (ISRC.containsMatchIn(line)) return Hit.LOOSE
        if (DASH_PREFIX.containsMatchIn(line)) return Hit.LOOSE
        // A matching QQ title + artist identifies its generated header even when all credits
        // are English and therefore lack the keyword/colon anchor used by ordinary LRC.
        if (looksLikeTitleLine(line, track)) return if (titleIsAnchor) Hit.STRICT else Hit.LOOSE
        // QQ also emits bare song titles with empty metadata tags. Only pass through a
        // matching title when another credit anchors the block, so sung titles stay intact.
        if (titleIsAnchor && track != null && normalize(line) == normalize(track.title)) return Hit.LOOSE

        return null
    }

    /**
     * 「关键词 + 冒号」。两样缺一不可 —— 光有冒号（歌词里的冒号不少）或光有关键词
     * （`Mastered at …` 这类英文行反而没有）都不算。
     */
    private fun hasKeywordAndColon(line: String): Boolean {
        if (':' !in line) return false
        val head = line.take(KEYWORD_HEAD_CHARS).lowercase(Locale.ROOT)
        return KEYWORDS.any { keyword ->
            if (keyword.length <= 3 && keyword.all { it.code < 128 }) {
                // 🔴 两三个字母的缩写（OA / OP / SP）只能要求「领行且后面不是字母」：
                // 子串匹配会让 `road:` 这种歌词命中 `OA`。
                startsWithCode(line, keyword)
            } else {
                keyword.lowercase(Locale.ROOT) in head
            }
        }
    }

    /** 行首就是该缩写，且紧跟的字符不是字母。 */
    private fun startsWithCode(line: String, code: String): Boolean {
        if (!line.startsWith(code, ignoreCase = true)) return false
        val next = line.getOrNull(code.length) ?: return true
        return !next.isLetter()
    }

    private fun copyrightHits(line: String): Int = COPYRIGHT_WORDS.count { it in line }

    /** 标题行的分隔符：`歌名 - 歌手`、`歌名 – 歌手`。 */
    private val TITLE_SEPARATOR = Regex("""\s*[-–—]\s*""")

    /**
     * 标题行：`歌名 - 歌手` 这种。见 [LyricTrackRef] 上关于「为什么歌名与歌手都要对得上」的注释。
     *
     * 🔴 必须真的是「一根横线两边」的 `X - Y`，且歌名对左边、歌手对右边。
     * 只判「行里同时出现歌名和歌手」是不够的 —— 实测踩过：《Shape of You》的副歌首句
     * `I'm in love with the shape of you` 含歌名，一旦行里再凑巧撞上歌手名，整句歌词就没了。
     */
    private fun looksLikeTitleLine(line: String, track: LyricTrackRef?): Boolean {
        if (track == null) return false
        val title = normalize(track.title)
        if (title.isEmpty()) return false

        val parts = line.split(TITLE_SEPARATOR, limit = 2)
        if (parts.size < 2) return false
        if (title !in normalize(parts[0])) return false

        val right = normalize(parts[1])
        return track.artists.any { artist ->
            val name = normalize(artist)
            name.isNotEmpty() && name in right
        }
    }

    /** 只去空白 + 忽略大小写：`晴天 (原唱 周杰伦)` 与 `晴天-周杰伦(Jay Chou)` 不该因为空格判不中。 */
    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).filter { !it.isWhitespace() }
}
