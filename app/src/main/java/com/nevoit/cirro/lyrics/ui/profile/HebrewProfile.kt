package com.nevoit.cirro.lyrics.ui.profile

import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeSyllable
import com.nevoit.cirro.lyrics.ui.internal.text.*

/** Keeps contextual shaping and lifts original syllables as a whole. */
object HebrewProfile : WordLevelLyricsProfile() {
    override fun matches(syllable: KaraokeSyllable): Boolean =
        syllable.content.any { it.code in 0x0590..0x05FF || it.code in 0xFB1D..0xFB4F }
}
