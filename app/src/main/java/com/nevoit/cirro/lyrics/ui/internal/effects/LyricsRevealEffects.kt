package com.nevoit.cirro.lyrics.ui.internal.effects

import com.nevoit.material.theme.tokens.Springs

internal val LyricsRevealSpring =
    Springs.smooth<Float>(500)

internal val BgvExitSpring =
    Springs.smooth<Float>(300)

internal fun revealScale(progress: Float) = 0.8f + 0.2f * progress.coerceIn(0f, 1f)

internal fun revealAlpha(progress: Float) = (progress / 0.65f).coerceIn(0f, 1f)

internal fun revealBlurIndex(progress: Float) = ((1f - progress.coerceIn(0f, 1f)) * 64f).toInt()
