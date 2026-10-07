package com.nevoit.cirro.lyrics.ui.preparation

import com.nevoit.cirro.lyrics.core.model.karaoke.KaraokeSyllable
import com.nevoit.cirro.lyrics.ui.profile.ProfileAnimationUnit

data class MeasuredSyllable(
    val source: KaraokeSyllable,
    val textStart: Int,
    val textEnd: Int,
    val animation: ProfileAnimationUnit,
)
