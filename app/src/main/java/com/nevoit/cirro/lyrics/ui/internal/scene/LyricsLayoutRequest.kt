package com.nevoit.cirro.lyrics.ui.internal.scene

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.nevoit.cirro.lyrics.core.model.SyncedLyrics
import com.nevoit.cirro.lyrics.ui.internal.preparation.LyricsLayoutCache
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLyrics
import com.nevoit.cirro.lyrics.ui.preparation.prepareLyrics
import com.nevoit.cirro.lyrics.ui.profile.LyricsProfile

/** Layout invalidation inputs. Paint color and playback never invalidate text geometry. */
internal data class LyricsLayoutRequest(
    val lyrics: SyncedLyrics,
    val profiles: List<LyricsProfile>,
    val measurer: TextMeasurer,
    val normalStyle: TextStyle,
    val accompanimentStyle: TextStyle,
    val phoneticStyle: TextStyle,
    val width: Float,
    val density: Density,
    val direction: LayoutDirection,
) {
    /**
     * 排版，走 [LyricsLayoutCache]。
     *
     * 面板每次打开都会走到这里（`toSyncedLyrics` 出来的是新实例，但结构相等），命中缓存就
     * 直接复用上一份几何，跳过整曲测量。缓存只装 `PreparedLyrics`，不装位图。
     */
    fun prepare(): PreparedLyrics {
        val key =
            LyricsLayoutCache.Key(
                lyrics = lyrics,
                profiles = profiles,
                width = width,
                density = density.density,
                fontScale = density.fontScale,
                direction = direction,
                normalStyle = normalStyle,
                accompanimentStyle = accompanimentStyle,
                phoneticStyle = phoneticStyle,
            )
        LyricsLayoutCache.get(key)?.let {
            return it
        }
        val prepared =
            prepareLyrics(
                lyrics, profiles, measurer, normalStyle, accompanimentStyle, phoneticStyle,
                width, density.density, true, fontScale = density.fontScale,
            )
        LyricsLayoutCache.put(key, prepared)
        return prepared
    }
}
