package com.nevoit.cirro.lyrics.ui.internal.preparation

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import com.nevoit.cirro.lyrics.core.model.SyncedLyrics
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLyrics
import com.nevoit.cirro.lyrics.ui.profile.LyricsProfile

/**
 * 歌词排版结果的跨会话缓存。
 *
 * `PreparedLyrics` 构造完就**只读**了——逐帧可变状态都在 `LyricsRenderResources` /
 * `RowRenderState` 里——所以同一份排版可以安全地喂给后来的会话。面板每次打开都会重新
 * `toSyncedLyrics`，但那是 data class，结构相等，于是「同一首歌 + 同样的宽度/密度/样式」
 * 会命中同一条目，跳过整曲文字测量。
 *
 * **只缓存排版，不缓存位图。** 位图是每行一个 `TextAtlas`（上限 2048²），一首 60 行的歌
 * 就是几十 MB **native** 图形内存，留几首会被 LMK 干掉；而且排版本来才是打开时最贵的那段。
 *
 * 失效两条：
 * - 系统字体异步解析完成 → [PreparedLyrics.hasStaleFonts] 命中，条目作废、让调用方重排；
 * - 超过 [MaxEntries] 条 → 按最近最少使用淘汰。
 */
internal object LyricsLayoutCache {

    /** 手机内存经不起多首全曲 `TextLayoutResult` 常驻。3 条够覆盖「上一首 / 这首 / 下一首」。 */
    private const val MaxEntries = 3

    private val entries =
        object : LinkedHashMap<Key, PreparedLyrics>(8, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PreparedLyrics>) =
                size > MaxEntries
        }

    /**
     * 命中且字体未失效才返回。
     *
     * 失效的条目**必须**在这里就地丢掉并返回 null，交给调用方重排 —— 若把失效条目原样返回，
     * `rememberLyricsScene` 那条 `hasStaleFonts()` 轮询会拿到同一份失效结果，空转成死循环。
     */
    @Synchronized
    fun get(key: Key): PreparedLyrics? {
        val cached = entries[key] ?: return null
        if (cached.hasStaleFonts()) {
            entries.remove(key)
            return null
        }
        return cached
    }

    @Synchronized
    fun put(key: Key, value: PreparedLyrics) {
        entries[key] = value
    }

    /**
     * 排版输入的全集。
     *
     * 不进键的三类：颜色与播放位置（只影响绘制，不影响几何）、可见性开关（`showPhonetic`
     * 由调用方固定传 true）、`TextMeasurer`（`TextLayoutResult` 自带全部几何，换 measurer
     * 不影响已排好的结果，且 `rememberTextMeasurer(cacheSize = 0)` 每处都是新实例）。
     *
     * `lyrics` 直接按 data class 结构相等比较：比手搓「行数 + 首尾时间」的指纹更准，代价是一次
     * 全曲字符串 hash——相对重新测量可以忽略。
     */
    internal data class Key(
        val lyrics: SyncedLyrics,
        val profiles: List<LyricsProfile>,
        val width: Float,
        val density: Float,
        val fontScale: Float,
        val direction: LayoutDirection,
        val normalStyle: TextStyle,
        val accompanimentStyle: TextStyle,
        val phoneticStyle: TextStyle,
    )
}
