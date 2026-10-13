package com.nevoit.cirro.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumDisplayTitleTest {
    @Test
    fun singleTypeAddsSuffixIgnoringCase() {
        assertEquals("Release - Single", albumDisplayTitle("Release", "single"))
    }

    @Test
    fun existingSuffixIsNotRepeated() {
        assertEquals("Release - Single", albumDisplayTitle("Release - Single", "Single"))
    }

    @Test
    fun otherOrUnknownTypesKeepOriginalTitle() {
        listOf("专辑", "EP", "", null).forEach { type ->
            assertEquals("Release", albumDisplayTitle("Release", type))
        }
        assertEquals("", albumDisplayTitle("", "Single"))
    }
}
