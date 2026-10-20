package com.nevoit.cirro.data

import coil3.Extras
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.map.Mapper
import coil3.size.Size
import coil3.size.pxOrElse

/** 网易云图片 CDN 的尺寸上限。再大在手机上也只是白吃流量和内存。 */
private const val CoverSidePx = 1024

/**
 * 请求尺寸 → 出图尺寸的档位（向上取一档）。
 *
 * 以前不管画多大一律要 [CoverSidePx]：详情页里一行 40dp 的封面（3x 屏上 120px）也要下一个
 * 1024 的图，一铺开就是十几二十个两百来 KB 的文件同时下载、解码、写磁盘缓存——正好顶在
 * 进场动画和列表落地那一刻上。
 *
 * 下限 256 是有意的：取色（`CoverPalette`）走同一个 Mapper，它是拿这张图算整页主色的，
 * 太小会让种子偏色。
 */
private val CoverSideSteps = intArrayOf(256, 384, 512, 768, CoverSidePx)

/**
 * 要原图的口子：`ImageRequest.Builder(context).data(url).apply { extras[OriginalCoverKey] = true }`。
 *
 * 不传就是按目标尺寸取档；只有确实需要原始分辨率的场景（导出、放大看）才用它。
 */
val OriginalCoverKey = Extras.Key(default = false)

/**
 * 全站的网易云图片默认由这条 Mapper 补尺寸参数。
 *
 * 放在 Coil 的 Mapper 里而不是各调用点：`AsyncImage`、`context.imageLoader`（取色预热）以及
 * 以后新写的页面都走这一条，不用谁记得自己拼。只认网易云的图片 CDN，Apple 的 `mzstatic.com`
 * 之流原样放过；上游已经写了 `param=` 的不覆盖。
 *
 * 尺寸从 `options.size` 里读——那是 Coil 解析完布局约束的真实目标像素（见 `RealImageLoader`），
 * 所以不用调用点交代画多大。
 */
fun installImageLoader() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components {
                add(Mapper<String, String> { data, options ->
                    if (options.extras[OriginalCoverKey] == true) data
                    else sizedCoverUrl(data, coverSideFor(options.size))
                })
            }
            .build()
    }
}

/** 目标像素边长 → 档位。尺寸未知（`Size.ORIGINAL`）或本来就够大时给上限。 */
internal fun coverSideFor(size: Size): Int {
    val target = maxOf(size.width.pxOrElse { 0 }, size.height.pxOrElse { 0 })
    if (target <= 0 || target >= CoverSidePx) return CoverSidePx
    return CoverSideSteps.firstOrNull { it >= target } ?: CoverSidePx
}

/** 只给网易云图片 CDN 补尺寸参数，别的一律原样返回。 */
internal fun sizedCoverUrl(url: String, side: Int = CoverSidePx): String {
    if (url.contains("param=")) return url

    val host = url.substringAfter("//", "")
        .substringBefore('/')
        .substringBefore(':')
        .lowercase()
    val isNcmCdn = host == "126.net" || host.endsWith(".126.net") ||
        host == "127.net" || host.endsWith(".127.net")
    if (!isNcmCdn) return url

    val separator = if ('?' in url) '&' else '?'
    return "$url${separator}param=${side}y$side"
}
