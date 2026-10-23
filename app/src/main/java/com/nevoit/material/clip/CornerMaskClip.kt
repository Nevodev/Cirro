package com.nevoit.material.clip

import android.util.LruCache
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.RoundedRectangularShape
import com.kyant.shapes.continuousCurvatureCornerPoints
import kotlin.math.ceil
import kotlin.math.roundToInt

fun Modifier.cornerMaskClip(
    radius: Dp,
    enabled: Boolean = true,
): Modifier = cornerMaskClip(RoundedRectangle(radius), enabled)

fun Modifier.cornerMaskClip(
    shape: RoundedRectangularShape,
    enabled: Boolean = true,
): Modifier = then(CornerMaskClipElement(shape, enabled))

private class CornerMaskClipElement(
    val shape: RoundedRectangularShape,
    val enabled: Boolean,
) : ModifierNodeElement<CornerMaskClipNode>() {

    override fun create(): CornerMaskClipNode = CornerMaskClipNode(shape, enabled)

    override fun update(node: CornerMaskClipNode) {
        node.shape = shape
        node.enabled = enabled
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "cornerMaskClip"
        properties["shape"] = shape
        properties["enabled"] = enabled
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CornerMaskClipElement) return false
        return shape == other.shape && enabled == other.enabled
    }

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + enabled.hashCode()
        return result
    }
}

private class CornerMaskClipNode(
    var shape: RoundedRectangularShape,
    var enabled: Boolean,
) : Modifier.Node(), DrawModifierNode {

    private var cornersSize = Size.Zero
    private var cornersShape: RoundedRectangularShape? = null
    private var cornersDirection: LayoutDirection? = null
    private var corners: RoundedRectangularShape.Corners? = null

    private fun cornersOf(
        width: Float,
        height: Float,
        direction: LayoutDirection,
        density: Density
    ): RoundedRectangularShape.Corners {
        val cached = corners
        if (cached != null &&
            cornersSize.width == width &&
            cornersSize.height == height &&
            cornersShape == shape &&
            cornersDirection == direction
        ) {
            return cached
        }
        val computed = shape.corners(Size(width, height), direction, density)
        cornersSize = Size(width, height)
        cornersShape = shape
        cornersDirection = direction
        corners = computed
        return computed
    }

    override fun ContentDrawScope.draw() {
        if (!enabled) {
            drawContent()
            return
        }

        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) {
            drawContent()
            return
        }

        val corners = cornersOf(width, height, layoutDirection, this)
        if (corners.topLeft <= 0f && corners.topRight <= 0f &&
            corners.bottomRight <= 0f && corners.bottomLeft <= 0f
        ) {
            drawContent()
            return
        }

        val topLeft = CornerMaskCache.obtain(corners.topLeft, width, height)
        val topRight = CornerMaskCache.obtain(corners.topRight, width, height)
        val bottomRight = CornerMaskCache.obtain(corners.bottomRight, width, height)
        val bottomLeft = CornerMaskCache.obtain(corners.bottomLeft, width, height)

        val canvas = drawContext.canvas
        canvas.saveLayer(Rect(0f, 0f, width, height), MaskLayerPaint)
        try {
            drawContent()
            if (topLeft != null) drawCornerMask(topLeft, mirrorX = false, mirrorY = false)
            if (topRight != null) drawCornerMask(topRight, mirrorX = true, mirrorY = false)
            if (bottomRight != null) drawCornerMask(bottomRight, mirrorX = true, mirrorY = true)
            if (bottomLeft != null) drawCornerMask(bottomLeft, mirrorX = false, mirrorY = true)
        } finally {
            canvas.restore()
        }
    }
}

private fun DrawScope.drawCornerMask(
    mask: ImageBitmap,
    mirrorX: Boolean,
    mirrorY: Boolean,
) {
    if (!mirrorX && !mirrorY) {
        drawImage(mask, topLeft = Offset.Zero, blendMode = BlendMode.DstOut)
        return
    }

    val canvas = drawContext.canvas
    canvas.save()
    try {
        canvas.translate(
            if (mirrorX) size.width else 0f,
            if (mirrorY) size.height else 0f
        )
        canvas.scale(
            if (mirrorX) -1f else 1f,
            if (mirrorY) -1f else 1f
        )
        drawImage(mask, topLeft = Offset.Zero, blendMode = BlendMode.DstOut)
    } finally {
        canvas.restore()
    }
}

private object CornerMaskCache {

    private const val RADIUS_STEPS = 4
    private const val T_STEPS = 64
    private const val MAX_PIXELS = 512 * 1024

    private val cache = object : LruCache<Long, ImageBitmap>(MAX_PIXELS) {
        override fun sizeOf(key: Long, value: ImageBitmap): Int =
            value.width * value.height
    }

    fun obtain(radiusPx: Float, width: Float, height: Float): ImageBitmap? {
        if (radiusPx <= 0f) return null
        val tW = ((width * 0.5f - radiusPx) / radiusPx).coerceIn(0f, 1f)
        val tH = ((height * 0.5f - radiusPx) / radiusPx).coerceIn(0f, 1f)

        val radiusKey = (radiusPx * RADIUS_STEPS).roundToInt()
        if (radiusKey <= 0) return null
        val tWKey = (tW * T_STEPS).roundToInt()
        val tHKey = (tH * T_STEPS).roundToInt()
        val key = (radiusKey.toLong() shl 20) or (tWKey.toLong() shl 10) or tHKey.toLong()

        cache.get(key)?.let { return it }

        val bitmap = createCornerMask(
            radius = radiusKey.toFloat() / RADIUS_STEPS,
            tW = tWKey.toFloat() / T_STEPS,
            tH = tHKey.toFloat() / T_STEPS
        ) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }
}

private fun createCornerMask(radius: Float, tW: Float, tH: Float): ImageBitmap? {
    val points = continuousCurvatureCornerPoints(tW.toDouble(), tH.toDouble())
    if (points.size < 20) return null

    // Top-left corner local space: x = (1 - px) * r, y = py * r
    var maxX = 0f
    var maxY = 0f
    var index = 0
    while (index < 20) {
        val x = (1.0 - points[index]).toFloat() * radius
        val y = points[index + 1].toFloat() * radius
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        index += 2
    }

    val width = ceil(maxX + 1f).toInt()
    val height = ceil(maxY + 1f).toInt()
    if (width <= 0 || height <= 0) return null

    val path = Path()
    path.moveTo((1.0 - points[0]).toFloat() * radius, points[1].toFloat() * radius)
    index = 2
    while (index <= 14) {
        path.cubicTo(
            (1.0 - points[index]).toFloat() * radius, points[index + 1].toFloat() * radius,
            (1.0 - points[index + 2]).toFloat() * radius, points[index + 3].toFloat() * radius,
            (1.0 - points[index + 4]).toFloat() * radius, points[index + 5].toFloat() * radius
        )
        index += 6
    }
    path.lineTo(0f, 0f)
    path.close()

    val bitmap = ImageBitmap(width, height, ImageBitmapConfig.Alpha8)
    val canvas = Canvas(bitmap)
    canvas.drawPath(path, MaskFillPaint)
    return bitmap
}

private val MaskFillPaint = Paint().apply {
    color = Color.White
}

private val MaskLayerPaint = Paint()
