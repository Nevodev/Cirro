package com.nevoit.cirro.playback

import android.content.Context
import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.test.platform.app.InstrumentationRegistry
import com.nevoit.cirro.data.model.Song
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class PlaybackOutputAccountingTest {
    @Test
    fun partialWriteDurationsUseTheOutputPcmFormatAndTheAppliedSpeedBranch() {
        data class Case(val encoding: Int, val speed: Float, val platformSpeed: Boolean, val bytes: Int, val mediaMs: Double, val realMs: Long)
        val cases = listOf(
            Case(C.ENCODING_PCM_16BIT, 2f, false, 2_000, 2_000.0, 1_000),
            Case(C.ENCODING_PCM_16BIT, 2f, true, 2_000, 1_000.0, 500),
            Case(C.ENCODING_PCM_FLOAT, 1f, false, 4_000, 1_000.0, 1_000),
            Case(C.ENCODING_PCM_FLOAT, 2f, true, 4_000, 1_000.0, 500),
        )
        for (case in cases) {
            var snapshot: ListeningSnapshot? = null
            val listening = PlaybackListeningSession(emit = {}, checkpoint = { snapshot = it })
            listening.setTracks(listOf(track(10)))
            listening.update(10, 0)
            val accounting = PlaybackOutputAccounting(listening)
            val timeline = SinglePeriodTimeline(1_000_000, true, false, false, null, Song(1).toMediaItem(10))
            val periodUid = timeline.getUidOfPeriod(0)
            val parameters = PlaybackParameters(case.speed)
            val sink = proxy<AudioSink> { method, _ -> if (method == "getPlaybackParameters") parameters else null }
            accounting.wrapSink(sink).configure(
                AudioSink.AudioSinkConfig.Builder(
                    Format.Builder().setSampleMimeType("audio/raw").setSampleRate(1_000)
                        .setChannelCount(1).setPcmEncoding(case.encoding).build()
                ).setTimeline(timeline).setMediaPeriodId(MediaPeriodId(periodUid, 0L)).build()
            )
            // float PCM 会绕过自定义 processor，必须直接按输出配置识别曲目。
            if (case.encoding != C.ENCODING_PCM_FLOAT) {
                accounting.boundary.flush(AudioProcessor.StreamMetadata.Builder().setTimeline(timeline).setPeriodUid(periodUid).build())
            }
            var calls = 0
            val delegate = proxy<AudioOutput> { method, args ->
                when (method) {
                    "write" -> {
                        calls++
                        val buffer = args!![0] as ByteBuffer
                        buffer.position(buffer.position() + minOf(case.bytes / 2, buffer.remaining()))
                        !buffer.hasRemaining()
                    }
                    "getPlaybackParameters" -> parameters
                    else -> null
                }
            }
            val provider = proxy<AudioOutputProvider> { method, _ -> if (method == "getAudioOutput") delegate else null }
            val output = accounting.wrapProvider(provider).getAudioOutput(
                AudioOutputProvider.OutputConfig.Builder().setEncoding(case.encoding)
                    .setSampleRate(1_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setUsePlaybackParameters(case.platformSpeed).build()
            )
            output.play()
            val buffer = ByteBuffer.allocateDirect(case.bytes)
            assertTrue(!output.write(buffer, 1, 0))
            assertTrue(output.write(buffer, 1, 0))
            listening.update(10, 0)
            assertEquals(case.toString(), case.mediaMs, requireNotNull(snapshot).timeMs, 0.0001)
            assertEquals(case.toString(), case.realMs, requireNotNull(snapshot).realTimeMs)
            assertEquals(2, calls)
        }
    }

    @Test
    fun partialWritesCountOnceAndPausedPreparationDoesNotStartAReport() {
        val events = mutableListOf<ListeningEvent>()
        val listening = PlaybackListeningSession(emit = events::add, checkpoint = {})
        listening.setTracks(listOf(track(10)))
        listening.update(10, 0)
        val accounting = PlaybackOutputAccounting(listening)
        val timeline = SinglePeriodTimeline(1_000_000, true, false, false, null, Song(1).toMediaItem(10))
        val periodUid = timeline.getUidOfPeriod(0)
        val sink = proxy<AudioSink> { method, _ ->
            if (method == "getPlaybackParameters") PlaybackParameters.DEFAULT else null
        }
        accounting.wrapSink(sink).configure(
            AudioSink.AudioSinkConfig.Builder(
                Format.Builder().setSampleMimeType("audio/raw").setSampleRate(1_000)
                    .setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
            ).setTimeline(timeline).setMediaPeriodId(MediaPeriodId(periodUid, 0L)).build()
        )
        accounting.boundary.flush(AudioProcessor.StreamMetadata.Builder().setTimeline(timeline).setPeriodUid(periodUid).build())
        val delegate = proxy<AudioOutput> { method, args ->
            when (method) {
                "write" -> {
                    val buffer = args!![0] as ByteBuffer
                    buffer.position(buffer.position() + minOf(1_000, buffer.remaining()))
                    !buffer.hasRemaining()
                }
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                else -> null
            }
        }
        val provider = proxy<AudioOutputProvider> { method, _ -> if (method == "getAudioOutput") delegate else null }
        val output = accounting.wrapProvider(provider).getAudioOutput(
            AudioOutputProvider.OutputConfig.Builder().setEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(1_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        )
        val buffer = ByteBuffer.allocateDirect(2_000)
        assertTrue(!output.write(buffer, 1, 0))
        assertTrue(events.isEmpty())
        output.play()
        assertTrue(output.write(buffer, 1, 0))
        listening.finish(ListeningEnd.Natural)
        assertEquals("1", field(events.last(), "time"))
        assertEquals("1", field(events.last(), "realtime"))
        assertEquals(2, events.size)
    }

    @Test
    fun realAudioTrackKeepsSameFormatTracksSeparateThroughSeekAndSoftwareSpeed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val events = CopyOnWriteArrayList<ListeningEvent>()
        val errors = CopyOnWriteArrayList<String>()
        val listening = PlaybackListeningSession(emit = events::add, checkpoint = {})
        listening.setTracks(listOf(track(10), track(20)))
        val accounting = PlaybackOutputAccounting(listening)
        val ready = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                accounting.wrapSink(DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(accounting.boundary))
                    .setEnableAudioOutputPlaybackParameters(false)
                    .setAudioOutputProvider(accounting.wrapProvider(AudioTrackAudioOutputProvider.Builder(context).build()))
                    .build())
        }
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context, renderers)
                    .setMediaSourceFactory(DefaultMediaSourceFactory({ ByteArrayDataSource(wav()) }))
                    .build().apply {
                        volume = 0f
                        addListener(object : Player.Listener {
                            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                                listening.transition(mediaItem?.queueEntryUid, ListeningEnd.Natural)
                            }
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) ready.countDown()
                                if (playbackState == Player.STATE_ENDED) {
                                    listening.finish(ListeningEnd.Natural)
                                    ended.countDown()
                                }
                            }
                            override fun onPlayerError(error: PlaybackException) {
                                errors += error.toString()
                                ready.countDown()
                                ended.countDown()
                            }
                        })
                        setMediaItems(listOf(Song(1).toMediaItem(10), Song(2).toMediaItem(20)))
                        prepare()
                    }
            }
            assertTrue("Paused preparation did not complete", ready.await(10, TimeUnit.SECONDS))
            assertTrue("Paused preparation reported a play", events.isEmpty())
            instrumentation.runOnMainSync {
                requireNotNull(player).apply { seekTo(1_000); setPlaybackSpeed(2f); play() }
            }
            assertTrue("Local playback did not end", ended.await(15, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { player?.release() }
        }
        assertTrue(errors.joinToString(), errors.isEmpty())
        val finished = events.filter { it.payload["action"]!!.jsonPrimitive.content == "play" }
        assertEquals(2, finished.size)
        assertEquals(listOf("10", "20"), finished.map { field(it, "sourceId") })
        // Sonic 尾部会舍入少量帧，整数秒边界允许相差一秒。
        assertTrue(field(finished[0], "time").toLong() in 2L..3L)
        assertTrue(field(finished[1], "time").toLong() in 3L..4L)
        assertTrue(field(finished[0], "realtime").toLong() <= 1L)
        assertTrue(field(finished[1], "realtime").toLong() in 1L..2L)
    }

    private fun track(uid: Long) = ListeningTrack(uid, uid, 7, "list", uid.toString(), 4_000, "loop")

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> call(method.name, args) } as T

    private fun field(event: ListeningEvent, name: String) = event.payload["json"]!!.jsonObject[name]!!.jsonPrimitive.content

    private fun wav(): ByteArray = ByteBuffer.allocate(44 + 48_000 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(capacity() - 8)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(48_000)
        putInt(96_000)
        putShort(2)
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(capacity() - 44)
        repeat(48_000 * 4) { putShort(1_000) }
    }.array()
}
