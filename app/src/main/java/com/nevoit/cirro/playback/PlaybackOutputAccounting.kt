package com.nevoit.cirro.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/** 与原版 native 一样只计算成功写入的音频，不能用解码输入量或主线程 isPlaying 时间替代。 */
@UnstableApi
internal class PlaybackOutputAccounting(private val listening: PlaybackListeningSession) {
    private var configured: ListeningStream? = null
    private var active: ListeningStream? = null
    private var speed = 1f
    private var sink: AudioSink? = null

    // 非 active 的处理器仍会收到 pipeline.flush，因而无需复制 PCM 或改变处理链输出。
    val boundary = object : BaseAudioProcessor() {
        override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
            val uid = itemUid(streamMetadata.timeline, streamMetadata.periodUid)
            active = configured?.takeIf { it.uid == uid }
            speed = sink?.playbackParameters?.speed ?: 1f
        }

        override fun queueInput(inputBuffer: ByteBuffer) = Unit
    }

    fun wrapSink(delegate: AudioSink): AudioSink {
        sink = delegate
        return object : ForwardingAudioSink(delegate) {
            override fun configure(config: AudioSink.AudioSinkConfig) {
                val uid = itemUid(config.timeline, config.mediaPeriodId?.periodUid)
                configured = uid?.let { ListeningStream(it, config.mediaPeriodId?.windowSequenceNumber ?: 0L) }
                super.configure(config)
            }

            override fun flush() {
                listening.flushed(active)
                super.flush()
            }
        }
    }

    fun wrapProvider(delegate: AudioOutputProvider): AudioOutputProvider =
        object : ForwardingAudioOutputProvider(delegate) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
                object : ForwardingAudioOutput(super.getAudioOutput(config)) {
                    private var playing = false
                    private val pending = mutableListOf<Write>()
                    private val frameSize = if (Util.isEncodingLinearPcm(config.encoding)) {
                        Util.getPcmFrameSize(config.encoding, Integer.bitCount(config.channelMask))
                    } else 0

                    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
                        val before = buffer.position()
                        val handled = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
                        val bytes = buffer.position() - before
                        if (bytes <= 0 || frameSize <= 0 || config.sampleRate <= 0) return handled
                        // 浮点输出绕过自定义 processor；该分支没有 Sonic 排空时序。
                        if (config.encoding == C.ENCODING_PCM_FLOAT && presentationTimeUs != C.TIME_END_OF_SOURCE) {
                            active = configured
                            speed = sink?.playbackParameters?.speed ?: 1f
                        }
                        val key = active ?: return handled
                        val effectiveSpeed = if (config.usePlaybackParameters) playbackParameters.speed else speed
                        val pcmUs = bytes.toDouble() * 1_000_000.0 / frameSize / config.sampleRate
                        val write = if (config.usePlaybackParameters) {
                            Write(key, pcmUs, pcmUs / effectiveSpeed, effectiveSpeed)
                        } else {
                            Write(key, pcmUs * effectiveSpeed, pcmUs, effectiveSpeed)
                        }
                        if (playing) submit(write) else {
                            val last = pending.lastOrNull()
                            if (last != null && last.stream == write.stream && last.speed == write.speed) {
                                pending[pending.lastIndex] = last.copy(timeUs = last.timeUs + write.timeUs, realUs = last.realUs + write.realUs)
                            } else pending += write
                        }
                        return handled
                    }

                    override fun play() {
                        super.play()
                        playing = true
                        pending.forEach(::submit)
                        pending.clear()
                    }

                    override fun pause() {
                        playing = false
                        super.pause()
                    }

                    override fun flush() {
                        pending.clear()
                        super.flush()
                    }

                    private fun submit(write: Write) = listening.output(write.stream, write.timeUs, write.realUs, write.speed)
                }
        }

    private data class Write(val stream: ListeningStream, val timeUs: Double, val realUs: Double, val speed: Float)

    private fun itemUid(timeline: Timeline, periodUid: Any?): Long? {
        if (periodUid == null || timeline.getIndexOfPeriod(periodUid) == C.INDEX_UNSET) return null
        val period = timeline.getPeriodByUid(periodUid, Timeline.Period())
        return timeline.getWindow(period.windowIndex, Timeline.Window()).mediaItem.queueEntryUid
    }
}
