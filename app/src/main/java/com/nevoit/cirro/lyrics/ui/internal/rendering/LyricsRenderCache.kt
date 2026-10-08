package com.nevoit.cirro.lyrics.ui.internal.rendering

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLyrics

/**
 * 歌词位图的**单槽**缓存 —— 只留当前这一首的 [LyricsRenderResources]。
 *
 * 位图是整曲一次性光栅化的产物（每行一个 `TextAtlas`，页面上限 2048²），一首 60 行的歌就是
 * 几十 MB **native** 图形内存。它既是打开面板最贵的一段，也是掉帧尖峰的来源（上百个
 * `ImageBitmap` 分配 + CPU 绘制集中在一瞬）。
 *
 * 之前它跟着**面板**的组合生命周期走 —— 关掉面板就没了，再打开整曲重做一遍。现在挂到
 * **播放页**的生命周期上：页内关掉再打开 = 零成本复用；播放页一关，实例随组合一起被回收
 * （见 [LocalLyricsRenderCache]，由播放页 `remember` 并 provide）。
 *
 * 只留一条，换歌即替换。留多首会直接把内存顶爆，而单条的上限本来就和「面板开着」时一样，
 * 属于这份缓存不额外增加常驻占用。
 *
 * 复用是安全的：[LyricsRenderResources] 里的逐帧可变状态只有 [RowRenderState] 的扫过光标
 * 与一支复用 `Paint`，前者自带时间回退重算（见 `sweepCenter`），后者每次绘制前都重设。
 *
 * ⚠️ 单槽的前提是**同一时刻只有一个场景在用它**。`KaraokeLineText`（单行独立渲染，目前没有
 * 调用点）也走 `rememberLyricsScene`，将来若要把它放进播放页，它会用自己的 `prepared` 把这一槽
 * 顶掉 —— 那时得给它单独开一份缓存，或者把这里换成按持有者分槽。
 */
internal class LyricsRenderCache {

    private var key: Key? = null
    private var resources: LyricsRenderResources? = null

    /** 命中返回同一份资源；未命中返回 null，由调用方去后台重建。 */
    @Synchronized
    fun get(key: Key): LyricsRenderResources? = resources?.takeIf { key == this.key }

    /** 写入即替换上一条（单槽），旧的那份交给 GC。 */
    @Synchronized
    fun put(key: Key, value: LyricsRenderResources) {
        this.key = key
        this.resources = value
    }

    /**
     * [prepared] 按**引用**比较（`PreparedLyrics` 没实现 `equals`），这正是想要的：
     * `LyricsLayoutCache` 命中时给回的就是同一个实例，于是「排版没变」直接等价于「位图还能用」，
     * 排版一旦重做位图必然跟着重做。
     *
     * 宽度/字重之类已经烘进 [prepared] 的几何里了；再带上 [density] 是因为字号换算也参与光栅，
     * [color] 同理 —— 颜色是**烘焙进位图**的，换了必须重做。
     */
    internal class Key(
        val prepared: PreparedLyrics,
        val color: Color,
        val density: Density,
        val direction: LayoutDirection,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Key &&
                prepared === other.prepared &&
                color == other.color &&
                density == other.density &&
                direction == other.direction

        override fun hashCode(): Int {
            var result = System.identityHashCode(prepared)
            result = 31 * result + color.hashCode()
            result = 31 * result + density.hashCode()
            result = 31 * result + direction.hashCode()
            return result
        }
    }
}

/**
 * 把 [LyricsRenderCache] 从播放页根部送到歌词场景里。
 *
 * 为什么用 CompositionLocal 而不是一路加参数：`KaraokeLyricsView` 是**搬进来**的公开 API，
 * 往它的签名里加一个 `internal` 类型的参数是编译不过的（公开函数不能暴露内部类型），而把那个
 * 类型降成 public 又会顺着 `LyricsRenderResources` 一路传染下去。挂在环境上，那些签名一个都不用动，
 * 上游升级时的 diff 也保持干净。
 *
 * 用 `static` 版：实例由播放页 `remember` 出来、从不变化，不需要动态订阅。
 */
internal val LocalLyricsRenderCache = staticCompositionLocalOf<LyricsRenderCache?> { null }
