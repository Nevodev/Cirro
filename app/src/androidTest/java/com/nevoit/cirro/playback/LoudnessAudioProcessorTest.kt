package com.nevoit.cirro.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.test.platform.app.InstrumentationRegistry
import com.nevoit.cirro.data.model.Song
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class LoudnessAudioProcessorTest {
    @Test
    fun preloadingDoesNotChangeTheCurrentStreamAndNextStreamStartsWithItsOwnGain() {
        val processor = processor()
        processor.rememberTrack("1@10", db(0.5), null)
        processor.flush(stream(1, 10))
        assertEquals(5_000, sample(processor))

        processor.rememberTrack("2@20", db(0.25), null)
        assertEquals(5_000, sample(processor))
        processor.flush(stream(2, 20))
        assertEquals(2_500, sample(processor))
        assertEquals(2_500, sample(processor))
    }

    @Test
    fun duplicateSongsKeepTheGainOfTheirOwnResolvedQuality() {
        val processor = processor()
        processor.rememberTrack("1@10", db(0.5), null)
        processor.rememberTrack("1@20", db(0.25), null)
        processor.flush(stream(1, 10))
        assertEquals(5_000, sample(processor))
        processor.flush(stream(1, 20))
        assertEquals(2_500, sample(processor))
        processor.flush(stream(1, 10))
        assertEquals(5_000, sample(processor))
    }

    @Test
    fun missingMetadataAndMissingGainUseTheBaselineInsteadOfThePreviousSong() {
        val processor = processor()
        processor.rememberTrack("1@10", db(0.5), null)
        processor.flush(stream(1, 10))
        assertEquals(5_000, sample(processor))
        processor.flush(stream(2, 20))
        assertEquals(10_000, sample(processor))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertEquals(10_000, sample(processor))
    }

    @Test
    fun automaticTransitionWithTheSamePcmFormatAppliesGainBeforeTheFirstSample() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val loudness = LoudnessAudioProcessor()
        val checked = mutableSetOf<String>()
        val failures = ConcurrentLinkedQueue<String>()
        val ended = CountDownLatch(1)
        val probe = object : BaseAudioProcessor() {
            private var key: String? = null

            override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = inputAudioFormat

            override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
                val uid = streamMetadata.periodUid ?: return
                val period = streamMetadata.timeline.getPeriodByUid(uid, Timeline.Period())
                key = streamMetadata.timeline.getWindow(period.windowIndex, Timeline.Window())
                    .mediaItem.localConfiguration?.customCacheKey
            }

            override fun queueInput(inputBuffer: ByteBuffer) {
                val output = replaceOutputBuffer(inputBuffer.remaining())
                val expected = if (key == "1@10") 5_000 else 2_500
                while (inputBuffer.hasRemaining()) {
                    val value = inputBuffer.short
                    if (value.toInt() != expected && failures.isEmpty()) {
                        failures += "$key: expected $expected, got $value"
                    }
                    output.putShort(value)
                }
                key?.let(checked::add)
                output.flip()
            }
        }
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(loudness, probe))
                .build()
        }
        val resolver = ResolvingDataSource.Resolver { spec ->
            loudness.rememberTrack(requireNotNull(spec.key), db(if (spec.key == "1@10") 0.5 else 0.25), null)
            spec
        }
        val source = DefaultMediaSourceFactory(
            ResolvingDataSource.Factory({ ByteArrayDataSource(wav()) }, resolver)
        )
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context, renderers)
                    .setMediaSourceFactory(source)
                    .build().apply {
                        volume = 0f
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) ended.countDown()
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                failures += error.toString()
                                ended.countDown()
                            }
                        })
                        setMediaItems(listOf(Song(1).toMediaItem(10), Song(2).toMediaItem(20)))
                        prepare()
                        play()
                    }
            }
            assertTrue("Playback did not finish", ended.await(15, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { player?.release() }
        }
        assertTrue(failures.joinToString(), failures.isEmpty())
        assertEquals(setOf("1@10", "2@20"), checked)
    }

    private fun processor() = LoudnessAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
    }

    private fun stream(id: Long, uid: Long): AudioProcessor.StreamMetadata {
        val timeline = SinglePeriodTimeline(1_000_000, true, false, false, null, Song(id).toMediaItem(uid))
        return AudioProcessor.StreamMetadata.Builder()
            .setTimeline(timeline)
            .setPeriodUid(timeline.getUidOfPeriod(0))
            .build()
    }

    private fun sample(processor: LoudnessAudioProcessor): Int {
        val input = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(10_000)
        input.flip()
        processor.queueInput(input)
        return processor.output.short.toInt()
    }

    private fun db(gain: Double) = 20.0 * log10(gain)

    private fun wav(): ByteArray = ByteBuffer.allocate(44 + 48_000)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
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
            putInt(48_000)
            repeat(24_000) { putShort(10_000) }
        }.array()
}
