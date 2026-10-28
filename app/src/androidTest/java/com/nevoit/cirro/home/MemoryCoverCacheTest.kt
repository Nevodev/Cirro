package com.nevoit.cirro.home

import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import androidx.test.platform.app.InstrumentationRegistry
import coil3.size.Size
import com.nevoit.cirro.playback.pearmesh.MemoryCoverTransformation
import com.nevoit.cirro.ui.ReflectionTransformation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCoverCacheTest {
    @Test
    fun savedCoverSurvivesNewRequestsAndCorruptionIsRegenerated() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "memory-cover-test-${UUID.randomUUID()}")
        val context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = directory
        }
        fun transform(source: String) = MemoryCoverTransformation(
            context, source,
            ReflectionTransformation(context, squareDp = 216f, riseDp = 72f, bleedDp = 36f),
        )
        val red = createBitmap(96, 96).apply { eraseColor(Color.RED) }
        val blue = createBitmap(96, 96).apply { eraseColor(Color.BLUE) }
        val results = mutableListOf<android.graphics.Bitmap>()
        try {
            assertNull(transform("red").cachedImage())
            val first = transform("red").transform(red, Size(512, 683)).also(results::add)
            assertEquals(512, first.width)
            assertEquals(683, first.height)
            val file = File(directory, "memory-covers").listFiles()!!.single {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it.absolutePath, bounds)
                bounds.outHeight == 683
            }
            assertTrue(file.length() > 0)
            val savedBytes = file.readBytes()
            val savedTime = file.lastModified()
            val cached = transform("red").cachedImage()
            assertNotNull("Must load the finished image without the original artwork", cached)
            results.add(cached!!)
            assertTrue(first.sameAs(cached))
            val reused = transform("red").transform(blue, Size(512, 683)).also(results::add)
            assertTrue("New request must reuse disk instead of rendering the supplied blue input", first.sameAs(reused))
            assertTrue(savedBytes.contentEquals(file.readBytes()))
            assertEquals(savedTime, file.lastModified())
            val differentSource = transform("blue").transform(blue, Size(512, 683)).also(results::add)
            assertFalse("A changed artwork identity must generate its own cover", first.sameAs(differentSource))
            file.writeText("incomplete png", Charsets.UTF_8)
            assertNull(transform("red").cachedImage())
            val repaired = transform("red").transform(red, Size(512, 683)).also(results::add)
            assertTrue(first.sameAs(repaired))
            val reread = transform("red").cachedImage()!!.also(results::add)
            assertTrue(repaired.sameAs(reread))
        } finally {
            results.forEach { it.recycle() }
            red.recycle()
            blue.recycle()
            directory.deleteRecursively()
        }
    }

    @Test
    fun detailReusesTheSquareSavedByTheHomeCard() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "memory-cover-detail-test-${UUID.randomUUID()}")
        val context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = directory
        }
        val red = createBitmap(96, 96).apply { eraseColor(Color.RED) }
        val blue = createBitmap(96, 96).apply { eraseColor(Color.BLUE) }
        val results = mutableListOf<android.graphics.Bitmap>()
        try {
            val home = MemoryCoverTransformation(
                context, "same-artwork",
                ReflectionTransformation(context, squareDp = 216f, riseDp = 72f, bleedDp = 36f),
            ).transform(red, Size(512, 683)).also(results::add)
            val detail = MemoryCoverTransformation(context, "same-artwork")
            val square = detail.cachedImage()!!.also(results::add)
            assertEquals(512, square.width)
            assertEquals(512, square.height)
            val cachedWithDifferentInput = detail.transform(blue, Size(1024, 1365)).also(results::add)
            assertTrue(square.sameAs(cachedWithDifferentInput))
            val reflectedSquare = ReflectionTransformation(
                context, squareDp = 216f, riseDp = 72f, bleedDp = 36f,
            ).transform(square, Size(512, 683)).also(results::add)
            assertTrue("Applying the home reflection to the saved square must reproduce the home card", home.sameAs(reflectedSquare))
            assertEquals(2, File(directory, "memory-covers").listFiles()!!.size)
        } finally {
            results.forEach { it.recycle() }
            red.recycle()
            blue.recycle()
            directory.deleteRecursively()
        }
    }
}
