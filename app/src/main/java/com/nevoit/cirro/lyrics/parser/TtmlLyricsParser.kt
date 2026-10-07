package com.nevoit.cirro.lyrics.parser

import android.util.Xml
import com.nevoit.cirro.lyrics.model.LyricBackground
import com.nevoit.cirro.lyrics.model.LyricLine
import com.nevoit.cirro.lyrics.model.LyricMetadata
import com.nevoit.cirro.lyrics.model.LyricRuby
import com.nevoit.cirro.lyrics.model.LyricSyllable
import com.nevoit.cirro.lyrics.model.LyricTranslatedText
import com.nevoit.cirro.lyrics.model.Lyrics
import com.nevoit.cirro.lyrics.model.LyricsSource
import com.nevoit.cirro.lyrics.model.SyncLevel
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * TTML 解析器 —— **一套覆盖 Apple Music 官方与 AMLL TTML DB**。
 *
 * 两者是同一套 schema，AMLL 只是在自己命名空间（`amll:`）上加了扩展字段，所以解析逻辑必须
 * 共用；差别只在元数据来源与 apple 专有的 `translations/transliterations` 侧车结构，两者
 * 这里都认。
 *
 * 实现严格对齐 AMLL 官方 `packages/ttml` 的解析语义（2026-10-07 版）：
 * - 时间：`begin` / `end`，支持 `HH:MM:SS.mmm` / `MM:SS.mmm` / `SS.mmm` / `12.345s`；
 * - 行 `<p itunes:key ttm:agent>`，逐字是行内 `<span begin end>`；
 * - 角色 `<span ttm:role="x-bg|x-translation|x-roman">`；
 * - 注音 `<span tts:ruby="container">` 包 `base` + `textContainer/text`；
 * - 背景人声去外层括号；对唱按 `ttm:agent` 的 person/group 交替状态机判定；
 * - 翻译/音译两种写法：行内 `x-translation` / `x-roman`，以及 head 里
 *   `iTunesMetadata/translations|transliterations/.../text[for=行 id]` 的侧车表。
 *
 * 与 AMLL 的两处**有意偏离**：
 * 1. `<p>` 没有 `itunes:key` 时**不丢弃该行**（AMLL 会丢弃）—— 用行号造一个合成 id，
 *    宁可少一份侧车翻译也不整行消失；
 * 2. 只保留模型里定义过的扩展（`ruby` / `obscene`），`amll:empty-beat` 解析后丢掉。
 *
 * 输出统一落到 [Lyrics]；行文本 [LyricLine.text] 与逐字拼接保持等价（尾随空格折进音节文本）。
 */
object TtmlLyricsParser {

    private const val NS_TTM = "http://www.w3.org/ns/ttml#metadata"
    private const val NS_ITUNES = "http://music.apple.com/lyric-ttml-internal"
    private const val NS_AMLL = "http://www.example.com/ns/amll"
    private const val NS_XML = "http://www.w3.org/XML/1998/namespace"
    private const val NS_TTS = "http://www.w3.org/ns/ttml#styling"

    private const val ROLE_BG = "x-bg"
    private const val ROLE_TRANSLATION = "x-translation"
    private const val ROLE_ROMAN = "x-roman"

    private const val AGENT_DEFAULT = "v1"

    /** 多余空白折叠成单个空格（含换行 —— TTML 里标签间的换行是排版，不是歌词）。 */
    private val MULTI_SPACE = Regex("\\s+")

    /** 钟表时间：`MM:SS.mmm` / `HH:MM:SS.mmm` / `SS.mmm`。 */
    private val TIME_PATTERN = Regex("""^(?:(?:(\d+):)?(\d+):)?(\d+(?:\.\d+)?)$""")

