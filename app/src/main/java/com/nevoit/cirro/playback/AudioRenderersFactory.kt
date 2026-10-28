package com.nevoit.cirro.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider

/**
 * 给音频输出链挂上自定义的 [androidx.media3.common.audio.AudioProcessor]。
 *
 * 只能走这条路：`ExoPlayer.Builder` 上没有挂处理器的口子，`AudioSink` 是在 RenderersFactory
 * 里造出来的，只有 [buildAudioSink] 这个 protected 方法能插进去。
 *
 * 不用手动补 Sonic：`setAudioProcessors` 传进去的数组会被
 * `DefaultAudioProcessorChain` 当成"额外的处理器"，它自己还会补上 Sonic（变速不变调）和静音跳过。
 * 手动再塞一个 Sonic 会让链上出现两个。
 *
 * `setEnableAudioOutputPlaybackParameters` 用的是官方默认实现那一支——废弃的
 * `setEnableAudioTrackPlaybackParams` 写的是同一个字段，纯改名。
 */
@UnstableApi
class AudioRenderersFactory internal constructor(
    context: Context,
    private val loudness: LoudnessAudioProcessor,
    private val beatAnalyzer: PcmBeatAudioProcessor,
    private val outputAccounting: PlaybackOutputAccounting? = null,
) : DefaultRenderersFactory(context) {

    protected override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setAudioProcessors(listOfNotNull(loudness, beatAnalyzer, outputAccounting?.boundary).toTypedArray())
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
        outputAccounting?.let { accounting ->
            builder.setAudioOutputProvider(accounting.wrapProvider(AudioTrackAudioOutputProvider.Builder(context).build()))
        }
        val sink = builder.build()
        return outputAccounting?.wrapSink(sink) ?: sink
    }
}
