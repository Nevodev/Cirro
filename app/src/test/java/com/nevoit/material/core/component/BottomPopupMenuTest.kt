package com.nevoit.material.core.component

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomPopupMenuTest {
    @Test
    fun dismissalKeepsContentUntilExitFinishes() {
        val state = BottomPopupMenuHostState()
        val request = request()
        state.show(request)
        state.dismiss(request.owner)
        assertFalse(state.expanded)
        assertSame(request, state.request)
        state.finishDismiss(request)
        assertNull(state.request)
    }

    @Test
    fun disposingAnOldCallerCannotDismissTheNewCallersMenu() {
        val state = BottomPopupMenuHostState()
        val old = request()
        val current = request()
        state.show(old)
        state.show(current)
        state.dismiss(old.owner)
        assertTrue(state.expanded)
        assertSame(current, state.request)
    }

    @Test
    fun oldExitCompletionCannotRemoveAReplacementThatIsAlsoClosing() {
        val state = BottomPopupMenuHostState()
        val old = request()
        val current = request()
        state.show(old)
        state.dismiss(old.owner)
        state.show(current)
        state.dismiss(current.owner)
        state.finishDismiss(old)
        assertSame(current, state.request)
        state.finishDismiss(current)
        assertNull(state.request)
    }

    @Test
    fun reopeningSurvivesAnInterruptedExitCompletion() {
        val state = BottomPopupMenuHostState()
        val request = request()
        state.show(request)
        state.dismiss(request.owner)
        state.show(request)
        state.finishDismiss(request)
        assertTrue(state.expanded)
        assertSame(request, state.request)
    }

    @Test
    fun collapsedMenuLandsAtCallerCenterAcrossWindowOffsets() {
        val translation = bottomPopupMenuTranslation(
            originInWindow = Offset(350f, 140f),
            viewportPositionInWindow = Offset(32f, 52f),
            viewportSize = IntSize(376, 776),
            menuSize = IntSize(376, 200),
            progress = 0f,
        )
        val finalCenterInWindow = Offset(220f, 728f)
        assertEquals(Offset(350f, 140f), finalCenterInWindow + translation)
    }

    @Test
    fun collapsedMenuLandsAtCallerCenterWithoutReservingTheNavigationBar() {
        // An 800px-high host at (20, 40) reserves max(24px status bar, 12px padding)
        // at the top and only 12px padding at the bottom, even with a 30px navigation bar.
        val translation = bottomPopupMenuTranslation(
            originInWindow = Offset(350f, 140f),
            viewportPositionInWindow = Offset(32f, 64f),
            viewportSize = IntSize(376, 764),
            menuSize = IntSize(376, 200),
            progress = 0f,
        )
        val finalCenterInWindow = Offset(220f, 728f)
        assertEquals(Offset(350f, 140f), finalCenterInWindow + translation)
    }

    @Test
    fun fullyExpandedMenuHasNoTranslationEvenWhenCallerIsOutsideMenuBounds() {
        val translation = bottomPopupMenuTranslation(
            Offset(900f, 20f), Offset(30f, 50f), IntSize(1000, 600), IntSize(976, 250), 1f,
        )
        assertEquals(0f, translation.x, 0f)
        assertEquals(0f, translation.y, 0f)
    }

    private fun request() =
        BottomPopupMenuRequest(Any(), Offset.Zero, 12.dp, mutableStateOf(RectangleShape)) {}
}
