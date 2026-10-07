package com.nevoit.cirro.lyrics.ui.profile

import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeSyllable
import com.nevoit.cirro.lyrics.ui.internal.text.*

/** Keeps contextual shaping and lifts original syllables as a whole. */
object CyrillicProfile : WordLevelLyricsProfile() {
    override fun matches(syllable: KaraokeSyllable): Boolean =
        syllable.content.any {
            it.code in 0x0400..0x052F || it.code in 0x2DE0..0x2DFF || it.code in 0xA640..0xA69F
        }
}
