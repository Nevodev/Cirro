package com.nevoit.cirro.lyrics.ui

import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeAlignment
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.core.model.karaoke.copy
import com.nevoit.cirro.lyrics.model.LyricBackground
import com.nevoit.cirro.lyrics.model.LyricLine
import com.nevoit.cirro.lyrics.model.LyricSyllable
import com.nevoit.cirro.lyrics.model.LyricTranslatedText
import com.nevoit.cirro.lyrics.model.Lyrics
import com.nevoit.cirro.lyrics.model.LyricsSource
import com.nevoit.cirro.lyrics.model.SyncLevel
import com.nevoit.cirro.lyrics.parser.LrcLyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsModelAdapterTest {
    @Test
    fun ordinaryLrcDoesNotAcquireWordTiming() {
        val lyrics = LrcLyricsParser.parse(
            "[00:01.000]普通逐行歌词\n[00:03.000]An ordinary lyric line\n[00:05.000]",
            LyricsSource.NETEASE,
        ).getOrThrow()
        val lines = lyrics.toSyncedLyrics(1).lines.map { it as KaraokeLine }

        assertEquals(SyncLevel.LINE, lyrics.syncLevel)
        assertEquals(listOf(1000, 3000), lines.map { it.start })
        assertEquals(listOf(3000, 5000), lines.map { it.end })
        assertEquals(listOf("普通逐行歌词", "An ordinary lyric line"),
            lines.map { it.syllables.joinToString("") { syllable -> syllable.content } })
        assertTrue(lines.all { !it.hasSyllableTiming })
    }

    @Test
    fun mixedLrcKeepsOnlyExplicitWordTiming() {
        val lyrics = LrcLyricsParser.parse(
            "[00:01.000]<00:01.000>真的<00:02.000>逐字\n[00:03.000]普通逐行\n[00:05.000]",
            LyricsSource.LRCLIB,
        ).getOrThrow()
        val lines = lyrics.toSyncedLyrics(1).lines.map { it as KaraokeLine }

        assertEquals(SyncLevel.SYLLABLE, lyrics.syncLevel)
        assertTrue(lines[0].hasSyllableTiming)
        assertEquals(listOf(1000, 2000), lines[0].syllables.map { it.start })
        assertEquals(listOf(2000, 3000), lines[0].syllables.map { it.end })
        assertFalse(lines[1].hasSyllableTiming)
    }

    @Test
    fun singleInlineLrcSyllableRetainsItsTimingThroughRendering() {
        val lyrics = LrcLyricsParser.parse(
            "[00:01.000]<00:01.500>Oh\n[00:03.000]",
            LyricsSource.LRCLIB,
        ).getOrThrow()
        val line = lyrics.lines.single()
        val rendered = lyrics.toSyncedLyrics(1).lines.single() as KaraokeLine

        assertEquals(SyncLevel.SYLLABLE, lyrics.syncLevel)
        assertEquals("Oh", line.text)
        assertEquals(listOf(LyricSyllable("Oh", 1500, 3000)), line.syllables)
        assertTrue(rendered.hasSyllableTiming)
        assertEquals(1500, rendered.syllables.single().start)
        assertEquals(3000, rendered.syllables.single().end)
    }

    @Test
    fun singleInlineLrcSyllableDoesNotChangeAdjacentUntimedLines() {
        val lyrics = LrcLyricsParser.parse(
            "[00:01.000]Ordinary line\n[00:03.000]<00:03.000>Oh\n[00:05.000]Another line\n[00:07.000]",
            LyricsSource.LRCLIB,
        ).getOrThrow()
        val lines = lyrics.toSyncedLyrics(1).lines.map { it as KaraokeLine }

        assertEquals(SyncLevel.SYLLABLE, lyrics.syncLevel)
        assertEquals(listOf(false, true, false), lines.map { it.hasSyllableTiming })
        assertEquals(3000, lines[1].syllables.single().start)
        assertEquals(5000, lines[1].syllables.single().end)
    }

    @Test
    fun singleExplicitSyllableRetainsItsTiming() {
        val line = LyricLine(1000, 3000, "Oh", syllables = listOf(LyricSyllable("Oh", 1500, 2500)))
        val rendered = model(line).lines.single() as KaraokeLine

        assertTrue(rendered.hasSyllableTiming)
        assertEquals(1500, rendered.syllables.single().start)
        assertEquals(2500, rendered.syllables.single().end)
    }

    @Test
    fun untimedDuetAndBackingVocalsKeepTheirPresentation() {
        val line = LyricLine(
            1000, 3000, "主唱", isDuet = true,
            translations = listOf(LyricTranslatedText("Lead")),
            romanizations = listOf(LyricTranslatedText("zhuchang")),
            background = LyricBackground("和声", 1200, 2800),
        )
        val rendered = model(line).lines.single() as KaraokeLine.MainKaraokeLine
        val backing = rendered.accompanimentLines!!.single()

        assertFalse(rendered.hasSyllableTiming)
        assertFalse(backing.hasSyllableTiming)
        assertEquals(KaraokeAlignment.End, rendered.alignment)
        assertEquals(KaraokeAlignment.End, backing.alignment)
        assertEquals("Lead", rendered.translation)
        assertEquals("zhuchang", rendered.phonetic)
        assertEquals(1200, backing.start)
        assertEquals(2800, backing.end)
    }

    @Test
    fun backingVocalsKeepTimingIndependentlyOfTheLead() {
        val line = LyricLine(
            1000, 3000, "主唱",
            background = LyricBackground("Oh", 1200, 2800,
                syllables = listOf(LyricSyllable("Oh", 1500, 2500))),
        )
        val rendered = model(line).lines.single() as KaraokeLine.MainKaraokeLine

        assertFalse(rendered.hasSyllableTiming)
        assertTrue(rendered.accompanimentLines!!.single().hasSyllableTiming)
    }

    @Test
    fun standaloneBackingVocalDoesNotAcquireWordTimingWhenCopied() {
        val rendered = model(LyricLine(1000, 3000, "(Oh)", isBackground = true))
            .lines.single() as KaraokeLine

        assertTrue(rendered is KaraokeLine.AccompanimentKaraokeLine)
        assertFalse(rendered.copy(translation = "和声").hasSyllableTiming)
    }

    private fun model(line: LyricLine) = Lyrics(
        source = LyricsSource.AMLL_TTML_DB,
        lines = listOf(line),
        syncLevel = SyncLevel.SYLLABLE,
    ).toSyncedLyrics(1)
}
