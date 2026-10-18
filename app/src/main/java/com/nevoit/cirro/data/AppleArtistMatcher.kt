package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.AppleArtistMatch
import com.nevoit.cirro.data.model.AppleArtistSongMatch
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.lyrics.data.AppleSearchResult
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal object AppleArtistMatcher {
    private const val RepresentativeCount = 3
    private const val MaxDurationDeltaMs = 3_000L
    private val brackets = Regex("[（(\\[【][^）)\\]】]*[）)\\]】]")
    private val albumSuffix = Regex("\\s*-\\s*(single|ep)\\s*$", RegexOption.IGNORE_CASE)
    private val versions = listOf(
        Regex("\\blive\\b|现场") to "live",
        Regex("\\bremix\\b|混音") to "remix",
        Regex("\\binstrumental\\b|\\bkaraoke\\b|伴奏") to "instrumental",
        Regex("\\bacoustic\\b|不插电") to "acoustic",
        Regex("\\bcover\\b|翻唱") to "cover",
        Regex("\\bdemo\\b") to "demo",
        Regex("\\bremaster(?:ed)?\\b|重制") to "remaster",
    )

    fun representatives(artistId: Long, songs: List<Song>): List<Song> {
        val remaining = songs.filter {
            artistId in it.artistIds && it.id > 0 && it.durationMs > 0 && title(it.name).isNotEmpty()
        }.sortedBy { !solo(it, artistId) }.distinctBy { title(it.name) }.toMutableList()
        val selected = mutableListOf<Song>()
        val albums = mutableSetOf<String>()
        while (remaining.isNotEmpty() && selected.size < RepresentativeCount) {
            val song = remaining.firstOrNull { album(it.album).let { key -> key.isNotEmpty() && key !in albums } }
                ?: remaining.first()
            selected += song
            albums += album(song.album)
            remaining.remove(song)
        }
        return selected
    }

    fun match(
        artistId: Long,
        artistName: String,
        results: List<Pair<Song, AppleSearchResult>>,
    ): AppleArtistMatch? {
        val names = buildSet {
            add(normalize(artistName))
            for ((song, _) in results) {
                song.artistIds.forEachIndexed { index, id ->
                    if (id == artistId) add(normalize(song.artists.getOrNull(index).orEmpty()))
                }
            }
            remove("")
        }
        val matches = results.groupBy { it.second.storefront }.mapNotNull { (storefront, songs) ->
            if (storefront.isBlank()) return@mapNotNull null
            val votes = mutableMapOf<String, MutableList<AppleArtistSongMatch>>()
            for ((song, found) in songs.distinctBy { title(it.first.name) }) {
                if (artistId !in song.artistIds || song.durationMs <= 0) continue
                val key = title(song.name)
                if (key.isEmpty()) continue
                val artists = mutableMapOf<String, String>()
                for (candidate in found.songs) {
                    if (candidate.id.isBlank() || title(candidate.name) != key ||
                        version(candidate.name) != version(song.name) || candidate.durationMs <= 0 ||
                        abs(candidate.durationMs - song.durationMs) > MaxDurationDeltaMs
                    ) continue
                    val sameAlbum = album(song.album).let { it.isNotEmpty() && it == album(candidate.album) }
                    val sameArtist = normalize(candidate.artist) in names ||
                        candidate.artists.any { normalize(it.name) in names }
                    if (!sameAlbum && !sameArtist) continue
                    for (artist in candidate.artists) {
                        if (artist.id.isEmpty() || !artist.id.all { it in '0'..'9' } ||
                            artist.id.all { it == '0' }
                        ) continue
                        // 两端均为独唱且专辑相同，允许艺人显示名被区域翻译；合作曲必须确认具体关联艺人。
                        if (normalize(artist.name) in names ||
                            (solo(song, artistId) && candidate.artists.size == 1 && (sameAlbum || sameArtist))
                        ) artists.putIfAbsent(artist.id, candidate.id)
                    }
                }
                for ((id, songId) in artists) {
                    votes.getOrPut(id) { mutableListOf() } += AppleArtistSongMatch(song.id, songId)
                }
            }
            // 同一首歌的重发不能重复投票；两个候选都得到多首作品支持时保留歧义。
            val confirmed = votes.mapValues { (_, evidence) -> evidence.distinctBy { it.appleSongId } }
                .filterValues { it.size >= 2 }
            confirmed.entries.singleOrNull()?.let { (id, evidence) ->
                AppleArtistMatch(id, storefront, evidence)
            }
        }
        return matches.takeIf { it.map { match -> match.appleArtistId }.distinct().size == 1 }
            ?.firstOrNull()
    }

    private fun solo(song: Song, artistId: Long) =
        song.artistIds == listOf(artistId) && song.artists.size == 1

    private fun normalize(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun title(text: String) = normalize(brackets.replace(text, ""))

    private fun album(text: String) = normalize(albumSuffix.replace(text, ""))

    private fun version(text: String): Set<String> {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return versions.filter { it.first.containsMatchIn(folded) }.map { it.second }.toSet()
    }
}
