package com.nevoit.cirro.home

import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.test.platform.app.InstrumentationRegistry
import coil3.decode.DataSource
import coil3.imageLoader
import coil3.request.SuccessResult
import coil3.request.crossfadeMillis
import coil3.size.Size
import com.nevoit.cirro.playback.pearmesh.MemoryCoverRequest
import com.nevoit.cirro.playback.pearmesh.MemoryCoverTransformation
import com.nevoit.cirro.playback.pearmesh.memoryCoverPaletteKey
import com.nevoit.cirro.playback.pearmesh.prepareMemoryCoverPalette
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.cirro.ui.ReflectionTransformation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCoverRequestTest {
    @Test
    fun preparedPaletteUsesTheGeneratedSquareEvenWhenOriginalPaletteIsWarm() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "memory-palette-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = directory
        }
        val original = createBitmap(96, 96).apply { eraseColor(Color.BLUE) }
        val generatedInput = createBitmap(96, 96).apply { eraseColor(Color.RED) }
        try {
            val originalFile = File(directory, "official.png")
            originalFile.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val source = originalFile.toURI().toString()
            val palette = CoverPalette(context, this)
            val officialSeed = palette.seed(source)!!
            MemoryCoverTransformation(context, source).transform(generatedInput, Size(512, 512)).recycle()
            prepareMemoryCoverPalette(context, source, palette)
            val generatedKey = memoryCoverPaletteKey(context, source)
            assertNotEquals(source, generatedKey)
            val preparedSeed = palette.cached(generatedKey)
            assertNotNull("Navigation and detail can synchronously read the generated palette", preparedSeed)
            assertNotEquals("The warm official palette must not be reused", officialSeed.seed, preparedSeed!!.seed)
            assertEquals(palette.seed(generatedKey)!!.seed, preparedSeed.seed)
        } finally {
            original.recycle()
            generatedInput.recycle()
            directory.deleteRecursively()
        }
    }

    @Test
    fun reenteringUsesMemoryAndDiskRestorationDoesNotCrossfade() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "memory-request-test-${UUID.randomUUID()}")
        val context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = directory
        }
        val source = "https://example.invalid/${UUID.randomUUID()}"
        val reflection = ReflectionTransformation(context, squareDp = 216f, riseDp = 72f, bleedDp = 36f)
        fun loader() = MemoryCoverRequest(context, source, 648, 864, reflection)
        val red = createBitmap(96, 96).apply { eraseColor(Color.RED) }
        var cacheKey: coil3.memory.MemoryCache.Key? = null
        try {
            val initial = loader().load()
            assertEquals(source, initial.data)
            assertTrue("Only a new cover should fade in", initial.crossfadeMillis > 0)
            MemoryCoverTransformation(context, source, reflection)
                .transform(red, Size(512, 683)).recycle()
            val fromDisk = loader().load()
            assertEquals(0, fromDisk.crossfadeMillis)
            val result = context.imageLoader.execute(fromDisk) as SuccessResult
            cacheKey = result.memoryCacheKey
            assertNotNull(cacheKey)
            val reentered = loader().memoryCached()
            assertNotNull("Reentering composition can supply a request immediately", reentered)
            assertEquals(fromDisk.memoryCacheKey, reentered!!.memoryCacheKey)
            val reused = context.imageLoader.execute(reentered) as SuccessResult
            assertEquals(DataSource.MEMORY_CACHE, reused.dataSource)
            context.imageLoader.memoryCache!!.remove(cacheKey!!)
            assertNull(loader().memoryCached())
            assertEquals("Disk restoration must also skip the fade after memory eviction", 0, loader().load().crossfadeMillis)
            val detail = MemoryCoverRequest(
                context, source, 512, 640,
                ReflectionTransformation(context, squareDp = 512f, riseDp = 128f),
                crossfadeFromDisk = true,
            ).load()
            assertNotEquals(fromDisk.memoryCacheKey, detail.memoryCacheKey)
            assertTrue("Detail must fade in when loading its reflection", detail.crossfadeMillis > 0)
            assertTrue("Detail must use the saved square without downloading official artwork", detail.data is Bitmap)
            val detailResult = context.imageLoader.execute(detail)
            assertTrue("Detail can render with an unreachable official artwork URL", detailResult is SuccessResult)
            detailResult as SuccessResult
            detailResult.memoryCacheKey?.let { context.imageLoader.memoryCache?.remove(it) }
            val restoredDetail = MemoryCoverRequest(
                context, source, 512, 640,
                ReflectionTransformation(context, squareDp = 512f, riseDp = 128f),
                crossfadeFromDisk = true,
            ).load()
            assertTrue("Detail must also fade in when restored from disk", restoredDetail.crossfadeMillis > 0)
        } finally {
            cacheKey?.let { context.imageLoader.memoryCache?.remove(it) }
            red.recycle()
            directory.deleteRecursively()
        }
    }
}
