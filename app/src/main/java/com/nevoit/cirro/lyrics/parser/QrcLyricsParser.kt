package com.nevoit.cirro.lyrics.parser

import com.nevoit.cirro.lyrics.model.LyricLine
import com.nevoit.cirro.lyrics.model.LyricSyllable
import com.nevoit.cirro.lyrics.model.LyricTranslatedText
import com.nevoit.cirro.lyrics.model.Lyrics
import com.nevoit.cirro.lyrics.model.LyricsSource
import com.nevoit.cirro.lyrics.model.SyncLevel
import kotlin.math.abs

object QrcLyricsParser {
    private val linePattern = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val syllablePattern = Regex("""(.*?)\((\d+),(\d+)\)""")
    private val offsetPattern = Regex("""(?im)^\[offset:([+-]?\d+)]\s*$""")

    fun parse(
        qrc: String,
        translation: String? = null,
        roman: String? = null,
        track: LyricTrackRef? = null,
    ): Result<Lyrics> = runCatching {
        val main = parseText(qrc, track)
        val translations = translation?.let { parseText(it, track).lines }.orEmpty()
        val romanizations = roman?.let { parseText(it, track).lines }.orEmpty()
        fun nearest(lines: List<LyricLine>, start: Long): List<LyricTranslatedText> =
            lines.minByOrNull { abs(it.startMs - start) }
                ?.takeIf { abs(it.startMs - start) <= 400L }
                ?.takeIf { it.text.trim() != "//" }
                ?.let { listOf(LyricTranslatedText(it.text)) }.orEmpty()
        main.copy(lines = main.lines.map { line ->
            line.copy(
                translations = nearest(translations, line.startMs),
                romanizations = nearest(romanizations, line.startMs),
            )
        })
    }

    private fun parseText(text: String, track: LyricTrackRef?): Lyrics {
        val sourceLines = text.lineSequence().map(String::trim).toList()
        val qrcLines = sourceLines.mapNotNull(linePattern::matchEntire)
        val lrc = LrcLyricsParser.parse(text, LyricsSource.QQ, track = track).getOrThrow()
        if (qrcLines.isEmpty()) return lrc
        val offset = offsetPattern.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = qrcLines.map { match ->
            val rawStart = match.groupValues[1].toLong() + offset
            val start = rawStart.coerceAtLeast(0L)
            val end = (rawStart + match.groupValues[2].toLong()).coerceAtLeast(start)
            val syllables = syllablePattern.findAll(match.groupValues[3]).map { syllable ->
                val wordStart = syllable.groupValues[2].toLong() + offset
                LyricSyllable(
                    text = syllable.groupValues[1],
                    startMs = wordStart.coerceAtLeast(0L),
                    endMs = (wordStart + syllable.groupValues[3].toLong()).coerceAtLeast(0L),
                )
            }.toList().let { words ->
                // Preserve internal spaces while keeping model text and syllable text identical.
                val first = words.indexOfFirst { it.text.isNotBlank() }
                val last = words.indexOfLast { it.text.isNotBlank() }
                if (first < 0) emptyList() else words.subList(first, last + 1).mapIndexed { i, word ->
                    word.copy(text = word.text.let {
                        when {
                            first == last -> it.trim()
                            i == 0 -> it.trimStart()
                            i == last - first -> it.trimEnd()
                            else -> it
                        }
                    })
                }
            }
            LyricLine(
                startMs = start,
                endMs = maxOf(end, syllables.maxOfOrNull { it.endMs } ?: end),
                text = if (syllables.isEmpty()) match.groupValues[3].trim() else syllables.joinToString("") { it.text },
                syllables = syllables,
            )
        }.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        val info = LyricInfoLines.markInfoLines(
            lines.map { it.text }, LyricInfoLines.qqTrack(lrc.metadata, track), titleIsAnchor = true,
        )
        val filtered = lines.filterIndexed { index, _ -> !info[index] }
        return Lyrics(
            source = LyricsSource.QQ,
            lines = filtered,
            syncLevel = if (filtered.any { it.syllables.isNotEmpty() }) SyncLevel.SYLLABLE else SyncLevel.LINE,
            metadata = lrc.metadata,
        )
    }
}
