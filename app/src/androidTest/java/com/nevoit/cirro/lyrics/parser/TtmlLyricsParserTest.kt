package com.nevoit.cirro.lyrics.parser

import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.model.LyricSyllable
import com.nevoit.cirro.lyrics.model.LyricsSource
import com.nevoit.cirro.lyrics.model.SyncLevel
import com.nevoit.cirro.lyrics.ui.toSyncedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlLyricsParserTest {
    @Test
    fun singleTimedSpanRetainsTimingForAppleAndAmll() {
        for (source in listOf(LyricsSource.APPLE_MUSIC, LyricsSource.AMLL_TTML_DB)) {
            for (timing in listOf(null, "Word")) {
                val lyrics = TtmlLyricsParser.parse(
                    ttml("""<p begin="1s" end="3s"><span begin="1.5s" end="2.5s">Oh</span></p>""", timing),
                    source,
                ).getOrThrow()
                val line = lyrics.lines.single()
                val rendered = lyrics.toSyncedLyrics(1).lines.single() as KaraokeLine

                assertEquals(SyncLevel.SYLLABLE, lyrics.syncLevel)
                assertEquals("Oh", line.text)
                assertEquals(listOf(LyricSyllable("Oh", 1500, 2500)), line.syllables)
                assertTrue(rendered.hasSyllableTiming)
                assertEquals(1500, rendered.syllables.single().start)
                assertEquals(2500, rendered.syllables.single().end)
            }
        }
    }

    @Test
    fun singleTimedSpanMatchingTheLineStillHasSyllableTiming() {
        val lyrics = parse("""<p begin="1s" end="3s"><span begin="1s" end="3s">Oh</span></p>""")

        assertEquals(SyncLevel.SYLLABLE, lyrics.syncLevel)
        assertEquals(listOf(LyricSyllable("Oh", 1000, 3000)), lyrics.lines.single().syllables)
    }

    @Test
    fun wholeLineFallbackDoesNotAcquireSyllableTiming() {
        for (content in listOf("Ordinary line", "<span>Ordinary line</span>")) {
            val lyrics = parse("""<p begin="1s" end="3s">$content</p>""")
            val line = lyrics.lines.single()
            val rendered = lyrics.toSyncedLyrics(1).lines.single() as KaraokeLine

            assertEquals(SyncLevel.LINE, lyrics.syncLevel)
            assertEquals("Ordinary line", line.text)
            assertEquals(1000L, line.startMs)
            assertEquals(3000L, line.endMs)
            assertTrue(line.syllables.isEmpty())
            assertFalse(rendered.hasSyllableTiming)
        }
    }

    @Test
    fun explicitLineTimingKeepsWholeLineHighlighting() {
        val lyrics = TtmlLyricsParser.parse(
            ttml(
                """<p begin="1s" end="3s"><span begin="1s" end="3s">Ordinary line</span></p>""",
                timing = "Line",
            ),
            LyricsSource.APPLE_MUSIC,
        ).getOrThrow()

        assertEquals(SyncLevel.LINE, lyrics.syncLevel)
        assertTrue(lyrics.lines.single().syllables.isEmpty())
        assertFalse((lyrics.toSyncedLyrics(1).lines.single() as KaraokeLine).hasSyllableTiming)
    }

    @Test
    fun singleBackingSyllableKeepsTimingIndependentlyOfUntimedLead() {
        val lyrics = parse(
            """<p begin="1s" end="3s">Lead<span ttm:role="x-bg" begin="1.2s" end="2.8s"><span begin="1.5s" end="2.5s">(Oh)</span></span></p>""",
        )
        val line = lyrics.lines.single()
        val background = requireNotNull(line.background)
        val rendered = lyrics.toSyncedLyrics(1).lines.single() as KaraokeLine.MainKaraokeLine

        assertEquals(SyncLevel.SYLLABLE, lyrics.syncLevel)
        assertTrue(line.syllables.isEmpty())
        assertEquals("Lead", line.text)
        assertEquals("Oh", background.text)
        assertEquals(listOf(LyricSyllable("Oh", 1500, 2500)), background.syllables)
        assertFalse(rendered.hasSyllableTiming)
        assertTrue(rendered.accompanimentLines!!.single().hasSyllableTiming)
    }

    @Test
    fun untimedBackingFallbackDoesNotAcquireSyllableTiming() {
        val lyrics = parse(
            """<p begin="1s" end="3s"><span begin="1s" end="3s">Lead</span><span ttm:role="x-bg" begin="1.5s" end="2.5s">(Oh)</span></p>""",
        )
        val rendered = lyrics.toSyncedLyrics(1).lines.single() as KaraokeLine.MainKaraokeLine

        assertEquals(SyncLevel.SYLLABLE, lyrics.syncLevel)
        assertTrue(rendered.hasSyllableTiming)
        assertTrue(lyrics.lines.single().background!!.syllables.isEmpty())
        assertFalse(rendered.accompanimentLines!!.single().hasSyllableTiming)
    }

    @Test
    fun mixedLinesKeepTheirOwnTimingModes() {
        val lyrics = parse(
            """<p begin="1s" end="3s"><span begin="1s" end="3s">Oh</span></p>
                <p begin="3s" end="5s">Ordinary line</p>
                <p begin="5s" end="7s"><span begin="5s" end="6s">Two </span><span begin="6s" end="7s">words</span></p>""",
        )
        val lines = lyrics.toSyncedLyrics(1).lines.map { it as KaraokeLine }

        assertEquals(SyncLevel.SYLLABLE, lyrics.syncLevel)
        assertEquals(listOf(true, false, true), lines.map { it.hasSyllableTiming })
        assertEquals(listOf("Oh", "Ordinary line", "Two words"), lyrics.lines.map { it.text })
        assertEquals(listOf(1000, 3000, 5000), lines.map { it.start })
        assertEquals(listOf(3000, 5000, 7000), lines.map { it.end })
    }

    @Test
    fun replacementBecomesMainTextWithItsOwnSyllableTiming() {
        val lyrics = parseLocalized(
            body = """
                <p itunes:key="L1" begin="1s" end="3s">
                    <span begin="1s" end="2s">記</span><span begin="2s" end="3s">憶</span>
                    <span ttm:role="x-roman">ji yi</span>
                </p>
            """,
            translations = """
                <translation type="replacement" xml:lang="zh-Hans">
                    <text for="L1"><span begin="1.2s" end="1.8s">记</span><span begin="1.8s" end="2.8s">忆</span></text>
                </translation>
                <translation type="subtitle" xml:lang="en">
                    <text for="L1">Memory</text>
                </translation>
            """,
        )
        val line = lyrics.lines.single()
        val rendered = lyrics.toSyncedLyrics(1).lines.single() as KaraokeLine

        assertEquals("zh-Hans", lyrics.metadata.language)
        assertEquals("记忆", line.text)
        assertEquals(listOf(LyricSyllable("记", 1200, 1800), LyricSyllable("忆", 1800, 2800)), line.syllables)
        assertEquals(1000L, line.startMs)
        assertEquals(3000L, line.endMs)
        assertEquals(listOf("Memory"), line.translations.map { it.text })
        assertEquals("en", line.translations.single().language)
        assertEquals(listOf("ji yi"), line.romanizations.map { it.text })
        assertTrue(rendered.hasSyllableTiming)
        assertEquals(listOf(1200, 1800), rendered.syllables.map { it.start })
    }

    @Test
    fun untimedReplacementDoesNotReuseTimingForDifferentText() {
        val lyrics = parseLocalized(
            """<p itunes:key="L1" begin="1s" end="3s"><span begin="1s" end="3s">舊詞</span></p>""",
            """<translation type="replacement" xml:lang="zh-Hans"><text for="L1">新的歌词</text></translation>""",
        )
        val line = lyrics.lines.single()

        assertEquals("新的歌词", line.text)
        assertTrue(line.syllables.isEmpty())
        assertEquals(SyncLevel.LINE, lyrics.syncLevel)
        assertEquals(1000L, line.startMs)
        assertEquals(3000L, line.endMs)
        assertTrue(line.translations.isEmpty())
        assertFalse((lyrics.toSyncedLyrics(1).lines.single() as KaraokeLine).hasSyllableTiming)
    }

    @Test
    fun emptyAndUnmatchedReplacementsLeaveOriginalContent() {
        val lyrics = parseLocalized(
            """<p itunes:key="L1" begin="1s" end="3s"><span begin="1s" end="3s">原詞</span></p>""",
            """
                <translation type="replacement" xml:lang="zh-Hans">
                    <text for="L1"> </text><text for="missing">替换歌词</text>
                </translation>
            """,
        )

        assertEquals("zh-Hant", lyrics.metadata.language)
        assertEquals("原詞", lyrics.lines.single().text)
        assertEquals(listOf(LyricSyllable("原詞", 1000, 3000)), lyrics.lines.single().syllables)
        assertTrue(lyrics.lines.single().translations.isEmpty())
    }

    @Test
    fun replacementPreservesBackingWhenNoBackingReplacementWasProvided() {
        val lyrics = parseLocalized(
            """<p itunes:key="L1" begin="1s" end="3s"><span begin="1s" end="3s">主詞</span><span ttm:role="x-bg" begin="1.2s" end="2.8s"><span begin="1.5s" end="2.5s">(Oh)</span></span></p>""",
            """<translation type="replacement" xml:lang="zh-Hans"><text for="L1"><span begin="1s" end="3s">主词</span></text></translation>""",
        )
        val line = lyrics.lines.single()
        val background = requireNotNull(line.background)

        assertEquals("主词", line.text)
        assertEquals("Oh", background.text)
        assertEquals(listOf(LyricSyllable("Oh", 1500, 2500)), background.syllables)
        assertEquals(1200L, background.startMs)
        assertEquals(2800L, background.endMs)
    }

    @Test
    fun replacementIncludesBackingTextAndItsIndependentTiming() {
        val lyrics = parseLocalized(
            """<p itunes:key="L1" begin="1s" end="3s"><span begin="1s" end="3s">主詞</span><span ttm:role="x-bg" begin="1.2s" end="2.8s"><span begin="1.2s" end="2.8s">(記憶)</span></span></p>""",
            """
                <translation type="replacement" xml:lang="zh-Hans">
                    <text for="L1"><span begin="1.1s" end="2.9s">主词</span><span ttm:role="x-bg"><span begin="1.5s" end="2.5s">(记忆)</span></span></text>
                </translation>
            """,
        )
        val line = lyrics.lines.single()
        val background = requireNotNull(line.background)

        assertEquals("主词", line.text)
        assertEquals(listOf(LyricSyllable("主词", 1100, 2900)), line.syllables)
        assertEquals("记忆", background.text)
        assertEquals(listOf(LyricSyllable("记忆", 1500, 2500)), background.syllables)
        assertTrue(line.translations.isEmpty())
        assertTrue(background.translations.isEmpty())
    }

    @Test
    fun replacementKeepsExplicitLineSyncAndOtherLines() {
        val lyrics = TtmlLyricsParser.parse(
            ttml(
                body = """
                    <p itunes:key="L1" begin="1s" end="3s">記憶</p>
                    <p itunes:key="L2" begin="3s" end="5s">原詞</p>
                """,
                timing = "Line",
                head = replacementHead("""<translation type="replacement" xml:lang="zh-Hans"><text for="L1"><span begin="1s" end="3s">记忆</span></text></translation>"""),
            ),
            LyricsSource.APPLE_MUSIC,
        ).getOrThrow()

        assertEquals(listOf("记忆", "原詞"), lyrics.lines.map { it.text })
        assertEquals(SyncLevel.LINE, lyrics.syncLevel)
        assertTrue(lyrics.lines.all { it.syllables.isEmpty() && it.translations.isEmpty() })
    }

    @Test
    fun subtitlesRemainTranslationsWithoutReplacingOriginal() {
        val lyrics = parseLocalized(
            """<p itunes:key="L1" begin="1s" end="3s"><span begin="1s" end="3s">原詞</span></p>""",
            """<translation type="subtitle" xml:lang="zh-Hans"><text for="L1">字幕</text></translation>""",
        )

        assertEquals("zh-Hant", lyrics.metadata.language)
        assertEquals("原詞", lyrics.lines.single().text)
        assertEquals(listOf("字幕"), lyrics.lines.single().translations.map { it.text })
    }

    private fun parseLocalized(body: String, translations: String) =
        TtmlLyricsParser.parse(
            ttml(body, head = replacementHead(translations)),
            LyricsSource.APPLE_MUSIC,
        ).getOrThrow()

    private fun replacementHead(translations: String) = """
        <head><itunes:iTunesMetadata><translations>$translations</translations></itunes:iTunesMetadata></head>
    """.trimIndent()

    private fun parse(body: String) =
        TtmlLyricsParser.parse(ttml(body), LyricsSource.APPLE_MUSIC).getOrThrow()

    private fun ttml(body: String, timing: String? = null, head: String = ""): String {
        val timingAttribute = timing?.let { "itunes:timing=\"$it\"" }.orEmpty()
        return """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                xml:lang="zh-Hant" $timingAttribute>
                $head
                <body><div>$body</div></body>
            </tt>
        """.trimIndent()
    }
}
