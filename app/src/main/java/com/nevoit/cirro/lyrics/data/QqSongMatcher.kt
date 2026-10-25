package com.nevoit.cirro.lyrics.data

import com.nevoit.cirro.data.model.Song
import java.text.Normalizer
import kotlin.math.abs

internal object QqSongMatcher {
    private val brackets = Regex("""\([^)]*\)|（[^）]*）|\[[^]]*]|【[^】]*】""")
    private val versions = listOf(
        Regex("""(?i)\blive\b|现场|演唱会"""),
        Regex("""(?i)\bremix\b|混音"""),
        Regex("""(?i)\binstrumental\b|伴奏|纯音乐"""),
        Regex("""(?i)\bacoustic\b|不插电"""),
        Regex("""(?i)\bcover\b|翻唱"""),
    )

    fun best(candidates: List<QqSongCandidate>, song: Song): QqSongCandidate? {
        val title = normalize(brackets.replace(song.name, ""))
        if (title.isEmpty()) return null
        val artists = song.artists.map(::normalize).filter(String::isNotEmpty)
        if (artists.isEmpty()) return null
        return candidates.mapNotNull { candidate ->
            if (candidate.id.isBlank() || normalize(brackets.replace(candidate.name, "")) != title) return@mapNotNull null
            if (versions.any {
                it.containsMatchIn("${song.name} ${song.album}") !=
                    it.containsMatchIn("${candidate.name} ${candidate.album}")
            }) return@mapNotNull null
            val matchesArtist = candidate.artists.map(::normalize).any { actual ->
                artists.any { want -> actual == want ||
                    (minOf(actual.length, want.length) >= 3 && (actual.contains(want) || want.contains(actual))) }
            }
            if (!matchesArtist) return@mapNotNull null
            val delta = if (song.durationMs > 0 && candidate.durationMs > 0) {
                abs(candidate.durationMs - song.durationMs)
            } else null
            if (delta != null && delta > maxOf(5_000L, song.durationMs / 50)) return@mapNotNull null
            val albumBonus = if (song.album.isNotBlank() && normalize(candidate.album) == normalize(song.album)) 2_000L else 0L
            candidate to ((delta ?: 10_000L) - albumBonus)
        }.minByOrNull { it.second }?.first
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().filter(Char::isLetterOrDigit)
}