    /**
     * TTML 的 **offset-time**：数字 + 单位（`9.955s` / `500ms` / `1.5m` / `1h`）。
     *
     * `ms` 必须排在 `s` 前面 —— 正则交替是按顺序试的，先命中 `s` 会把 `"500ms"`
     * 切成 `"500m"` + 尾巴，于是整个时间解析失败。
     * `f`（frames）/ `t`（ticks）要靠 `tts:frameRate` / `ttp:tickRate` 换算，这里不认。
     */
    private val OFFSET_PATTERN = Regex("""^(\d+(?:\.\d+)?)(ms|s|m|h)$""")

    /**
     * 解析一段 TTML。
     *
     * [source] 只用于标注来源（Apple 官方 / AMLL DB），解析逻辑完全一致。
     */
    fun parse(ttml: String, source: LyricsSource): Result<Lyrics> = runCatching {
        val root = XmlTree.parse(ttml)
        val head = root.firstChildElement("head")
        val meta = MetaCollector()
        val sidecar = mutableMapOf<String, Sidecar>()
        if (head != null) {
            meta.collect(head)
            collectSidecar(head, sidecar)
        }
        meta.language = root.attr(NS_XML, "lang") ?: meta.language

        val entries = collectLineEntries(root)
        val lines = assembleLines(entries, meta, sidecar)

        val hasSyllables = lines.any { line ->
            line.syllables.size > 1 || (line.background?.syllables?.size ?: 0) > 1
        }

        Lyrics(
            source = source,
            lines = ParserSupport.resolveEndTimes(lines),
            syncLevel = if (hasSyllables) SyncLevel.SYLLABLE else SyncLevel.LINE,
            metadata = meta.toMetadata(),
        )
    }

    // -----------------------------------------------------------------------
    // body：<p> 的收集与组装
    // -----------------------------------------------------------------------

    /** 一行 `<p>` 及其所在 `<div>` 的 songPart（模型暂不消费，留着定位用）。 */
    private class LineEntry(val element: XElement, val songPart: String?)

    private fun collectLineEntries(root: XElement): List<LineEntry> {
        val body = root.firstChildElement("body") ?: return emptyList()
        val entries = mutableListOf<LineEntry>()
        for (child in body.childElements()) {
            when (child.name) {
                "div" -> {
                    val songPart = child.attr(NS_ITUNES, "songPart")
                        ?: child.attr(NS_ITUNES, "song-part")
                    child.descendantElements("p").forEach { entries += LineEntry(it, songPart) }
                }
                "p" -> entries += LineEntry(child, null)
            }
        }
        return entries
    }

    private fun assembleLines(
        entries: List<LineEntry>,
        meta: MetaCollector,
        sidecar: Map<String, Sidecar>,
    ): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()

        // Apple 风格对唱判定：group 恒不对唱且不打断交替；其余按 person 是否换人翻转左右侧。
        var lastPersonAgentId: String? = null
        var lastPersonIsDuet = false

