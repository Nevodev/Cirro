package com.nevoit.cirro.lyrics.parser

import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.model.LyricsSource
import com.nevoit.cirro.lyrics.model.SyncLevel
import com.nevoit.cirro.lyrics.ui.toSyncedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcBackgroundTest {
    @Test fun worldsAwayAlbumEchoTimingsStayAsIndependentMainRows() {
        // Timing pattern from Netease 2692557265 (Aura Park); text replaced for the fixture.
        val lyrics = LrcLyricsParser.parse(
            "[00:51.717]Lead\n[00:55.189](Echo one)\n[00:57.866](Echo two)\n[01:01.705](Echo three)\n" +
                "[01:04.371]Lead\n[01:08.058](Echo one)\n[01:10.670](Echo two)\n[01:14.195](Echo three)\n[01:17.107]Lead",
            LyricsSource.NETEASE,
        ).getOrThrow()
        assertEquals(9, lyrics.lines.size)
        assertTrue(lyrics.lines.none { it.isBackground })
        assertEquals(listOf(55189L, 57866L, 61705L), lyrics.lines.subList(1, 4).map { it.startMs })
        assertEquals(listOf(57866L, 61705L, 64371L), lyrics.lines.subList(1, 4).map { it.endMs })
        assertTrue(lyrics.toSyncedLyrics(2692557265).lines.all { it is KaraokeLine.MainKaraokeLine })
    }

    @Test fun consecutiveBracketedLinesRemainOrdinaryLyrics() {
        for (source in listOf(LyricsSource.NETEASE, LyricsSource.LRCLIB, LyricsSource.QQ)) {
            val lyrics = LrcLyricsParser.parse(
                "[00:01.00]Lead\n[00:02.00](First echo)\n[00:03.00](Second echo)\n[00:04.00]（Third echo）\n[00:05.00]Next lead",
                source,
            ).getOrThrow()
            assertEquals(5, lyrics.lines.size)
            assertEquals(SyncLevel.LINE, lyrics.syncLevel)
            assertTrue(lyrics.lines.none { it.isBackground || it.background != null })
            assertEquals("(First echo)", lyrics.lines[1].text)
            assertTrue(lyrics.toSyncedLyrics(1).lines.all { it is KaraokeLine.MainKaraokeLine })
        }
    }
}
