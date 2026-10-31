package com.nevoit.material.clip

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.shapes.UnevenRoundedRectangle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnevenRoundedRectangleTest {
    @Test
    fun mixedCornersKeepTheStraightSidesInBothLayoutDirections() {
        val width = 240
        val height = 80
        val cornerPixels = listOf(1 to 1, width - 2 to 1, width - 2 to height - 2, 1 to height - 2)
        for (direction in LayoutDirection.entries) {
            for (mask in 1..15) {
                val radii = List(4) { index -> if (mask and (1 shl index) != 0) 16.dp else 0.dp }
                val shape = UnevenRoundedRectangle(radii[0], radii[1], radii[2], radii[3])
                val outline = shape.createOutline(Size(width.toFloat(), height.toFloat()), direction, Density(1f))
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(bitmap).drawPath((outline as Outline.Generic).path.asAndroidPath(), Paint().apply {
                        color = Color.BLUE
                        isAntiAlias = true
                    })
                    val label = "mask=$mask, direction=$direction"
                    for ((x, y) in listOf(width / 2 to 1, width / 2 to height - 2, 1 to height / 2, width - 2 to height / 2)) {
                        assertEquals("$label: straight edge ($x, $y)", Color.BLUE, bitmap.getPixel(x, y))
                    }
                    val physicalCorners = if (direction == LayoutDirection.Ltr) radii else listOf(radii[1], radii[0], radii[3], radii[2])
                    cornerPixels.forEachIndexed { index, (x, y) ->
                        val expected = if (physicalCorners[index] == 0.dp) Color.BLUE else Color.TRANSPARENT
                        assertEquals("$label: corner $index", expected, bitmap.getPixel(x, y))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }
}
