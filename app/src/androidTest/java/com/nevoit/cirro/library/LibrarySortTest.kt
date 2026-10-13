package com.nevoit.cirro.library

import com.nevoit.cirro.data.sortedByLibraryName
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortTest {
    @Test
    fun chineseAndLatinNamesShareTheSameAlphabeticalOrder() {
        val names = listOf("中国", "Taylor Swift", "上海", "Coldplay", "北京", "安静", "Adele")
        assertEquals(
            listOf("Adele", "安静", "北京", "Coldplay", "上海", "Taylor Swift", "中国"),
            names.sortedByLibraryName { it },
        )
    }

    @Test
    fun numbersStayBeforeLettersAndAccentsDoNotCreateSeparateGroups() {
        val names = listOf("周杰伦", "Édith Piaf", "1975", "ADÉLA", "张学友", "Zara")
        assertEquals(
            listOf("1975", "ADÉLA", "Édith Piaf", "Zara", "张学友", "周杰伦"),
            names.sortedByLibraryName { it },
        )
    }

    @Test
    fun latinNamesUseAlphabeticalOrderIgnoringCaseAndOuterSpaces() {
        val names = listOf("Zebra", " banana ", "apple", "Cherry")
        assertEquals(listOf("apple", " banana ", "Cherry", "Zebra"), names.sortedByLibraryName { it })
    }

    @Test
    fun chineseNamesUsePinyinOrder() {
        val names = listOf("中国", "北京", "上海", "安静")
        assertEquals(listOf("安静", "北京", "上海", "中国"), names.sortedByLibraryName { it })
    }

    @Test
    fun equalNamesKeepTheirRelativeOrder() {
        val items = listOf(1 to "Beta", 2 to "Alpha", 3 to "alpha")
        assertEquals(listOf(2, 3, 1), items.sortedByLibraryName { it.second }.map { it.first })
    }
}
