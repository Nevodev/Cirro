package com.nevoit.cirro.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.nevoit.cirro.data.PlaybackSnapshot
import com.nevoit.cirro.data.model.PlayMode
import com.nevoit.cirro.data.model.QueueSource
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.playQueueOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(UnstableApi::class)
class PlaybackRestorationTest {
    @Test
    fun restoresPausedMetadataAndPositionWithoutLoadingAndNeverReplacesALivePlaylist() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val queue = playQueueOf(
            listOf(Song(42, "First"), Song(42, "Duplicate"), Song(99, "Last")),
            1, QueueSource(title = "Saved playlist"), mode = PlayMode.REPEAT_ALL,
        )
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(instrumentation.targetContext).build()
            try {
                player.restorePlayback(PlaybackSnapshot(queue, 37_000))
                assertEquals(3, player.mediaItemCount)
                assertEquals(1, player.currentMediaItemIndex)
                assertEquals(queue.entries[1].uid, player.currentMediaItem?.queueEntryUid)
                assertEquals("Duplicate", player.currentMediaItem?.mediaMetadata?.title.toString())
                assertEquals(37_000L, player.currentPosition)
                assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
                assertEquals(Player.STATE_IDLE, player.playbackState)
                assertFalse(player.playWhenReady)
                assertFalse(player.isPlaying)

                player.seekTo(2, 12_000)
                player.restorePlayback(PlaybackSnapshot(queue, 0))
                assertEquals(2, player.currentMediaItemIndex)
                assertEquals(12_000L, player.currentPosition)
            } finally {
                player.release()
            }
        }
    }
}
