package com.nevoit.cirro.data.model

/**
 * 音质档位。
 *
 * [wire] 是请求直链时传的 `level`，也是响应里回显的值。⚠️ **服务端会自己降级**：实测
 * 请求 `hires` 得 `lossless`、请求 `dolby` 得 `jyeffect`，所以"实得档位"一律看响应回显的
 * `level`，别信请求参数。实际档位不在 [supported] 中时不能播放；杜比请求会重新尝试普通档位。
 *
 * [bitrate] 是网易云给这一档标的**虚拟码率**（bps），跟真实码率不等同，界面显示
 * "约 xxx kbps" 用。取自官方 `rn-audio-quality-layer` 的常量表。
 *
 * 枚举保留可识别的服务端响应；应用允许请求的档位由 [supported] 限定。
 * 杜比与立体声分别展示，不参与同一条音质高低排序。
 *
 * ⚠️ 别把这里当成"用户能播什么"：能不能播取决于**这首歌有没有这一档**（问
 * [SongQualityInfo]）和**账号权益**（只能由直链响应回答）。
 */
enum class AudioQuality(
    val wire: String,
    val displayName: String,
    val alias: String,
    val bitrate: Int,
) {
    DOLBY("dolby", "杜比全景声", "Dolby Atmos", 2_999_000),
    VIVID("vivid", "臻音全景声", "Audio Vivid", 5_999_000),
    SKY("sky", "沉浸环绕声", "Surround Audio", 4_099_000),
    JY_MASTER("jymaster", "超清母带", "Master", 4_999_000),
    JY_EFFECT("jyeffect", "高清臻音", "Spatial Audio", 3_999_000),
    HIRES("hires", "高解析度无损", "Hi-Res", 1_999_000),
    LOSSLESS("lossless", "无损", "SQ", 999_000),
    EXHIGH("exhigh", "极高", "HQ", 320_000),
    HIGHER("higher", "较高", "", 192_000),
    STANDARD("standard", "标准", "", 128_000),
    ;

    /** 列表里的副标题，如 "SQ · 约 999 kbps"；别名空的档（较高/标准）只给码率。 */
    val spec: String
        get() {
            val kbps = "约 ${bitrate / 1000} kbps"
            return if (alias.isEmpty()) kbps else "$alias · $kbps"
        }

    companion object {
        val supported: List<AudioQuality> = listOf(STANDARD, EXHIGH, LOSSLESS, HIRES, DOLBY)

        /** 认不出来的 `level` 返回 null，调用方自己决定兜底（一般退回 [STANDARD]）。 */
        fun fromWire(value: String): AudioQuality? = entries.find { it.wire == value }
    }
}
