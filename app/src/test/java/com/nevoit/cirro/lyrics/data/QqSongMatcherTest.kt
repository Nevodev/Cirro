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
