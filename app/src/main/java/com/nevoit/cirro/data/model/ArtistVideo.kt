package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

/**
 * 艺人页「音乐视频」里的一条。
 *
 * [width] / [height] 是**视频画面**的真实宽高（原生层取最高一档 mp4 的 `width`/`height`），
 * 卡片按它算缩略图比例；[cover] 的比例可能跟视频不一样（竖版视频的封面常被裁成 16:9），
 * 所以只当兜底用。[url] 是直链，一小时过期，拿不到就是空串。
 */
@Serializable
data class ArtistVideo(
    val id: Long,
    val title: String = "",
    val cover: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val playCount: Long = 0L,
    val publishTimeMs: Long = 0L,
    val url: String = "",
)
