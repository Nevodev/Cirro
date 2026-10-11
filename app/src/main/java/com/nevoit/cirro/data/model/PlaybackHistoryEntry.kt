package com.nevoit.cirro.data.model

data class PlaybackHistoryEntry(
    val id: Long,
    val entry: QueueEntry,
    val startedAtMillis: Long,
)
