package com.nevoit.cirro.lyrics.core.model.synced

import com.nevoit.cirro.lyrics.core.model.ISyncedLine
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine

data class SyncedLine(
    val content: String,
    val translation: String?,
    override val start: Int,
    override val end: Int,
) : ISyncedLine {
    override val duration = end - start
    init {
        require(end >= start)
    }
}

data class UncheckedSyncedLine(
    val content: String,
    val translation: String?,
    override val start: Int,
    override val end: Int,
) : ISyncedLine {
    override val duration = (end - start).takeIf { it >= 0 } ?: 0

    fun toSyncedLine():SyncedLine {
        return SyncedLine(
            this.content,
            this.translation,
            this.start,
            this.end
        )
    }
}