package com.nevoit.cirro.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt

/**
 * 给封面接一段「倒影」的 Coil 变换。
 *
 * 输入一张方图（边长记作 [squareDp]），输出 `squareDp × (squareDp + riseDp)`：
 * 上半是原图，下半接一段 [riseDp] 高的倒影 —— 取自原图底部 [stripDp] 那一条，**上下翻转**后拉伸填满，
 * 再做**半径线性增长**的模糊：从接缝**往上 [bleedDp] 处**的 0 开始，一路长到底部的 [blurDp]。
 *
 * 所有几何量都按 [squareDp] 折算成比例，输出宽度直接沿用输入位图的宽度，所以不需要 density，
 * 解码器给出多大尺寸都能算对。输入宽度本身就是输出宽度，意味着只要请求尺寸和布局尺寸一致，
 * 结果就是一个像素对一个像素，不会被二次缩放。
 *
 * ### 为什么用变换而不是实时 shader
 * 一张图只算一次，算完进 Coil 的内存缓存，滑动时是零开销；实时 shader 每帧都要重算。
 * 顺带还有个好处：**不挑系统版本** —— Haze 那套渐变模糊在 Android 13 以下得靠多次绘制或遮罩降级。
 *
 * ### 模糊怎么算的
 * 结构照搬 chrisbanes/haze：它的渐变模糊是「mask 提供逐像素半径 + 可分离两趟高斯」，
 * `sigma = max(radius / 2, 1)`，采样越界就丢掉该权重、用实际覆盖到的像素重新归一化。
 *
 * 这里保持同样的结构和 sigma 语义，只是把高斯核换成**三次 box 模糊**：box 半径 k 的 sigma 是
 * `k / sqrt(3)`，叠三次后 sigma ≈ k，所以 box 半径取 sigma 就等于复现 Haze 的模糊量。
 * 再配合前缀和，每趟的代价从 O(w·h·r) 降到 O(w·h) —— 底部半径 32dp 时，真高斯要两千万次采样，
 * box 只要七十万次整数运算。
 *
 * 接缝处半径为 0 时接得上原图，但"上面全清晰、下面从 0 开始糊"会让过渡挤在接缝一条线上。
 * [bleedDp] 就是把半径 0 的位置**抬到接缝上方** [bleedDp] 处，让模糊带整个往上溢一截：
 * 原图最下面那 [bleedDp] 从清晰渐变到接缝处那个（还不大的）半径，接缝两边就平滑接上了。
 * 代价是原图底部那 [bleedDp] 确实会被糊到一点 —— 这是刻意的。
 */
class ReflectionTransformation(
    /** 封面本体的边长。其余尺寸都按它折算。 */
    private val squareDp: Float = 192f,
    /** 下方接出的倒影高度。 */
    private val riseDp: Float = 64f,
    /** 从原图底部裁来、翻转成倒影的那一条的高度。 */
    private val stripDp: Float = 16f,
    /** 倒影底部的最大模糊半径（半径 0 在接缝上方 [bleedDp] 处）。 */
    private val blurDp: Float = 32f,
    /** 模糊带越过接缝、往上溢进原图的高度。 */
    private val bleedDp: Float = 8f,
) : Transformation() {

    override val cacheKey: String = "reflection($squareDp,$riseDp,$stripDp,$blurDp,$bleedDp)"

    override fun equals(other: Any?): Boolean = other is ReflectionTransformation &&
            other.squareDp == squareDp &&
            other.riseDp == riseDp &&
            other.stripDp == stripDp &&
            other.blurDp == blurDp &&
            other.bleedDp == bleedDp

    override fun hashCode(): Int = cacheKey.hashCode()

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (input.width == 0 || input.height == 0) return input

        val source = input.asSoftware()
        val width = source.width
        val rise = (width * riseDp / squareDp).roundToInt().coerceAtLeast(1)
        val strip = (width * stripDp / squareDp).roundToInt().coerceIn(1, width)
        val bleed = (width * bleedDp / squareDp).roundToInt().coerceAtLeast(1)

        // 源图居中裁成正方形，这样非正方形的图也不会被拉变形。
        val side = minOf(source.width, source.height)
        val crop = Rect(
            (source.width - side) / 2,
            (source.height - side) / 2,
            (source.width + side) / 2,
            (source.height + side) / 2,
        )

        val output = createBitmap(width, width + rise)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        canvas.drawBitmap(source, crop, RectF(0f, 0f, width.toFloat(), width.toFloat()), paint)

        // 倒影：把画布翻过来画。翻转后源图的最后一行正好落在接缝上，所以接缝处看不出接痕。
        canvas.withTranslation(0f, (width + rise).toFloat()) {
            canvas.scale(1f, -1f)
            canvas.drawBitmap(
                source,
                Rect(crop.left, crop.bottom - strip, crop.right, crop.bottom),
                RectF(0f, 0f, width.toFloat(), rise.toFloat()),
                paint,
            )
        }

        // 模糊带从接缝上方 bleed 处起算，所以 top 要减去 bleed、height 要加上 bleed。
        blurReflection(
            output,
            top = width - bleed,
            height = rise + bleed,
            maxRadius = width * blurDp / squareDp
        )

        // 硬件位图是拷出来的一份，用完自己回收；输入位图本身不碰（Coil 管它）。
        if (source !== input) source.recycle()
        return output
    }
}

