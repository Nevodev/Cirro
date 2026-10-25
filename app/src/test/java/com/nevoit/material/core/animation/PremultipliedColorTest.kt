package com.nevoit.material.core.animation

import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PremultipliedColorTest {
    @Test
    fun scrimToOpaqueFillNeverFlashesBrighterThanEitherEndpoint() {
        val cases = listOf(
            Triple(Color.White.copy(alpha = 0.05f), Color(0xFF5A5060), Color.Black),
            Triple(Color.White.copy(alpha = 0.1f), Color(0xFF5A5060), Color.Black),
            Triple(Color.Black.copy(alpha = 0.025f), Color(0xFFE8DEE8), Color.White),
            Triple(Color.Black.copy(alpha = 0.05f), Color(0xFFE8DEE8), Color.White),
        )
        for ((scrim, fill, background) in cases) {
            for ((start, end) in listOf(scrim to fill, fill to scrim)) {
                val from = start.compositeOver(background)
                val to = end.compositeOver(background)
                for (step in 0..100) {
                    val actual = interpolate(start, end, step / 100f).compositeOver(background)
                    val fraction = step / 100f
                    assertEquals(from.red + (to.red - from.red) * fraction, actual.red, 0.006f)
                    assertEquals(from.green + (to.green - from.green) * fraction, actual.green, 0.006f)
                    assertEquals(from.blue + (to.blue - from.blue) * fraction, actual.blue, 0.006f)
                }
            }
        }
    }

    @Test
    fun invisibleRgbDoesNotTintTheTransition() {
        val target = Color(0xFF407080)
        val fromBlack = interpolate(Color.Black.copy(alpha = 0f), target, 0.5f)
        val fromWhite = interpolate(Color.White.copy(alpha = 0f), target, 0.5f)
        assertEquals(fromBlack, fromWhite)
        assertEquals(target.red, fromBlack.red, 0.006f)
        assertEquals(target.green, fromBlack.green, 0.006f)
        assertEquals(target.blue, fromBlack.blue, 0.006f)
        assertEquals(0.5f, fromBlack.alpha, 0.006f)
    }

    @Test
    fun fullyTransparentResultHasFiniteChannels() {
        val actual = interpolate(Color.Transparent, Color.White.copy(alpha = 0f), 0.5f)
        assertEquals(0f, actual.alpha, 0f)
        assertTrue(actual.red.isFinite() && actual.green.isFinite() && actual.blue.isFinite())
    }

    private fun interpolate(start: Color, end: Color, fraction: Float): Color {
        val from = PremultipliedColorConverter.convertToVector(start)
        val to = PremultipliedColorConverter.convertToVector(end)
        return PremultipliedColorConverter.convertFromVector(
            AnimationVector4D(
                from.v1 + (to.v1 - from.v1) * fraction,
                from.v2 + (to.v2 - from.v2) * fraction,
                from.v3 + (to.v3 - from.v3) * fraction,
                from.v4 + (to.v4 - from.v4) * fraction,
            )
        )
    }
}
