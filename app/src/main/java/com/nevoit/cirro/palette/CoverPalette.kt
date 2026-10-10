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
 * 灰阶封面回落色带用的色相与彩度。
 *
 * 黑白封面没有彩色簇可挑（[Score] 的彩色选拔会把它们整体滤掉），兜底色带就用纯中性灰：
 * chroma = 0 时色相无意义，`HctSolver` 对 `chroma < 0.0001` 直接按明度吐 sRGB 灰，
 * 拿到的是干净的灰，而不是封面那点压缩色偏。想给黑白封面带一丝冷/暖调，把 chroma 调到
 * 1–3 再给个 hue 即可。
 */
private const val NeutralHue = 0.0
private const val NeutralChroma = 0.0

/**
 * **封面平均明度**（HCT tone，0–100）的深浅分界线：低于它 dark=true，详情页**恒定**深色页
 * （暗封面压白字怎么都不糊）；在线上 dark=false 即 auto——详情页跟系统颜色模式走。
 * 想更早/更晚切深色调这里。
 *
 * 🔴 判据是**整张封面的平均明度**（[averageTone]），不是被 [Score] 挑中的那一个种子的明度 ——
 * 种子按「彩度 × 占比」打分、明度不参与，深色封面里一块高饱和亮色也可能被选成种子；
 * 拿种子明度判深浅，那种封面会被误判成浅色页（亮封面挑到暗种子则反向）。
 */
private const val DarkSeedTone = 40.0

/**
 * 一张封面的取色结果：种子色 + primary 色带 + 明暗判定。
 *
 * 只做三件事：[seed] 是种子色（ARGB）——彩色封面来自官方 Score 的量化簇，灰阶封面没有彩色
 * 簇可挑，是合成的中性灰；[primary] 是种子进 HCT 后生成的 tonal 色带——色相锁死、彩度被钳制、
 * 明度任意档可取；[dark] 是**封面整体明暗**的判定（取色时算好，消费方直接用）。没有 secondary /
 * tertiary，也不拼整套 ColorScheme：配色自己拿档位试。
 *
 * **档位语义**（M3 标准）：T40 ≈ 主色（浅色主题里配 T100 文字）、T90 ≈ 容器底、
 * T98 ≈ 页面底、T10 ≈ 深色主题里的主色。任何种子进来输出都是同一套明度档，对比度有保证。
 */
class CoverSeed(
    val seed: Int,
    /**
     * 封面平均明度（HCT tone）低于 [DarkSeedTone] 就是 true：详情页恒定深色页；
     * false 即 auto——详情页跟系统颜色模式走。判据是整张封面的平均明度，与 [seed] 无关。
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
 * 不碰主线程。挑不出彩色种子（灰阶封面）时回落成按平均明度合成的中性灰，见 [extract]。
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

            val clusters = QuantizerCelebi.quantize(pixels, MaxColors)

            // 整张封面的平均明度（各簇 tone 按像素数加权）。算一次，两处共用：
            // ① 灰阶封面没有彩色种子，用它合成中性灰当种子；② 深浅页判定也用它。
            // 量化结果为空（退化位图）时它是 null，按取色失败处理。
            val coverTone = averageTone(clusters) ?: return@withContext null

            // fallbackColorArgb 显式传 null：官方的兜底值是写死的 Google Blue（#4285F4），
            // 而 Score 会把 chroma < CUTOFF_CHROMA(5.0) 的簇整体滤掉——灰阶封面的簇全在此列，
            // 滤空后就返回那个蓝，于是每张黑白封面都和"图片加载失败"长得一样（都是同一个蓝）。
            // 拿掉兜底，空结果就成了「这张封面没有可用的彩色种子」这个信号，由我们兜成中性灰。
            val scored = Score.score(clusters, fallbackColorArgb = null).firstOrNull()

            // 灰阶封面回落：黑封面得到深色页、白封面得到浅色页，深浅跟着封面本身走。
            val seed = scored ?: Hct.from(NeutralHue, NeutralChroma, coverTone).toInt()

            if (scored == null) Log.d(Tag, "灰阶封面无彩色种子，回落中性灰 url=$url")
            Log.d(Tag, "取色成功 seed=0x" + seed.toUInt().toString(16) + " url=$url")
            // 先过 HCT 把彩度钳到上限再生成色带：橙红不再爆亮，蓝紫不受影响
            // （灰阶种子本来就 chroma≈0，走同一条路，色带是纯灰）
            val hct = Hct.fromInt(seed)
            CoverSeed(
                seed = seed,
                // 🔴 深浅看**整张封面**的平均明度，不是种子那一个簇的：Score 按「彩度 × 占比」
                // 挑种子、明度不参与，深色封面里一块高饱和亮色也可能胜出当种子；若拿种子明度
                // 判深浅，这种封面会被误判成浅色页。种子只负责色调（hue/chroma），不参与深浅。
                dark = coverTone < DarkSeedTone,
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

    /**
     * 整张封面的平均明度：各量化簇的 tone 按像素数加权平均。既当灰阶封面的回落种子明度，
     * 又当深浅页（[CoverSeed.dark]）的判据。
     *
     * 簇的分布就是封面的明度分布，所以加权平均 ≈ 整张封面的平均明度。只平均 tone 而不是 ARGB
     * 直接平均——sRGB 平均会往中间调塌，黑白封面就压不出深浅感了。
     *
     * ⚠️ 遍历的是**簇**（size ≤ [MaxColors]），不是像素 —— 量化已经把它压到几十个，所以很便宜。
     * 没有可算的像素时返回 null，调用方按取色失败处理。
     */
    private fun averageTone(clusters: Map<Int, Int>): Double? {
        var sum = 0.0
        var count = 0
        for ((color, n) in clusters) {
            sum += Hct.fromInt(color).tone * n
            count += n
        }
        return if (count == 0) null else sum / count
    }
}
