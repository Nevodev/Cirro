package com.nevoit.cirro.playback

import android.content.Context
import android.media.MediaMetadata as PlatformMetadata
import android.media.session.MediaController as PlatformController
import android.media.session.PlaybackState as PlatformState
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class PlaybackSessionTest {
    @Test
    fun platformOmitsLargeQueueWhileAppRetainsTimelineAndTransportControls() {
        withSession { player, app, notification, platform ->
            onMain { player.load(2182) }
            await {
                onMain { app.mediaItemCount == 2182 } && platform.metadata != null
            }
            onMain {
                assertTrue(app.isCommandAvailable(Player.COMMAND_GET_TIMELINE))
                assertFalse(notification.isCommandAvailable(Player.COMMAND_GET_TIMELINE))
                assertTrue(notification.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                assertTrue(notification.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                assertTrue(notification.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
            }
            assertTrue(platform.queue.isNullOrEmpty())
            assertEquals(120_000L, requireNotNull(platform.metadata).getLong(PlatformMetadata.METADATA_KEY_DURATION))
            assertTrue(requireNotNull(platform.playbackState).actions and PlatformState.ACTION_SEEK_TO != 0L)
        }
    }

    @Test
    fun platformProgressAdvancesAndSeekReachesPlayerWithoutPublishingQueue() {
        withSession { player, _, _, platform ->
            onMain { player.load(3) }
            await { platform.metadata?.getLong(PlatformMetadata.METADATA_KEY_DURATION) == 120_000L }
            platform.transportControls.play()
            await { platform.playbackState?.state == PlatformState.STATE_PLAYING }
            val state = requireNotNull(platform.playbackState)
            assertEquals(1f, state.playbackSpeed, 0f)
            await {
                val projectedPosition = state.position +
                    (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed
                projectedPosition > state.position + 100
            }
            platform.transportControls.seekTo(30_000L)
            await { onMain { player.lastSeekPositionMs == 30_000L } }
            await { (platform.playbackState?.position ?: 0L) >= 30_000L }
            platform.transportControls.pause()
            await { platform.playbackState?.state == PlatformState.STATE_PAUSED }
            assertEquals(0f, requireNotNull(platform.playbackState).playbackSpeed, 0f)
            assertTrue(platform.queue.isNullOrEmpty())
        }
    }

    private fun withSession(
        check: (TestPlayer, MediaController, MediaController, PlatformController) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val player = onMain { TestPlayer() }
        val session = onMain {
            MediaSession.Builder(context, player)
                .setId("playback-session-test")
                .setCallback(PlaybackSessionCallback())
                .build()
        }
        var app: MediaController? = null
        var notification: MediaController? = null
        try {
            app = connect(context, session, notification = false)
            notification = connect(context, session, notification = true)
            val platform = PlatformController(context, session.platformToken)
            check(player, app, notification, platform)
        } finally {
            onMain {
                notification?.release()
                app?.release()
                session.release()
                player.release()
            }
        }
    }

    private fun connect(context: Context, session: MediaSession, notification: Boolean): MediaController =
        onMain {
            MediaController.Builder(context, session.token)
                .setConnectionHints(Bundle().apply {
                    putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, notification)
                })
                .buildAsync()
        }.get(5, TimeUnit.SECONDS)

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000L
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Session update timed out" }
            SystemClock.sleep(10)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(Callable(block))
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        return task.get()
    }

    private class TestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var currentState = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .build()
        var lastSeekPositionMs = C.TIME_UNSET
            private set

        fun load(count: Int) {
            currentState = currentState.buildUpon()
                .setPlaylist(List(count) { index ->
                    MediaItemData.Builder(index)
                        .setMediaItem(MediaItem.Builder()
                            .setMediaId(index.toString())
                            .setMediaMetadata(MediaMetadata.Builder().setTitle("Track $index").build())
                            .build())
                        .setDurationUs(120_000_000L)
                        .setIsSeekable(true)
                        .build()
                })
                .setCurrentMediaItemIndex(0)
                .setPlaybackState(Player.STATE_READY)
                .setContentPositionMs(1000L)
                .build()
            invalidateState()
        }

        override fun getState(): State = currentState

        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            currentState = currentState.buildUpon()
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            lastSeekPositionMs = positionMs
            currentState = currentState.buildUpon()
                .setCurrentMediaItemIndex(mediaItemIndex)
                .setContentPositionMs(positionMs)
                .build()
            return Futures.immediateVoidFuture()
        }
    }
}
