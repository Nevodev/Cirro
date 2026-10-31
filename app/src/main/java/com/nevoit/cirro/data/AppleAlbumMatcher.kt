package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.lyrics.data.AppleAlbum
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

internal object AppleAlbumMatcher {
    private val suffix = Regex("\\s*-\\s*(single|ep)\\s*$", RegexOption.IGNORE_CASE)
    private val featuredCredit = Regex(
        "[（(\\[【]\\s*(?:feat(?:uring)?\\.?|ft\\.?|with)\\s+[^）)\\]】]+[）)\\]】]",
        RegexOption.IGNORE_CASE,
    )
    private val trailingFeaturedCredit = Regex("\\s+(?:feat(?:uring)?\\.?|ft\\.?)\\s+.+$", RegexOption.IGNORE_CASE)
    private val videoEdition = Regex("\\s*\\[video deluxe]\\s*$", RegexOption.IGNORE_CASE)
    private val catalogTrackLabel = Regex(
        """\s*[（(\[]\s*(?:bonus track|pop mix)\s*[）)\]]""",
        RegexOption.IGNORE_CASE,
    )

    fun candidates(info: CollectionInfo, albums: List<AppleAlbum>): List<AppleAlbum> =
        albums.distinctBy { it.id }.filter {
            it.id.isNotEmpty() && it.id.all { char -> char in '0'..'9' } &&
                title(it.name).isNotEmpty() && title(it.name) == title(info.title) &&
                normalize(it.artist).isNotEmpty() &&
                normalize(it.artist) in setOf(normalize(info.author), normalize(info.primaryArtistName)) &&
                (info.trackCount <= 0 || it.trackCount >= info.trackCount ||
                    canOmitTrailingBonusTracks(info.trackCount, it.trackCount))
        }

    fun match(info: CollectionInfo, songs: List<Song>, albums: List<AppleAlbum>): AppleAlbum? {
        if (songs.isEmpty() || songs.any { it.durationMs <= 0 || trackTitle(it.name).isEmpty() } ||
            (info.trackCount > 0 && songs.size != info.trackCount)
        ) return null
        val verified = candidates(info, albums).filter { album ->
            if (album.trackCount != album.tracks.size) return@filter false
            val source = songs.map { Track(it.name, it.durationMs) }
            val target = album.tracks.map { Track(it.name, it.durationMs) }
            if (source.size == target.size) tracksMatch(source, target, 3_000)
            else {
                // Regional releases can append bonus songs to the complete standard album.
                // Require every standard track in order; arbitrary subsets cannot prove identity.
                canOmitTrailingBonusTracks(source.size, target.size) &&
                    source.take(target.size).zip(target).all { (a, b) ->
                        trackTitle(a.name) == trackTitle(b.name) && abs(a.durationMs - b.durationMs) <= 3_000
                    }
            }
        }
        if (verified.isEmpty()) return null
        // 曲目不等价时用发行日消歧；等价重发版可以共享封面，优先选择含动态封面的版本。
        val date = info.updateTimeMs.takeIf { it > 0 }?.let {
            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
        }
        fun equivalent(albums: List<AppleAlbum>): Boolean = albums.all { album ->
            tracksMatch(
                album.tracks.map { Track(it.name, it.durationMs) },
                albums.first().tracks.map { Track(it.name, it.durationMs) },
                1_000,
            )
        }
        val pool = if (equivalent(verified)) verified else {
            verified.filter { it.releaseDate == date }.takeIf { it.isNotEmpty() && equivalent(it) } ?: return null
        }
        return pool.sortedWith(compareByDescending<AppleAlbum> { !it.videoUrl.isNullOrBlank() }
            .thenByDescending { it.releaseDate == date }.thenBy { it.id }).first()
    }

    private fun title(text: String) = normalize(suffix.replace(videoEdition.replace(text, ""), ""))

    private fun canOmitTrailingBonusTracks(sourceCount: Int, targetCount: Int): Boolean =
        targetCount >= 10 && sourceCount - targetCount in 1..2 && targetCount * 5 >= sourceCount * 4

    private data class Track(val name: String, val durationMs: Long)

    private fun tracksMatch(source: List<Track>, target: List<Track>, toleranceMs: Long): Boolean {
        if (source.size != target.size) return false
        fun sameDuration(a: Track, b: Track) = a.durationMs > 0 && b.durationMs > 0 &&
            abs(a.durationMs - b.durationMs) <= toleranceMs
        val namedAnchors = source.zip(target).count { (a, b) ->
            normalize(a.name).isNotEmpty() && trackTitle(a.name) == trackTitle(b.name) && sameDuration(a, b)
        }
        val remaining = target.indices.toMutableSet()
        // 纯符号曲名在不同平台可能用不同字形；仅在同位置、时长一致且有至少两首文字曲目佐证时兼容。
        for ((position, track) in source.withIndex().sortedBy { normalize(it.value.name).isNotEmpty() }) {
            val name = trackTitle(track.name)
            val symbolic = normalize(track.name).isEmpty()
            val index = remaining.filter { candidate ->
                val other = target[candidate]
                sameDuration(track, other) &&
                    if (symbolic) {
                        candidate == position && normalize(other.name).isEmpty() &&
                            (name == trackTitle(other.name) || (namedAnchors >= 2 && trackTitle(other.name).isNotEmpty()))
                    } else name == trackTitle(other.name)
            }.minByOrNull { abs(track.durationMs - target[it].durationMs) } ?: return false
            remaining.remove(index)
        }
        return true
    }

    private fun trackTitle(text: String): String {
        // 移除合作艺人署名和曲库附注，保留 live、remix、extended 等录音版本标记。
        val stripped = catalogTrackLabel.replace(
            trailingFeaturedCredit.replace(featuredCredit.replace(text, ""), ""), "",
        )
        val normalized = normalize(stripped)
        if (normalized.isNotEmpty()) return normalized
        return Normalizer.normalize(stripped, Normalizer.Form.NFKC)
            .filterNot { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() || it in '\uFE00'..'\uFE0F' }
    }

    private fun normalize(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
