package com.nevoit.cirro.data

import coil3.Extras
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.map.Mapper

/** 网易云图片 CDN 的尺寸上限。再大在手机上也只是白吃流量和内存。 */
private const val CoverSidePx = 1024

/**
 * 要原图的口子：`ImageRequest.Builder(context).data(url).apply { extras[OriginalCoverKey] = true }`。
 *
 * 不传就是默认的 `?param=1024y1024`；只有确实需要原始分辨率的场景（导出、放大看）才用它。
 */
val OriginalCoverKey = Extras.Key(default = false)

/**
 * 全站的网易云图片默认都带 `?param=1024y1024`。
 *
 * 放在 Coil 的 Mapper 里而不是各调用点：`AsyncImage`、`context.imageLoader`（取色预热）以及
 * 以后新写的页面都走这一条，不用谁记得自己拼。只认网易云的图片 CDN，Apple 的 `mzstatic.com`
 * 之流原样放过；上游已经写了 `param=` 的不覆盖。
 */
fun installImageLoader() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components {
                add(Mapper<String, String> { data, options ->
                    if (options.extras[OriginalCoverKey] == true) data else sizedCoverUrl(data)
                })
            }
            .build()
    }
}

/** 只给网易云图片 CDN 补尺寸参数，别的一律原样返回。 */
internal fun sizedCoverUrl(url: String): String {
    if (url.contains("param=")) return url

    val host = url.substringAfter("//", "")
        .substringBefore('/')
        .substringBefore(':')
        .lowercase()
    val isNcmCdn = host == "126.net" || host.endsWith(".126.net") ||
        host == "127.net" || host.endsWith(".127.net")
    if (!isNcmCdn) return url

    val separator = if ('?' in url) '&' else '?'
    return "$url${separator}param=${CoverSidePx}y$CoverSidePx"
}