        entries.forEachIndexed { index, entry ->
            val content = parseCommonContent(entry.element)
            val agentId = entry.element.attr(NS_TTM, "agent") ?: AGENT_DEFAULT
            val agentType = meta.agents[agentId]

            val isDuet = when {
                agentType == "group" -> false
                lastPersonAgentId == null -> {
                    lastPersonAgentId = agentId
                    lastPersonIsDuet = agentType == "other"
                    lastPersonIsDuet
                }
                lastPersonAgentId == agentId -> lastPersonIsDuet
                else -> {
                    lastPersonAgentId = agentId
                    lastPersonIsDuet = !lastPersonIsDuet
                    lastPersonIsDuet
                }
            }

            // 🔴 侧车按 itunes:key 对行；缺 key 时造合成 id（见类注释的第 1 条偏离）。
            val id = entry.element.attr(NS_ITUNES, "key") ?: "__line_$index"
            val side = sidecar[id]
            val sideTranslations = side?.translations ?: emptyList()
            val sideRomanizations = side?.romanizations ?: emptyList()
            val sideBgTranslations = side?.bgTranslations ?: emptyList()
            val sideBgRomanizations = side?.bgRomanizations ?: emptyList()

            val background = content.background?.let { bg ->
                LyricBackground(
                    text = bg.text,
                    startMs = bg.startMs,
                    endMs = bg.endMs,
                    syllables = toSyllables(bg.words),
                    translations = (bg.translations + sideBgTranslations).map(::toTranslatedText),
                    romanizations = (bg.romanizations + sideBgRomanizations).map(::toTranslatedText),
                )
            }

            lines += LyricLine(
                startMs = content.startMs,
                endMs = content.endMs,
                text = content.text,
                syllables = toSyllables(content.words),
                translations = (content.translations + sideTranslations).map(::toTranslatedText),
                romanizations = (content.romanizations + sideRomanizations).map(::toTranslatedText),
                background = background,
                isDuet = isDuet,
            )
        }
        return lines
    }

    /**
     * 音节数量 ≤ 1 时视为**行级同步**，返回空表。
     *
     * 行级 TTML（Apple 的 `itunes:timing="Line"`）也是用整行包一个 `<span>` 表达的，
     * 那种"一个音节"没有逐字信息量，落在模型里应该是 `syllables` 为空。
     */
    private fun toSyllables(words: List<Syllable>): List<LyricSyllable> {
        if (words.size <= 1) return emptyList()
        return words.map { word ->
            LyricSyllable(
                // 尾随空格折进文本，保证 syllables 拼接 == 行文本（见类注释）。
                text = if (word.endsWithSpace) word.text + " " else word.text,
                startMs = word.startMs,
                endMs = word.endMs,
                ruby = word.ruby ?: emptyList(),
                obscene = word.obscene,
            )
        }
    }

    private fun toTranslatedText(content: SubContent) =
        LyricTranslatedText(content.text, content.language)

    // -----------------------------------------------------------------------
    // 行内容：与 AMLL 的 parseCommonContent / extractNodeState 一一对应
    // -----------------------------------------------------------------------

    /** 解析中的一个音节。 */
    private class Syllable(
        var text: String,
        val startMs: Long,
        val endMs: Long,
        var ruby: List<LyricRuby>? = null,
        var endsWithSpace: Boolean = false,
        var obscene: Boolean = false,
    )

    /** 附属文本（翻译 / 音译）或背景人声的一段。 */
    private class SubContent(
        val text: String,
        val language: String?,
        val words: List<Syllable>,
    )

    /** 一块内容（主行或背景人声）。 */
    private data class Content(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val words: List<Syllable>,
        val translations: List<SubContent>,
        val romanizations: List<SubContent>,
        val background: Content?,
    )

    /** 解析中间态，与 AMLL 的 IParsedState 对应。 */
    private class NodeState {
        val fullText = StringBuilder()
        val words = mutableListOf<Syllable>()
        val translations = mutableListOf<SubContent>()
        val romanizations = mutableListOf<SubContent>()
        val bgTranslations = mutableListOf<SubContent>()
        val bgRomanizations = mutableListOf<SubContent>()
        var background: Content? = null
    }

    private fun parseCommonContent(element: XElement): Content {
        val begin = element.attr(NS_XML, "begin")
        val end = element.attr(NS_XML, "end")
        val originalStart = parseTime(begin)
        val originalEnd = parseTime(end)

        val state = extractNodeState(element)

        state.background?.let { background ->
            if (state.bgTranslations.isNotEmpty() || state.bgRomanizations.isNotEmpty()) {
                state.background = background.copy(
                    translations = background.translations + state.bgTranslations,
                    romanizations = background.romanizations + state.bgRomanizations,
                )
            }
        }

        finalizeWords(state.words)
        val (startMs, endMs) = calculateTimeRange(
            originalStart, originalEnd, state.words, state.background,
        )

        val cleanText = normalizeText(state.fullText.toString())
        val hasTimeAttrs = begin != null || end != null
        applyFallbackWord(
            state.words, cleanText, hasTimeAttrs, originalStart, originalEnd, startMs, endMs,
        )

        return Content(
            text = cleanText,
            startMs = startMs,
            endMs = endMs,
            words = state.words.toList(),
            translations = state.translations.toList(),
            romanizations = state.romanizations.toList(),
            background = state.background,
        )
    }

    private fun extractNodeState(element: XElement): NodeState {
        val state = NodeState()
        for (child in element.children) {
            when (child) {
                is XText -> processTextNode(state, child.raw)
                is XElement -> processElementNode(state, child)
            }
        }
        return state
    }

    private fun processTextNode(state: NodeState, raw: String) {
        val isFormatting = raw.contains("\n")
        if (isFormatting && raw.trim().isEmpty()) return

        val normalized = normalizeText(raw, trim = false)
        state.fullText.append(normalized)

        // 只由空白构成的文本节点跟在某个音节后面 → 那个音节带尾随空格
        if (!isFormatting && normalized.isNotEmpty() && normalized.trim().isEmpty()) {
            state.words.lastOrNull()?.endsWithSpace = true
        }
    }

    private fun processElementNode(state: NodeState, element: XElement) {
        if (element.attr(NS_TTS, "ruby") == "container") {
            processRubyElement(state, element)
            return
        }

        when (element.attr(NS_TTM, "role")) {
            ROLE_BG -> state.background = parseBackgroundVocal(element)

            ROLE_TRANSLATION -> {
                val sub = parseInlineSubContent(element) ?: return
                sub.main?.let { state.translations += it }
                sub.bg?.let { state.bgTranslations += it }
            }

            ROLE_ROMAN -> {
                val sub = parseInlineSubContent(element) ?: return
                sub.main?.let { state.romanizations += it }
                sub.bg?.let { state.bgRomanizations += it }
            }

            else -> processWordElement(state, element)
        }
    }

    /** 注音：`<span tts:ruby="container">` + `base` + `textContainer/text[begin,end]`。 */
    private fun processRubyElement(state: NodeState, container: XElement) {
        val isObscene = container.attr(NS_AMLL, "obscene") == "true"

        var baseText = ""
        val rubyTags = mutableListOf<LyricRuby>()

        for (child in container.childElements()) {
            when (child.attr(NS_TTS, "ruby")) {
                "base" -> baseText = normalizeText(child.textContent(), trim = false)
                "textContainer" -> child.childElements().forEach { textNode ->
                    if (textNode.attr(NS_TTS, "ruby") != "text") return@forEach
                    val begin = textNode.attr(NS_XML, "begin") ?: return@forEach
                    val end = textNode.attr(NS_XML, "end") ?: return@forEach
                    val text = normalizeText(textNode.textContent(), trim = false).trim()
                    if (text.isNotEmpty()) {
                        rubyTags += LyricRuby(text, parseTime(begin), parseTime(end))
                    }
                }
            }
        }

        if (baseText.isEmpty()) return
        state.fullText.append(baseText)

        val startMs = rubyTags.minOfOrNull { it.startMs } ?: 0L
        val endMs = rubyTags.maxOfOrNull { it.endMs } ?: 0L
        val cleanText = baseText.trim()
        if (cleanText.isEmpty()) return

        if (baseText.firstOrNull()?.isWhitespace() == true) {
            state.words.lastOrNull()?.endsWithSpace = true
        }

        state.words += Syllable(
            text = cleanText,
            startMs = startMs,
            endMs = endMs,
            ruby = rubyTags.ifEmpty { null },
            endsWithSpace = baseText.lastOrNull()?.isWhitespace() == true,
            obscene = isObscene,
        )
    }

    /** 普通逐字 `<span begin end>`。 */
    private fun processWordElement(state: NodeState, element: XElement) {
        val begin = element.attr(NS_XML, "begin")
        val end = element.attr(NS_XML, "end")
        val isObscene = element.attr(NS_AMLL, "obscene") == "true"

        val raw = element.textContent()
        val normalized = normalizeText(raw, trim = false)
        state.fullText.append(normalized)

        if (begin == null || end == null) return

        val isFormatting = raw.contains("\n")
        val startsWithSpace = !isFormatting && normalized.firstOrNull()?.isWhitespace() == true
        val endsWithSpace = !isFormatting && normalized.lastOrNull()?.isWhitespace() == true
        val cleanText = normalized.trim()

        if (startsWithSpace) {
            state.words.lastOrNull()?.endsWithSpace = true
        }

        if (cleanText.isNotEmpty()) {
            state.words += Syllable(
                text = cleanText,
                startMs = parseTime(begin),
                endMs = parseTime(end),
                endsWithSpace = endsWithSpace,
                obscene = isObscene,
            )
        }
    }

    /** 背景人声：正常解析，但要去掉包裹用的外层括号。 */
    private fun parseBackgroundVocal(element: XElement): Content {
        val parsed = parseCommonContent(element)
        val words = parsed.words.toMutableList()
        if (words.isNotEmpty()) {
            words[0].text = words[0].text.replace(Regex("^[(（]+"), "").trimStart()
            val last = words.lastIndex
            words[last].text = words[last].text.replace(Regex("[)）]+$"), "").trimEnd()
        }
        return parsed.copy(
            // ⚠️ 括号是**在 trim 之后**才剥的，剥完会重新露出空白（`( 哦 )` → ` 哦 `），
            // 所以这里得再 trim 一次。音节的边界上面已经处理过了。
            text = parsed.text.replace(Regex("^[(（]+"), "").replace(Regex("[)）]+$"), "").trim(),
            words = words,
            background = null,
        )
    }

    private class SubPair(val main: SubContent?, val bg: SubContent?)

    /** 行内的翻译 / 音译。 */
    private fun parseInlineSubContent(element: XElement): SubPair? {
        val language = element.attr(NS_XML, "lang")
        // 内联的翻译/音译不带逐字（逐字那份在 head 的侧车里）
        val pair = extractSubContent(parseCommonContent(element), language, ignoreWords = true)
        return if (pair.main != null || pair.bg != null) pair else null
    }

    private fun extractSubContent(
        base: Content,
        language: String?,
        ignoreWords: Boolean,
    ): SubPair {
        var main: SubContent? = null
        var bg: SubContent? = null

        val mainText = normalizeText(base.text)
        val mainWords = if (!ignoreWords) usableWords(base.words) else emptyList()
        if (mainText.isNotEmpty() || mainWords.isNotEmpty()) {
            main = SubContent(mainText, language, mainWords)
        }

        base.background?.let { background ->
            val bgText = normalizeText(background.text)
            val bgWords = if (!ignoreWords) usableWords(background.words) else emptyList()
            if (bgText.isNotEmpty() || bgWords.isNotEmpty()) {
                bg = SubContent(bgText, language, bgWords)
            }
        }

        return SubPair(main, bg)
    }

    /** 丢掉「只有一个零长度音节」的伪逐字（AMLL 的 isZeroFallback）。 */
    private fun usableWords(words: List<Syllable>): List<Syllable> {
        if (words.size == 1 && words[0].startMs == 0L && words[0].endMs == 0L) return emptyList()
        return words
    }

    private fun finalizeWords(words: MutableList<Syllable>) {
        if (words.isEmpty()) return
        words[0].text = words[0].text.trimStart()
        words[words.lastIndex].text = words[words.lastIndex].text.trimEnd()
        words[words.lastIndex].endsWithSpace = false
    }

    /** 行时间 = `<p>` 的 begin/end 与子元素极值合并。 */
    private fun calculateTimeRange(
        originalStart: Long,
        originalEnd: Long,
        words: List<Syllable>,
        background: Content?,
    ): Pair<Long, Long> {
        var startMs = originalStart
        var endMs = originalEnd

        val timed = buildList {
            words.forEach { add(it.startMs to it.endMs) }
            background?.let { add(it.startMs to it.endMs) }
        }
        if (timed.isEmpty()) return startMs to endMs

        val minChildStart = timed.minOf { it.first }
        val maxChildEnd = timed.maxOf { it.second }

        if (startMs == 0L || (minChildStart > 0 && minChildStart < startMs)) {
            startMs = minChildStart
        }
        if (endMs == 0L || maxChildEnd > endMs) {
            endMs = maxChildEnd
        }
        return startMs to endMs
    }

    /** 整行没有任何逐字时，补一个覆盖整行的音节（否则行级 TTML 会没有时间）。 */
    private fun applyFallbackWord(
        words: MutableList<Syllable>,
        cleanText: String,
        hasTimeAttrs: Boolean,
        originalStart: Long,
        originalEnd: Long,
        calcStart: Long,
        calcEnd: Long,
    ) {
        if (words.isNotEmpty() || cleanText.isEmpty() || !hasTimeAttrs) return
        words += Syllable(
            text = cleanText,
            startMs = if (originalStart > 0) originalStart else calcStart,
            endMs = if (originalEnd > 0) originalEnd else calcEnd,
        )
    }

    // -----------------------------------------------------------------------
    // head：元数据与侧车表
    // -----------------------------------------------------------------------

    private class Sidecar {
        val translations = mutableListOf<SubContent>()
        val romanizations = mutableListOf<SubContent>()
        val bgTranslations = mutableListOf<SubContent>()
        val bgRomanizations = mutableListOf<SubContent>()
    }

    private class MetaCollector {
        val title = mutableListOf<String>()
        val artist = mutableListOf<String>()
        val album = mutableListOf<String>()
        val isrc = mutableListOf<String>()
        val authorIds = mutableListOf<String>()
        val authorNames = mutableListOf<String>()
        val songwriters = mutableListOf<String>()
        val platformIds = linkedMapOf<String, MutableList<String>>()
        val rawProperties = linkedMapOf<String, MutableList<String>>()

        /** agent id → ttm:type（对唱判定用）。 */
        val agents = linkedMapOf<String, String>()

        var language: String? = null

        fun collect(head: XElement) {
            val elements = mutableListOf<XElement>()
            head.collectElements(elements)

            // ttm:title（只取第一个，与 AMLL 一致）
            elements.firstOrNull { it.name == "title" }?.textContent()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { title += it }

            // ttm:agent
            elements.filter { it.name == "agent" }.forEach { agent ->
                val id = agent.attr(NS_XML, "id") ?: return@forEach
                val type = agent.attr(NS_TTM, "type")
                if (type != null) agents[id] = type
            }

            // amll:meta
            elements.filter { it.name == "meta" }.forEach { meta ->
                val key = meta.attr(NS_AMLL, "key") ?: return@forEach
                val value = meta.attr(NS_AMLL, "value")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@forEach
                when (key) {
                    "musicName" -> title += value
                    "artists" -> artist += value
                    "album" -> album += value
                    "isrc" -> isrc += value
                    "ttmlAuthorGithub" -> authorIds += value
                    "ttmlAuthorGithubLogin" -> authorNames += value
                    "ncmMusicId", "qqMusicId", "spotifyId", "appleMusicId" ->
                        platformIds.getOrPut(key) { mutableListOf() } += value

                    else -> rawProperties.getOrPut(key) { mutableListOf() } += value
                }
            }

            // iTunesMetadata/songwriters
            elements.filter { it.name == "songwriter" }.forEach { writer ->
                writer.textContent().trim().takeIf { it.isNotEmpty() }?.let { songwriters += it }
            }
        }

        fun toMetadata(): LyricMetadata = LyricMetadata(
            title = title.firstOrNull(),
            artist = artist.takeIf { it.isNotEmpty() }?.joinToString(" / "),
            album = album.takeIf { it.isNotEmpty() }?.joinToString(" / "),
            isrc = isrc.firstOrNull(),
            language = language,
            songwriters = songwriters.distinct(),
            platformIds = platformIds.mapValues { it.value.distinct() },
            extra = buildMap {
                putAll(rawProperties.mapValues { it.value.distinct() })
                if (authorIds.isNotEmpty()) put("ttmlAuthorGithub", authorIds.distinct())
                if (authorNames.isNotEmpty()) {
                    put("ttmlAuthorGithubLogin", authorNames.distinct())
                }
            },
        )
    }

    /**
     * 侧车表：`iTunesMetadata/translations|transliterations/<item xml:lang><text for="行 id">`。
     *
     * Apple 官方把翻译/音译放在 head 里、按行 id 关联，而不是写进行内，所以要单独收一遍。
     */
    private fun collectSidecar(head: XElement, sidecar: MutableMap<String, Sidecar>) {
        val all = mutableListOf<XElement>()
        head.collectElements(all)
        // 只看 head 里那张表；body 里没有这种结构
        val containerRoots = all.filter { it.name == "iTunesMetadata" || it.name == "metadata" }
        if (containerRoots.isEmpty()) return

        for (root in containerRoots) {
            processSidecarEntry(root, "translations", "translation", sidecar)
            processSidecarEntry(root, "transliterations", "transliteration", sidecar)
        }
    }

    private fun processSidecarEntry(
        root: XElement,
        containerName: String,
        itemName: String,
        sidecar: MutableMap<String, Sidecar>,
    ) {
        val descendants = mutableListOf<XElement>()
        root.collectElements(descendants)

        val container = descendants.firstOrNull { it.name == containerName } ?: return
        val items = mutableListOf<XElement>()
        container.collectElements(items)

        for (item in items.filter { it.name == itemName }) {
            val language = item.attr(NS_XML, "lang")
            val texts = mutableListOf<XElement>()
            item.collectElements(texts)

            for (textNode in texts.filter { it.name == "text" }) {
                val forId = textNode.attr(null, "for") ?: continue
                val parsed = parseCommonContent(textNode)
                val pair = extractSubContent(parsed, language, ignoreWords = false)
                val entry = sidecar.getOrPut(forId) { Sidecar() }

                pair.main?.let {
                    if (containerName == "translations") {
                        entry.translations += it
                    } else {
                        entry.romanizations += it
                    }
                }
                pair.bg?.let {
                    if (containerName == "translations") {
                        entry.bgTranslations += it
                    } else {
                        entry.bgRomanizations += it
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 基础工具
    // -----------------------------------------------------------------------

    /** 折叠多余空白；[trim] 为 true 时再去首尾。 */
    private fun normalizeText(text: String, trim: Boolean = true): String {
        if (text.isEmpty()) return ""
        val collapsed = text.replace(MULTI_SPACE, " ")
        return if (trim) collapsed.trim() else collapsed
    }

    /**
     * 钟表时间 / offset-time → 毫秒。认不出给 0。
     *
     * ⚠️ 单位后缀**不能只判 `endsWith("s")`**：真实数据里出现过 `begin="9.955s"`，
     * 而 `"500ms"` 同样以 `s` 结尾，那样会先按秒切掉一个字符、再拿 `"500m"` 去转数字，
     * 静默变成 0（整行时间凭空落到 0）。
     */
    private fun parseTime(value: String?): Long {
        if (value == null) return 0
        val clean = value.trim()
        if (clean.isEmpty()) return 0

        OFFSET_PATTERN.matchEntire(clean)?.let { offset ->
            val amount = offset.groupValues[1].toDoubleOrNull() ?: return 0
            val unitMillis = when (offset.groupValues[2]) {
                "h" -> 3_600_000.0
                "m" -> 60_000.0
                "s" -> 1_000.0
                else -> 1.0 // ms
            }
            return Math.round(amount * unitMillis)
        }

        val match = TIME_PATTERN.matchEntire(clean) ?: return 0
        val seconds = match.groupValues[3].toDoubleOrNull() ?: return 0
        val minutes = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: 0
        val hours = match.groupValues[1].ifEmpty { "0" }.toIntOrNull() ?: 0
        return Math.round((hours * 3600 + minutes * 60 + seconds) * 1000)
    }
}

// ---------------------------------------------------------------------------
// 极简 XML 树
//
// 用 android.util.Xml 的 XmlPullParser 建一棵轻量树，再按 AMLL 的语义递归处理。
// 直接流式消费会在「按序处理子节点 / 回溯取 textContent」上写得很拧，TTML 体量又小，
// 建树是最省心的做法。
// ---------------------------------------------------------------------------

/** 一个 XML 节点：元素或文本。 */
internal sealed class XNode

/** 文本节点（原样保留，空白由解析器决定怎么折叠）。 */
internal class XText(val raw: String) : XNode()

/** 元素节点。[attrs] 保留命名空间，便于按 ns+local 精确取属性。 */
internal class XElement(
    val name: String,
    private val attrs: List<XAttribute>,
    val children: MutableList<XNode>,
) : XNode() {

    /**
     * 取属性：先按命名空间精确匹配，找不到再退化成「只看 localName」。
     *
     * 与 AMLL 的 `getAttr` 同策略 —— 有些环境不暴露命名空间，只靠前缀名，两条路都得走。
     */
    fun attr(namespace: String?, localName: String): String? {
        if (namespace != null) {
            attrs.firstOrNull { it.ns == namespace && it.local == localName }
                ?.let { return it.value }
        }
        return attrs.firstOrNull { it.local == localName }?.value
    }

    fun childElements(): List<XElement> = children.filterIsInstance<XElement>()

    fun firstChildElement(name: String): XElement? = childElements().firstOrNull { it.name == name }

    /** 全部后代元素（不含自身、不重复），文档序。 */
    fun descendantElements(name: String): List<XElement> {
        val result = mutableListOf<XElement>()
        fun walk(parent: XElement) {
            parent.childElements().forEach { child ->
                if (child.name == name) result += child
                walk(child)
            }
        }
        walk(this)
        return result
    }

    fun collectElements(into: MutableList<XElement>) {
        into += this
        childElements().forEach { it.collectElements(into) }
    }

    /** 全部后代文本拼接（用于拿 `<title>` / `<span>` 一类的纯文本内容）。 */
    fun textContent(): String {
        val builder = StringBuilder()
        fun walk(nodes: List<XNode>) {
            nodes.forEach { node ->
                when (node) {
                    is XText -> builder.append(node.raw)
                    is XElement -> walk(node.children)
                }
            }
        }
        walk(children)
        return builder.toString()
    }

    override fun toString(): String = "<$name>"
}

/** 一条属性。[ns] 用空串表示「无命名空间」。 */
internal class XAttribute(val ns: String?, val local: String, val value: String)

internal object XmlTree {

    /**
     * 把 XML 文本解析成 [XElement] 树。
     *
     * @throws IllegalArgumentException 输入不是结构完整的 XML 时。
     */
    fun parse(xml: String): XElement {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        var root: XElement? = null
        val stack = ArrayDeque<XElement>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val element = XElement(
                        name = parser.name.orEmpty(),
                        attrs = readAttributes(parser),
                        children = mutableListOf(),
                    )
                    val parent = stack.lastOrNull()
                    if (parent != null) {
                        parent.children += element
                    } else if (root == null) {
                        root = element
                    }
                    stack.addLast(element)
                }

                XmlPullParser.END_TAG -> stack.removeLastOrNull()

                XmlPullParser.TEXT,
                XmlPullParser.CDSECT,
                XmlPullParser.ENTITY_REF,
                -> stack.lastOrNull()?.children?.add(XText(parser.text.orEmpty()))
            }
            event = parser.next()
        }

        return root ?: throw IllegalArgumentException("TTML 里没有根元素")
    }

    private fun readAttributes(parser: XmlPullParser): List<XAttribute> =
        (0 until parser.attributeCount).map { index ->
            val rawName = parser.getAttributeName(index).orEmpty()
            XAttribute(
                ns = parser.getAttributeNamespace(index)?.takeIf { it.isNotEmpty() },
                local = rawName.substringAfterLast(':'),
                value = parser.getAttributeValue(index).orEmpty(),
            )
        }
}