/** Coil 可能给硬件位图，而硬件位图没法画到软件 Canvas 上，也读不出像素。 */
private fun Bitmap.asSoftware(): Bitmap =
    if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

/**
 * 只对 y ∈ [top, top + height) 这一段做**半径逐行递增**的模糊。
 *
 * 第 j 行的半径是 `maxRadius * j / (height - 1)`：这一段的最上面一行半径 0（原样输出），
 * 最底下一行正好到 [maxRadius]。调用方把 [top] 放在接缝上方，模糊带便由此向原图溢出 [bleedDp]。
 */
private fun blurReflection(bitmap: Bitmap, top: Int, height: Int, maxRadius: Float) {
    val width = bitmap.width
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, top, width, height)

    // box 半径取 sigma = radius / 2（Haze 的公式）。半径 0 的行 k 为 0，
    // 窗口塌成单个像素，等于原样输出 —— 那就是模糊带的上缘，接缝之上就靠它接回清晰的原图。
    val radius = IntArray(height) { row ->
        val t = if (height > 1) row.toFloat() / (height - 1) else 1f
        (maxRadius * t / 2f).roundToInt()
    }

    val scratch = IntArray(pixels.size)
    repeat(2) { // 效果差的不多
        boxBlur(pixels, scratch, width, height, radius, horizontal = true)
        boxBlur(scratch, pixels, width, height, radius, horizontal = false)
    }

    bitmap.setPixels(pixels, 0, width, 0, top, width, height)
}

/**
 * 一维 box 模糊。窗口半径横向时按行取、纵向时按输出行取，都从 [radius] 查。
 *
 * 每条线先求一遍各通道前缀和，之后每个输出像素两次查表就拿到区间和 —— 半径取多大都是 O(1)，
 * 整趟 O(w·h)，跟真高斯核的 O(w·h·r) 差两个量级。
 *
 * 越界的那部分不参与平均（除数用实际覆盖到的像素数），跟 Haze shader 里丢掉越界权重
 * 再重新归一化是一个意思。
 */
private fun boxBlur(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: IntArray,
    horizontal: Boolean,
) {
    val line = if (horizontal) width else height
    val lines = if (horizontal) height else width
    val stride = if (horizontal) 1 else width

    val alpha = IntArray(line + 1)
    val red = IntArray(line + 1)
    val green = IntArray(line + 1)
    val blue = IntArray(line + 1)

    for (index in 0 until lines) {
        val base = if (horizontal) index * width else index

        for (i in 0 until line) {
            val pixel = src[base + i * stride]
            alpha[i + 1] = alpha[i] + ((pixel ushr 24) and 0xFF)
            red[i + 1] = red[i] + ((pixel ushr 16) and 0xFF)
            green[i + 1] = green[i] + ((pixel ushr 8) and 0xFF)
            blue[i + 1] = blue[i] + (pixel and 0xFF)
        }

        for (i in 0 until line) {
            val k = if (horizontal) radius[index] else radius[i]
            val from = maxOf(0, i - k)
            val to = minOf(line - 1, i + k)
            val count = to - from + 1
            val half = count shr 1

            dst[base + i * stride] =
                (((alpha[to + 1] - alpha[from] + half) / count) shl 24) or
                        (((red[to + 1] - red[from] + half) / count) shl 16) or
                        (((green[to + 1] - green[from] + half) / count) shl 8) or
                        ((blue[to + 1] - blue[from] + half) / count)
        }
    }
}
