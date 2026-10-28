package com.nevoit.cirro.home

import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.platform.app.InstrumentationRegistry
import coil3.size.Size
import com.nevoit.cirro.playback.pearmesh.PearMeshCoverTransformation
import com.nevoit.cirro.ui.ReflectionTransformation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PearMeshCoverTest {
    @Test
    fun squareMeshUsesItsInputAndReflectionOnlyExtendsBelowIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val red = createBitmap(96, 96).apply { eraseColor(Color.RED) }
        val blue = createBitmap(96, 96).apply { eraseColor(Color.BLUE) }
        val transform = PearMeshCoverTransformation(context)
        val first = transform.transform(red, Size(96, 96))
        val repeat = transform.transform(red, Size(96, 96))
        val second = transform.transform(blue, Size(96, 96))
        try {
            assertNotSame("Must run the renderer instead of falling back", red, first)
            assertEquals(512, first.width)
            assertEquals(512, first.height)
            assertTrue("Same cover must keep a stable static frame", first.sameAs(repeat))
            assertFalse("Official artwork must affect the result", first.sameAs(second))
            assertFalse(red.isRecycled)
            val reflected = ReflectionTransformation(context, squareDp = 216f, riseDp = 72f)
                .transform(first, Size(96, 128))
            try {
                assertEquals(512, reflected.width)
                assertEquals(683, reflected.height)
                val pixels = IntArray(512 * 683)
                reflected.getPixels(pixels, 0, 512, 0, 0, 512, 683)
                assertTrue(pixels.all { Color.alpha(it) == 255 })
            } finally { reflected.recycle() }
        } finally {
            first.recycle()
            repeat.recycle()
            second.recycle()
            red.recycle()
            blue.recycle()
        }
    }
}
