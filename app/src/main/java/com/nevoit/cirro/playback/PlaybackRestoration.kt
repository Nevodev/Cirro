package com.nevoit.cirro.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.nevoit.cirro.data.PlaybackSnapshot
import com.nevoit.cirro.data.model.PlayMode
import com.nevoit.cirro.data.model.PlaybackQueue

@OptIn(UnstableApi::class)
internal fun Player.restorePlayback(snapshot: PlaybackSnapshot) {
    if (mediaItemCount != 0 || snapshot.queue.entries.isEmpty()) return
    val queue = snapshot.queue
    setMediaItems(queue.entries.map { it.song.toMediaItem(it.uid) }, queue.currentIndex, snapshot.positionMs)
    repeatMode = queue.repeatMode()
    // 只恢复队列和游标，不 prepare/play；用户点击播放后再加载音频。
}

/** 随机顺序已经体现在队列中，播放器只负责循环这一轴。 */
internal fun PlaybackQueue.repeatMode(): Int = when (mode) {
    PlayMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
    PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
    PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
}
