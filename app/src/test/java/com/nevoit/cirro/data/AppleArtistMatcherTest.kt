package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.lyrics.data.AppleArtistCandidate
import com.nevoit.cirro.lyrics.data.AppleSearchResult
import com.nevoit.cirro.lyrics.data.AppleSongCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleArtistMatcherTest {
    private val first = song(1, "First", "Album A")
    private val second = song(2, "Second", "Album B")

    @Test
    fun twoDifferentWorksConfirmAnArtist() {
        val match = match(first to found(first), second to found(second))!!
        assertEquals("100", match.appleArtistId)
        assertEquals(listOf(1L, 2L), match.songs.map { it.neteaseSongId })
    }

    @Test
    fun aNameOrSingleSongCannotConfirmAnArtist() {
        assertNull(match(first to found(first)))
        assertNull(match(first to found(first), second to found(second, id = "200")))
    }

    @Test
    fun repeatedReleasesDoNotCountAsIndependentEvidence() {
        val reissue = first.copy(id = 3, album = "Greatest Hits")
        assertNull(match(first to found(first), reissue to found(reissue)))
        assertNull(match(first to found(first), second to found(second, songId = first.id.toString())))
    }

    @Test
    fun conflictingArtistsRemainUnmatched() {
        fun ambiguous(song: Song): AppleSearchResult {
            val found = found(song)
            return found.copy(songs = found.songs + found.songs.single().copy(
                id = "other-${song.id}", artists = listOf(AppleArtistCandidate("200", "Artist")),
            ))
        }
        assertNull(match(first to ambiguous(first), second to ambiguous(second)))
    }

    @Test
    fun wrongVersionsAndDurationAreRejectedEvenWithLyrics() {
        for (candidate in listOf(
            found(second).songs.single().copy(name = "Second (Live)", hasLyrics = true),
            found(second).songs.single().copy(name = "Second (Remix)"),
            found(second).songs.single().copy(durationMs = second.durationMs + 3_001),
            found(second).songs.single().copy(durationMs = 0),
        )) {
            assertNull(match(first to found(first), second to AppleSearchResult(listOf(candidate), "cn", false)))
        }
    }

    @Test
    fun translatedSoloArtistRequiresMatchingAlbum() {
        fun translated(song: Song) = found(song).let { result ->
            result.copy(songs = result.songs.map { it.copy(
                artist = "艺人", artists = listOf(AppleArtistCandidate("100", "艺人")),
            ) })
        }
        assertEquals("100", match(first to translated(first), second to translated(second))?.appleArtistId)
        val otherAlbum = translated(second).let { it.copy(songs = it.songs.map { song -> song.copy(album = "Other") }) }
        assertNull(match(first to translated(first), second to otherAlbum))
    }

    @Test
    fun collaborationsResolveTheNamedArtistRatherThanTheFirstRelationship() {
        val artists = listOf(AppleArtistCandidate("200", "Other"), AppleArtistCandidate("100", "Artist"))
        fun collaboration(song: Song) = song.copy(artistIds = listOf(99, 42), artists = listOf("Other", "Artist"))
        fun result(song: Song) = found(song).let { it.copy(songs = it.songs.map { song -> song.copy(artists = artists) }) }
        assertEquals("100", match(
            collaboration(first) to result(first), collaboration(second) to result(second),
        )?.appleArtistId)
        val anonymous = result(second).let { it.copy(songs = it.songs.map { song ->
            song.copy(artists = listOf(AppleArtistCandidate("200", "Other")))
        }) }
        assertNull(match(collaboration(first) to result(first), collaboration(second) to anonymous))
    }

    @Test
    fun storefrontsCannotSupplyHalfOfTheEvidenceEach() {
        assertNull(match(first to found(first), second to found(second).copy(storefront = "us")))
    }

    @Test
    fun representativesPreferSoloTracksAndDifferentAlbumsAndRejectUnknownOwnership() {
        val reissue = first.copy(id = 3)
        val collaborator = song(4, "Together", "Album C").copy(artistIds = listOf(42, 99), artists = listOf("Artist", "Other"))
        val unknown = song(5, "Unknown", "Album D").copy(artistIds = emptyList())
        assertEquals(listOf(1L, 2L, 4L), AppleArtistMatcher.representatives(
            42, listOf(collaborator, first, reissue, second, unknown),
        ).map { it.id })
    }

    @Test
    fun descriptiveSuffixesDoNotHideAnOtherwiseExactWork() {
        val result = found(second).let { it.copy(songs = it.songs.map { song ->
            song.copy(name = "Second (电影主题曲)", album = "Album B - Single")
        }) }
        assertEquals("100", match(first to found(first), second to result)?.appleArtistId)
    }

    private fun match(vararg results: Pair<Song, AppleSearchResult>) =
        AppleArtistMatcher.match(42, "Artist", results.toList())

    private fun song(id: Long, name: String, album: String) =
        Song(id, name, listOf("Artist"), album, durationMs = 180_000, artistIds = listOf(42))

    private fun found(song: Song, id: String = "100", songId: String = song.id.toString()) = AppleSearchResult(
        listOf(AppleSongCandidate(songId, song.name, "Artist", song.album, song.durationMs,
            artists = listOf(AppleArtistCandidate(id, "Artist")))),
        "cn", false,
    )
}
