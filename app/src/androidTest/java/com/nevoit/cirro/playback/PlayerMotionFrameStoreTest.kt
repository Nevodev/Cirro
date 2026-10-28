package com.nevoit.cirro.playback

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.nevoit.cirro.ui.video.PlayerMotionVideoLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.DataOutputStream
import java.util.UUID

class PlayerMotionFrameStoreTest {
    @Test
    fun frameSurvivesStoreRecreationAndRejectsMismatchedOrCorruptFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "frame-store-test-${UUID.randomUUID()}")
        val key = PlayerMotionFrameKey("video", 400, 900, PlayerMotionVideoLayout(2.25f, 0.9f, 1.2f, 0.3f, 100f), albumId = 42)
        val bitmap = Bitmap.createBitmap(4, 9, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val frame = PlayerMotionCachedFrame(bitmap.asImageBitmap(), 1080, System.currentTimeMillis())
        try {
            PlayerMotionFrameStore(directory).write(key, frame)
            val recreated = PlayerMotionFrameStore(directory)
            val saved = checkNotNull(recreated.read(key))
            assertEquals(1080, saved.sourceWidth)
            assertEquals(frame.capturedAtMillis, saved.capturedAtMillis)
            val restored = saved.image.asAndroidBitmap()
            assertEquals(4, restored.width)
            assertEquals(9, restored.height)
            assertEquals(Color.MAGENTA, restored.getPixel(2, 3))
            restored.recycle()
            assertNull(recreated.read(key.copy(url = "other")))
            assertNull(recreated.read(key.copy(layout = key.layout.copy(blurRadiusPx = 120f))))
            val otherKey = key.copy(url = "other-video", albumId = 43)
            recreated.write(otherKey, frame)
            assertEquals(Color.MAGENTA, checkNotNull(recreated.read(key)).image.asAndroidBitmap().getPixel(0, 0))
            assertEquals(Color.MAGENTA, checkNotNull(recreated.read(otherKey)).image.asAndroidBitmap().getPixel(0, 0))
            DataOutputStream(File(File(directory, "42"), "${key.diskId()}.frame").outputStream()).use { output ->
                output.writeInt(1)
                output.writeUTF(key.diskId())
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            val legacy = checkNotNull(recreated.read(key))
            assertEquals(0, legacy.sourceWidth)
            assertEquals(Color.MAGENTA, legacy.image.asAndroidBitmap().getPixel(0, 0))
            File(File(directory, "42"), "${key.diskId()}.frame").writeBytes(byteArrayOf(0, 0, 0, 1))
            assertNull(recreated.read(key))
            File(File(directory, "42"), "${key.diskId()}.frame").writeBytes(byteArrayOf(1, 2, 3))
            assertNull(recreated.read(key))
        } finally {
            bitmap.recycle()
            directory.listFiles()?.forEach { child ->
                if (child.isDirectory) child.listFiles()?.forEach { it.delete() }
                child.delete()
            }
            directory.delete()
        }
    }
}
