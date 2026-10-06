package com.nevoit.material.core.utility

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer

// Packs a colour that is already fixed at composition time. For the defaults of parameters that
// want a producer; a colour that animates should be passed as `{ state.value }` instead.
@Composable
fun rememberColorProducer(color: Color): ColorProducer =
    remember(color) { ColorProducer { color } }

// Packs an animated colour state. The state object itself is remembered upstream (see
// animateColorAsState), so the producer keeps one identity across recompositions and never
// forces work on whoever compares it.
@Composable
fun rememberColorProducer(state: State<Color>): ColorProducer =
    remember(state) { ColorProducer { state.value } }
