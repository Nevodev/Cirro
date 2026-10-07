package com.nevoit.cirro.lyrics.ui.internal.text

import com.nevoit.cirro.lyrics.ui.preparation.MeasuredLyricsLine
import com.nevoit.cirro.lyrics.ui.profile.ProfileTextUnit

internal fun bindSyllableAnimations(line: MeasuredLyricsLine, units: List<ProfileTextUnit>) =
    units.map { unit ->
        val source = line.sourceFor(unit.start, unit.end)
        if (source == null) unit
        else unit.copy(animation = source.animation, sourceStart = source.source.start)
    }
