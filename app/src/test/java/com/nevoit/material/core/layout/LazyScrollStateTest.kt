package com.nevoit.material.core.layout

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyScrollStateTest {
    @Test
    fun restoredListPositionPassesWithoutMeasuringThePrefix() {
        val list = LazyListState(firstVisibleItemIndex = 8)
        val gate = list.scrolledPastState(1000f)
        assertTrue(gate.value)
        list.requestScrollToItem(0)
        assertFalse(gate.value)
        list.requestScrollToItem(1)
        assertTrue(gate.value)
    }

    @Test
    fun listOffsetCrossesStrictThresholdInBothDirections() {
        val list = LazyListState(firstVisibleItemScrollOffset = 100)
        val gate = list.scrolledPastState(100f)
        assertFalse(gate.value)
        list.requestScrollToItem(0, 101)
        assertTrue(gate.value)
        list.requestScrollToItem(0, 100)
        assertFalse(gate.value)
    }

    @Test
    fun restoredGridPositionPassesWithoutMeasuringThePrefix() {
        val grid = LazyGridState(firstVisibleItemIndex = 8)
        val gate = grid.scrolledPastState(1000f)
        assertTrue(gate.value)
        grid.requestScrollToItem(0)
        assertFalse(gate.value)
        grid.requestScrollToItem(1)
        assertTrue(gate.value)
    }

    @Test
    fun gridOffsetCrossesStrictThresholdInBothDirections() {
        val grid = LazyGridState(firstVisibleItemScrollOffset = 100)
        val gate = grid.scrolledPastState(100f)
        assertFalse(gate.value)
        grid.requestScrollToItem(0, 101)
        assertTrue(gate.value)
        grid.requestScrollToItem(0, 100)
        assertFalse(gate.value)
    }
}
