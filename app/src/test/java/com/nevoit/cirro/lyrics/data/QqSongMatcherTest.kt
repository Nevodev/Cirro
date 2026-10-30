package com.nevoit.cirro.lyrics.data

import com.nevoit.cirro.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QqSongMatcherTest {
    private val song = Song(1, "晴天", listOf("周杰伦"), "叶惠美", durationMs = 269_500)
    private val candidate = QqSongCandidate("97773", "晴天", listOf("周杰伦"), "叶惠美", 269_000)

    @Test fun prefersMatchingAlbumAndDuration() {
        assertEquals(candidate, QqSongMatcher.best(listOf(candidate.copy(id = "2", album = ""), candidate), song))
    }

    @Test fun rejectsOtherTitleArtistAndRecording() {
        for (wrong in listOf(
            candidate.copy(name = "夜曲"), candidate.copy(artists = listOf("其他歌手")),
            candidate.copy(name = "晴天 (Live)"), candidate.copy(durationMs = 249_000),
            candidate.copy(name = "晴天 (伴奏)"),
            candidate.copy(album = "演唱会 Live CD"),
        )) assertNull(QqSongMatcher.best(listOf(wrong), song))
    }

    @Test fun permitsMatchingLiveVersionAndPunctuation() {
        assertEquals(candidate.copy(name = "晴天（Live）"), QqSongMatcher.best(
            listOf(candidate.copy(name = "晴天（Live）")), song.copy(name = "晴天 (Live)"),
        ))
    }

    @Test fun cannotMatchWithoutIdentity() {
        assertNull(QqSongMatcher.best(listOf(candidate), song.copy(name = "")))
        assertNull(QqSongMatcher.best(listOf(candidate), song.copy(artists = emptyList())))
    }
}

class AppleSongMatcherTest {
    private val needs = Song(2078834440, "Needs", listOf("Tinashe"), "BB/ANG3L", durationMs = 146_791)
    private val needsCandidate = AppleSongCandidate("needs", "Needs", "Tinashe", durationMs = 146_791, hasLyrics = true)

    @Test fun needsRejectsDifferentSongsByTheSameArtistWithSimilarDurations() {
        for (name in listOf("Nobody Wanna Dance Anymore", "Pillow Fight", "No Broke Boys")) {
            assertNull(AppleSongMatcher.best(listOf(needsCandidate.copy(name = name, durationMs = 140_521)), needs))
        }
    }

    @Test fun matchingTitleAndDurationCannotSubstituteForTheArtist() {
        assertNull(AppleSongMatcher.best(listOf(needsCandidate.copy(artist = "Collective Soul")), needs))
    }

    @Test fun rejectsDifferentRecordingsAndExcessiveDurationDifferences() {
        for (candidate in listOf(
            needsCandidate.copy(name = "Needs (Mixed)"),
            needsCandidate.copy(name = "Needs (Live)"),
            needsCandidate.copy(durationMs = 117_102),
        )) assertNull(AppleSongMatcher.best(listOf(candidate), needs))
    }

    @Test fun ranksAllMatchingAliciaReleasesAndExcludesThePianoRecording() {
        val song = Song(16434100, "If I Ain't Got You", listOf("Alicia Keys"), "If I Ain't Got You", durationMs = 231_666)
        val studio = AppleSongCandidate("1714012402", song.name, "Alicia Keys", durationMs = 228_707, hasLyrics = true)
        val piano = studio.copy(id = "1451535999", name = "${song.name} (Piano & Vocal Version)", durationMs = 231_653)
        val radio = studio.copy(id = "1451535987", name = "${song.name} (Radio Edit)", durationMs = 231_667)
        val reissue = studio.copy(id = "255343130")
        assertEquals(listOf(radio, studio, reissue), AppleSongMatcher.ranked(listOf(studio, reissue, piano, radio), song))
    }

    @Test fun preservesChineseAliasesAndTraditionalArtistNames() {
        val song = Song(1, "Lemon", listOf("米津玄師"), "Lemon", durationMs = 255_000)
        val candidate = AppleSongCandidate("1", "Lemon (主题曲)", "米津玄师", durationMs = 255_000)
        assertEquals(candidate, AppleSongMatcher.best(listOf(candidate), song))
    }
}
