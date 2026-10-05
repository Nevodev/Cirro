package com.nevoit.cirro.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.nevoit.cirro.data.SongUnavailableException
import com.nevoit.cirro.data.model.Song

/**
 * 播放条目用一个私有 scheme 占位，真实直链等播放器真要读数据时才换。
 *
 * 为什么非要绕这一下：网易云的直链只有 20 分钟有效期（响应里的 `expi`）。如果在建队列时
 * 就把直链烧进 MediaItem，一首排在半小时后才轮到的歌，等到它时链接早就死了。
 * 换成占位地址之后还有个附带好处——每次 load 重试都会重新换一次链接，链接过期可以自愈。
 */
internal const val SongScheme = "cirro"
internal const val SongHost = "song"

internal fun songUri(id: Long): Uri =
    Uri.Builder()
        .scheme(SongScheme)
        .authority(SongHost)
        .appendPath(id.toString())
        .build()

internal fun Uri.songIdOrNull(): Long? =
    if (scheme == SongScheme && authority == SongHost) lastPathSegment?.toLongOrNull() else null

internal fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(songUri(id))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(name)
            .setArtist(artistText)
            .setAlbumTitle(album)
            // 元数据先给时长，别等 READY：迷你条的进度条要靠它才能提前算出来
            .setDurationMs(durationMs.takeIf { it > 0 } ?: C.TIME_UNSET)
            .apply { if (cover.isNotEmpty()) setArtworkUri(cover.toUri()) }
            .build()
    )
    .build()

/**
 * 顺着异常链找"这首歌确定放不了"。
 *
 * media3 会把数据源抛出的异常包上好几层，不能只看最外层。
 */
internal fun Throwable.songUnavailable(): SongUnavailableException? {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is SongUnavailableException) return cause
        cause = cause.cause
    }
    return null
}
