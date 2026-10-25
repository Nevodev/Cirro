package com.nevoit.cirro.lyrics.parser

import com.nevoit.cirro.lyrics.model.LyricsSource
import com.nevoit.cirro.lyrics.model.SyncLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrcLyricsParserTest {
    @Test fun qqTagsIdentifyHeaderWhenPlaybackTitleHasDifferentVersionAnnotations() {
        val lyrics = QrcLyricsParser.parse(
            "[ti:Lemon (主题曲)]\n[ar:米津玄師 (よねづ けんし)]\n[al:Lemon]\n" +
                "[0,500]Lemon(0,100) - 米津玄師(100,400)\n[500,500]词：某某(500,500)\n" +
                "[1000,500]曲：某某(1000,500)\n[1500,500]正文(1500,500)",
            track = LyricTrackRef("Lemon（其他版本后缀）", listOf("米津玄師")),
        ).getOrThrow()
        assertEquals(listOf("正文"), lyrics.lines.map { it.text })
        assertEquals(1500L, lyrics.lines.single().syllables.single().startMs)
        assertEquals("Lemon (主题曲)", lyrics.metadata.title)
    }

    @Test fun qqHeaderAnchorsEnglishCreditsEvenWithoutChineseOrColons() {
        val lyrics = QrcLyricsParser.parse(
            "[ti:Hello]\n[ar:Adele]\n[0,1000]Hello - Adele(0,1000)\n" +
                "[1000,1000]Written by Adele(1000,1000)\n[2000,1000]Ordinary lyric(2000,1000)",
        ).getOrThrow()
        assertEquals(listOf("Ordinary lyric"), lyrics.lines.map { it.text })
    }

    @Test fun standaloneEnglishLyricAndMiddleCreditWordsArePreserved() {
        val lyrics = QrcLyricsParser.parse(
            "[ti:Song]\n[ar:Artist]\n[0,1000]Written by the pain(0,1000)\n" +
                "[1000,1000]Ordinary lyric(1000,1000)\n[2000,1000]制作：梦境(2000,1000)\n[3000,1000](Echo)(3000,1000)",
        ).getOrThrow()
        assertEquals(4, lyrics.lines.size)
        assertEquals("Written by the pain", lyrics.lines.first().text)
        assertEquals("(Echo)", lyrics.lines.last().text)
    }

    @Test fun placeholderTranslationAndRomanizationDoNotRenderOrBorrowNearbyText() {
        for (companion in listOf(
            "[00:01.00]译文\n[00:01.20] // \n[00:02.00]包含 // 的正文",
            "[1000,200]译文(1000,200)\n[1200,800] //(1200,800)\n[2000,1000]包含 // 的正文(2000,1000)",
        )) {
            val lyrics = QrcLyricsParser.parse(
                "[1000,200]One(1000,200)\n[1200,800]Two(1200,800)\n[2000,1000]Three(2000,1000)",
                translation = companion, roman = companion,
            ).getOrThrow()
            assertEquals("译文", lyrics.lines[0].translations.single().text)
            assertTrue(lyrics.lines[1].translations.isEmpty())
            assertTrue(lyrics.lines[1].romanizations.isEmpty())
            assertEquals("包含 // 的正文", lyrics.lines[2].translations.single().text)
        }
    }

    @Test fun qqOrdinaryLrcAlsoCleansHeadersAndTranslationPlaceholders() {
        val lyrics = QrcLyricsParser.parse(
            "[ti:Song (版本)]\n[ar:Artist]\n[00:00.00]Song - Artist\n" +
                "[00:01.00]Written by Someone\n[00:02.00]正文\n[00:03.00]下句",
            translation = "[00:02.00] // \n[00:03.00]译文",
        ).getOrThrow()
        assertEquals(listOf("正文", "下句"), lyrics.lines.map { it.text })
        assertTrue(lyrics.lines[0].translations.isEmpty())
        assertEquals("译文", lyrics.lines[1].translations.single().text)
    }

    @Test fun preservesAbsoluteWordTimesAndGaps() {
        val lyrics = QrcLyricsParser.parse("[1000,1000] 你(1100,200) 好 (1500,300)").getOrThrow()
        val line = lyrics.lines.single()
        assertEquals("你 好", line.text)
        assertEquals(line.text, line.syllables.joinToString("") { it.text })
        assertEquals(listOf(1100L, 1500L), line.syllables.map { it.startMs })
        assertEquals(listOf(1300L, 1800L), line.syllables.map { it.endMs })
        assertEquals(2000L, line.endMs)
        assertEquals(SyncLevel.SYLLABLE, lyrics.syncLevel)
        assertEquals(LyricsSource.QQ, lyrics.source)
    }

    @Test fun appliesOffsetAndMergesLrcAndQrcCompanions() {
        val lyrics = QrcLyricsParser.parse(
            "[offset:100]\n[1000,500]Hello(1000,500)",
            translation = "[00:01.10]你好",
            roman = "[1100,500]he(1100,200)llo(1300,300)",
        ).getOrThrow()
        val line = lyrics.lines.single()
        assertEquals(1100L, line.startMs)
        assertEquals(1100L, line.syllables.single().startMs)
        assertEquals("你好", line.translations.single().text)
        assertEquals("hello", line.romanizations.single().text)
    }

    @Test fun filtersCreditsAndAcceptsOrdinaryLrc() {
        val qrc = QrcLyricsParser.parse("[0,500]词：(0,200)某某(200,300)\n[1000,500]歌词(1000,500)").getOrThrow()
        assertEquals("歌词", qrc.lines.single().text)
        val lrc = QrcLyricsParser.parse("[00:01.00]歌词\n[00:02.00]下一行").getOrThrow()
        assertEquals(SyncLevel.LINE, lrc.syncLevel)
        assertEquals(2, lrc.lines.size)
        assertTrue(lrc.lines.all { it.syllables.isEmpty() })
    }

    @Test fun doesNotAttachDistantTranslation() {
        val line = QrcLyricsParser.parse("[1000,500]词(1000,500)", "[00:05.00]其他行").getOrThrow().lines.single()
        assertTrue(line.translations.isEmpty())
    }

    @Test fun negativeOffsetClipsRatherThanExtendingTheLine() {
        val line = QrcLyricsParser.parse("[offset:-1500]\n[1000,1000]词(1000,1000)").getOrThrow().lines.single()
        assertEquals(0L, line.startMs)
        assertEquals(500L, line.endMs)
        assertEquals(500L, line.syllables.single().endMs)
    }
}
