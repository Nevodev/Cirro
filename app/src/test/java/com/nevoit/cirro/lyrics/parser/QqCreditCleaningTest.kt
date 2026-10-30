package com.nevoit.cirro.lyrics.parser

import com.nevoit.cirro.lyrics.model.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QqCreditCleaningTest {
    @Test fun aliciaRoleLabelsAreRemovedFromQrcAndOrdinaryLrc() {
        val texts = listOf(
            "If I Ain't Got You - Alicia Keys/Usher",
            "Lyricist：Alicia Keys",
            "Composer：Alicia Keys",
            "Producer：Alicia Keys",
            "Some people live for the fortune",
        )
        for (qrc in listOf(true, false)) {
            val body = texts.mapIndexed { index, text ->
                if (qrc) "[${index * 6000},6000]$text(${index * 6000},6000)"
                else "[00:${(index * 6).toString().padStart(2, '0')}.00]$text"
            }.joinToString("\n")
            val lyrics = QrcLyricsParser.parse(
                "[ti:If I Ain't Got You]\n[ar:Alicia Keys/Usher]\n$body",
            ).getOrThrow()
            assertEquals(listOf(texts.last()), lyrics.lines.map { it.text })
            assertEquals(24000L, lyrics.lines.single().startMs)
        }
    }

    @Test fun roleLabelsInTheBodyRemainLyrics() {
        val lyrics = QrcLyricsParser.parse(
            "[0,1000]Opening(0,1000)\n[1000,1000]Lyricist: dreams(1000,1000)\n" +
                "[2000,1000]Composer: dreams(2000,1000)\n[3000,1000]Ordinary lyric(3000,1000)",
        ).getOrThrow()
        assertEquals(4, lyrics.lines.size)
    }

    @Test fun bareTitleBeforeCreditsDoesNotStopQqHeaderCleaning() {
        val lyrics = QrcLyricsParser.parse(
            "[ti:]\n[ar:]\n[0,1545]坚强笨女人(0,1545)\n" +
                "[1545,1073]作词：姜欣欣(1545,1073)\n" +
                "[2618,783]作曲：姜欣欣(2618,783)\n" +
                "[3402,1069]出品：姜欣欣(3402,1069)\n" +
                "[4471,221326]出品公司：北京福盛天成文化传媒有限公司(4471,221326)\n" +
                "[21630,3000]无论道路有多么曲折(21630,3000)",
            track = LyricTrackRef("坚强笨女人", listOf("那艺娜")),
        ).getOrThrow()
        assertEquals(listOf("无论道路有多么曲折"), lyrics.lines.map { it.text })
        assertEquals(21630L, lyrics.lines.single().startMs)
        assertEquals(24630L, lyrics.lines.single().endMs)
    }

    @Test fun bareTitleWithoutCreditsRemainsALyric() {
        for (source in listOf(LyricsSource.QQ, LyricsSource.NETEASE)) {
            val lyrics = LrcLyricsParser.parse(
                "[00:00.00]坚强笨女人\n[00:02.00]普通歌词",
                source,
                track = LyricTrackRef("坚强笨女人", listOf("那艺娜")),
            ).getOrThrow()
            assertEquals(listOf("坚强笨女人", "普通歌词"), lyrics.lines.map { it.text })
        }
    }

    @Test fun qqBareTitleInBodyRemainsALyric() {
        val lyrics = QrcLyricsParser.parse(
            "[0,1000]普通歌词(0,1000)\n[1000,1000]坚强笨女人(1000,1000)\n" +
                "[2000,1000]作词：梦境(2000,1000)\n[3000,1000]普通歌词(3000,1000)",
            track = LyricTrackRef("坚强笨女人", listOf("那艺娜")),
        ).getOrThrow()
        assertEquals(4, lyrics.lines.size)
    }

    @Test fun artistAliasesWithSlashesDoNotBreakHeaderMatching() {
        val lyrics = QrcLyricsParser.parse(
            "[ti:Song]\n[ar:Artist (Alias One/Alias Two)/Guest]\n" +
                "[0,500]Song - Artist(0,500)\n[500,500]Lyrics by：Writer(500,500)\n[1000,1000]正文(1000,1000)",
        ).getOrThrow()
        assertEquals(listOf("正文"), lyrics.lines.map { it.text })
    }

    @Test fun worldsAwayLyricsByAndTranslationRightsHeaderAreRemoved() {
        val lyrics = QrcLyricsParser.parse(
            "[ti:Worlds Away]\n[ar:Dabin/Trella]\n" +
                "[159,602]Worlds Away - Dabin/Trella(159,602)\n" +
                "[761,770]Lyrics by：Annika Wells/Dabin Lee/Lindsey Nicole Sweat(761,770)\n" +
                "[1531,1177]正文(1531,1177)",
            translation = "[00:00.15]QQ音乐享有本翻译作品的著作权\n[00:00.76]//\n[00:01.53]译文",
        ).getOrThrow()
        assertEquals(listOf("正文"), lyrics.lines.map { it.text })
        assertEquals("译文", lyrics.lines.single().translations.single().text)
    }

    @Test fun deepestWaterAcceptsPrimaryArtistAndRemovesGeneratedTranslationNotice() {
        val lyrics = QrcLyricsParser.parse(
            "[ti:Deepest Water (feat. Syd Taylor)]\n[ar:Dabin/Syd Taylor]\n" +
                "[0,460]Deepest Water (feat. Syd Taylor) - Dabin(0,460)\n" +
                "[461,613]Lyrics by：Dabin Lee/John Christopher Gomez/Stephen James Gomez/Sydney Taylor(461,613)\n" +
                "[1076,1888]Oh oh oh oh(1076,1888)\n[3172,3346]正文(3172,3346)",
            translation = "[00:00.00]以下歌词翻译由文曲大模型提供\n[00:00.46]//\n[00:01.07]//\n[00:03.17]译文",
        ).getOrThrow()
        assertEquals(listOf("Oh oh oh oh", "正文"), lyrics.lines.map { it.text })
        assertTrue(lyrics.lines[0].translations.isEmpty())
        assertEquals("译文", lyrics.lines[1].translations.single().text)
    }

    @Test fun qqTranslationNoticesAreRemovedEvenWithoutOtherCredits() {
        for (notice in listOf("QQ音乐享有本翻译作品的著作权", "以下歌词翻译由文曲大模型提供")) {
            val lyrics = LrcLyricsParser.parse(
                "[00:01.00]开场\n[00:02.00]正文", LyricsSource.QQ,
                translation = "[00:01.00]$notice\n[00:02.00]译文",
            ).getOrThrow()
            assertTrue(lyrics.lines[0].translations.isEmpty())
            assertEquals("译文", lyrics.lines[1].translations.single().text)
        }
    }

    @Test fun creditWordsInBodyAndBracketedVocalsRemain() {
        val lyrics = QrcLyricsParser.parse(
            "[ti:Song]\n[ar:Artist]\n[0,1000]普通歌词(0,1000)\n" +
                "[1000,1000]Lyrics by：dreams(1000,1000)\n[2000,1000](Echo)(2000,1000)",
        ).getOrThrow()
        assertEquals(3, lyrics.lines.size)
        assertEquals("(Echo)", lyrics.lines.last().text)
    }
}
