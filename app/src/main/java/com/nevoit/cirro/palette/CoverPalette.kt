package com.nevoit.cirro.palette

import android.content.Context
import android.util.Log
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 取色用的封面缩到多大。量化不需要细节，96px 足够，也快。 */
private const val SamplePx = 96

/** 量化最多聚多少簇。官方默认 64，封面主色绰绰有余。 */
private const val MaxColors = 64

/** 提取链路的日志 tag，logcat 过滤它就知道断在哪一环。 */
private const val Tag = "CoverPalette"

/**
 * 种子重建色带前的彩度上限（HCT 的 C）。
 *
 * tone 只钉明度，彩度跟着色相走——橙红这类暖色相在中间明度段 sRGB 能显示的彩度
 * 远高于蓝紫，同档位下观感爆亮。这里把种子钳到统一上限再生成色带，各色相拉平。
 * 想更素调小、想更艳调大。
 */
private const val SeedChromaCap = 45.0

/**
 * 种子明度（HCT tone，0–100）的深浅分界线：低于它 dark=true，详情页**恒定**深色页
 * （暗封面压白字怎么都不糊）；在线上 dark=false 即 auto——详情页跟系统颜色模式走。
 * 想更早/更晚切深色调这里。
 */
private const val DarkSeedTone = 40.0

/**
 * 一张封面的取色结果：种子色 + primary 色带 + 明暗判定。
 *
 * 只做三件事：[seed] 是官方 Score 从量化簇里挑出的种子（ARGB）；[primary] 是种子进
 * HCT 后生成的 tonal 色带——色相锁死、彩度被钳制、明度任意档可取；[dark] 是种子的
 * 明暗判定（取色时算好，消费方直接用）。没有 secondary / tertiary，也不拼整套
 * ColorScheme：配色自己拿档位试。
 *
 * **档位语义**（M3 标准）：T40 ≈ 主色（浅色主题里配 T100 文字）、T90 ≈ 容器底、
 * T98 ≈ 页面底、T10 ≈ 深色主题里的主色。任何种子进来输出都是同一套明度档，对比度有保证。
 */
class CoverSeed(
    val seed: Int,
    /**
     * 种子明度（HCT tone）低于 [DarkSeedTone] 就是 true：详情页恒定深色页；
     * false 即 auto——详情页跟系统颜色模式走。
     */
    val dark: Boolean,
    private val primary: TonalPalette,
) {
    /** primary 色带上任意明度档（0–100）的颜色，ARGB。 */
    fun primaryTone(tone: Int): Int = primary.tone(tone)
}

/**
 * 封面取色器：每个封面算一次，按 URL 缓存，之后全是白拿。
 *
 * 管线：Coil 拉一张 [SamplePx] 的软件小图（走 Coil 自己的磁盘缓存，大图不落内存）→
 * QuantizerCelebi 量化 → Score 挑种子 → TonalPalette。全在 [Dispatchers.Default]，
 * 不碰主线程。
 *
 * **预取**：列表阶段（首页横滑区、详情页头部）拿到封面 URL 就该调 [prefetch]，
 * 等用户真的点进去，色已经在缓存里了——这是"不用点进去再算"的关键。
 */
class CoverPalette(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val cache = ConcurrentHashMap<String, CoverSeed>()

    /** 已缓存的结果；这个封面还没取过色就是 null。 */
    fun cached(url: String): CoverSeed? = cache[url]

    /** 取色（带缓存）。url 为空或加载失败返回 null。 */
    suspend fun seed(url: String): CoverSeed? {
        if (url.isEmpty()) return null
        cache[url]?.let { return it }
        val result = extract(url) ?: return null
        cache[url] = result
        return result
    }

    /** 后台预取一张封面的色。重复调用、已有缓存都会直接跳过。 */
    fun prefetch(url: String) {
        if (url.isEmpty() || cache.containsKey(url)) return
        scope.launch { seed(url) }
    }

    private suspend fun extract(url: String): CoverSeed? = withContext(Dispatchers.Default) {
        try {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(SamplePx)
                // 取色要读像素，硬件位图读不了
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            if (result !is SuccessResult) {
                Log.d(Tag, "加载失败 url=$url result=${result::class.simpleName} $result")
                return@withContext null
            }
            // Coil 3 的位图结果包在 BitmapImage 里（不是 Android 的 BitmapDrawable）
            val bitmap = (result.image as? BitmapImage)?.bitmap
            if (bitmap == null) {
                Log.d(Tag, "image 不是位图: ${result.image::class.simpleName}")
                return@withContext null
            }

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

            val seed = Score.score(QuantizerCelebi.quantize(pixels, MaxColors))
                .firstOrNull()
            if (seed == null) {
                Log.d(Tag, "打分结果为空 url=$url")
                return@withContext null
            }
            Log.d(Tag, "取色成功 seed=0x" + seed.toUInt().toString(16) + " url=$url")
            // 先过 HCT 把彩度钳到上限再生成色带：橙红不再爆亮，蓝紫不受影响
            val hct = Hct.fromInt(seed)
            CoverSeed(
                seed = seed,
                dark = hct.tone < DarkSeedTone,
                primary = TonalPalette.fromHueAndChroma(
                    hue = hct.hue,
                    chroma = minOf(hct.chroma, SeedChromaCap),
                ),
            )
        } catch (t: Throwable) {
            Log.d(Tag, "提取抛了异常 url=$url", t)
            null
        }
    }
}
