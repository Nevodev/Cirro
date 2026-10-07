package com.nevoit.cirro.lyrics.core.model.karaoke.mapper

import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeAlignment
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeLine
import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeSyllable
import com.nevoit.cirro.lyrics.core.model.synced.SyncedLine


fun SyncedLine.toKaraokeLine(): KaraokeLine {
    return KaraokeLine.MainKaraokeLine(
        syllables = listOf(
            KaraokeSyllable(
                this.content,
                this.start,
                this.end
            )
        ),
        translation = this.translation,
        alignment = KaraokeAlignment.Unspecified,
        start = this.start,
        end = this.end
    )
}