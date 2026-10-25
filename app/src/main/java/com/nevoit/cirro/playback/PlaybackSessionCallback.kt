package com.nevoit.cirro.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
internal class PlaybackSessionCallback : MediaSession.Callback {
    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.ConnectionResult> {
        val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
        if (session.isMediaNotificationController(controller) ||
            controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION
        ) {
            // The notification controller also configures the platform compatibility session.
            // Publishing thousands of queue items there blocks the main thread in setQueue().
            // Current metadata, position and transport commands do not require the full timeline.
            val defaults = result.build().availablePlayerCommands
            result.setAvailablePlayerCommands(
                defaults.buildUpon().remove(Player.COMMAND_GET_TIMELINE).build()
            )
        }
        return Futures.immediateFuture(result.build())
    }
}
