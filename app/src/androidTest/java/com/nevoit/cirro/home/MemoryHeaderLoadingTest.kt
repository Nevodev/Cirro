package com.nevoit.cirro.home

import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.test.platform.app.InstrumentationRegistry
import coil3.compose.AsyncImage
import coil3.request.NullRequestDataException
import coil3.size.Size
import com.nevoit.cirro.detail.HeaderCoverImage
import com.nevoit.cirro.playback.pearmesh.MemoryCoverTransformation
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MemoryHeaderLoadingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun coilReportsNullDataAsAnError() {
        var error: Throwable? = null
        compose.setContent {
            AsyncImage(
                model = null,
                contentDescription = null,
                onError = { error = it.result.throwable },
                modifier = Modifier.size(64.dp),
            )
        }
        compose.waitUntil(5_000) { error != null }
        assertTrue(error is NullRequestDataException)
    }

    @Test
    fun diskPreparationAndReentryDoNotInvalidateTheHeaderCover() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "memory-header-test-${UUID.randomUUID()}")
        val context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = directory
        }
        val source = "https://example.invalid/${UUID.randomUUID()}"
        val red = createBitmap(96, 96).apply { eraseColor(AndroidColor.RED) }
        val visible = mutableStateOf(true)
        val failed = mutableStateOf(false)
        val errors = AtomicInteger()
        try {
            MemoryCoverTransformation(context, source).transform(red, Size(512, 512)).recycle()
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    Box(
                        Modifier.size(128.dp, 256.dp)
                            .background(if (failed.value) Color.Yellow else Color.Black)
                            .testTag("header"),
                    ) {
                        if (visible.value) HeaderCoverImage(
                            url = if (failed.value) "" else source,
                            widthDp = 128,
                            memoryCover = true,
                            onError = { errors.incrementAndGet(); failed.value = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            fun waitForRedHeader() {
                compose.waitUntil(10_000) {
                    val pixels = compose.onNodeWithTag("header").captureToImage().toPixelMap()
                    val pixel = pixels[pixels.width / 2, pixels.height / 4]
                    pixel.red > pixel.green + 0.2f && pixel.red > pixel.blue + 0.2f
                }
                assertEquals("Preparing the local request must not trigger cover fallback", 0, errors.get())
            }
            waitForRedHeader()
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { visible.value = true }
            waitForRedHeader()
            compose.runOnIdle { visible.value = false }
        } finally {
            red.recycle()
            directory.deleteRecursively()
        }
    }
}
