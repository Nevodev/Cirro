package com.nevoit.cirro.lyrics.ui.internal.rendering

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLine
import com.nevoit.cirro.lyrics.ui.preparation.PreparedLyrics
import com.nevoit.cirro.lyrics.ui.preparation.PreparedRow

/** Fully prepared on a worker and retained above lazy items for one rendering host. */
internal class LyricsRenderResources(
    prepared: PreparedLyrics,
    val color: Color,
    density: Density,
    direction: LayoutDirection,
) {
    val paints = RowPaints(color)
    private val rasters = prepared.allLines.associateWith {
        prepareLineRaster(it, color, density, direction, paints)
    }
    private val rows = prepared.allLines.flatMap { it.rows }.associateWith { RowRenderState(it) }

    fun raster(line: PreparedLine): PreparedLineRaster = rasters.getValue(line)
    fun row(row: PreparedRow): RowRenderState = rows.getValue(row)
}
