package com.nevoit.cirro.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

internal class PlayerMotionFrameStore(private val directory: File) {
    private fun file(key: PlayerMotionFrameKey) = AtomicFile(File(
        if (key.albumId > 0) File(directory, key.albumId.toString()) else directory,
        "${key.diskId()}.frame",
    ))

    fun read(key: PlayerMotionFrameKey): PlayerMotionCachedFrame? = try {
        val file = listOf(
            file(key), AtomicFile(File(directory, "${key.diskId()}.frame")), AtomicFile(File(directory, "first-frame")),
        ).firstOrNull { it.baseFile.exists() || File("${it.baseFile.path}.bak").exists() } ?: return null
        DataInputStream(file.openRead()).use { input ->
            val version = input.readInt()
            if (version !in 1..FormatVersion || input.readUTF() != key.diskId()) return null
            // 没有源分辨率的缓存仍可占位，但加载视频后必须重新校验画质。
            val sourceWidth = if (version >= 2) input.readInt() else 0
            val capturedAt = if (version >= 2) input.readLong() else 0L
            if (version >= 2 && (sourceWidth <= 0 || capturedAt <= 0)) return null
            BitmapFactory.decodeStream(input)?.asImageBitmap()?.let { PlayerMotionCachedFrame(it, sourceWidth, capturedAt) }
        }
    } catch (_: java.io.FileNotFoundException) {
        null
    } catch (error: Exception) {
        Log.w("PlayerMotionArtwork", "无法恢复首帧缓存", error)
        null
    }

    fun write(key: PlayerMotionFrameKey, frame: PlayerMotionCachedFrame) {
        val file = file(key)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            val output = DataOutputStream(stream)
            output.writeInt(FormatVersion)
            output.writeUTF(key.diskId())
            output.writeInt(frame.sourceWidth)
            output.writeLong(frame.capturedAtMillis)
            check(frame.image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
            output.flush()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            Log.w("PlayerMotionArtwork", "无法保存首帧缓存", error)
        }
    }

    private companion object {
        // 倒影或模糊算法改变时递增，避免复用旧的处理结果。
        const val FormatVersion = 2
    }
}

internal fun PlayerMotionFrameKey.diskId(): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        val urlBytes = url.toByteArray(Charsets.UTF_8)
        output.writeInt(urlBytes.size)
        output.write(urlBytes)
        output.writeInt(width)
        output.writeInt(height)
        output.writeInt(PlayerMotionArtworkDefaults.MaxVideoWidthPx)
        with(layout) {
            output.writeFloat(heightToWidth)
            output.writeFloat(blurStartFraction)
            output.writeFloat(blurEndFraction)
            output.writeFloat(reflectionSourceFraction)
            output.writeFloat(blurRadiusPx)
            output.writeFloat(blurCircleBottomFraction)
            output.writeFloat(blurHorizontalScale)
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
