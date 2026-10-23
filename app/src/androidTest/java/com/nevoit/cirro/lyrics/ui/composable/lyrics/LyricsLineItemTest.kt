package com.nevoit.cirro.lyrics.ui.composable.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LyricsLineItemTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun indicationOpacityStaysConstantWhenFocusChanges() {
        var focused by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(240.dp).background(Color.Black)) {
                LyricsLineItem(
                    isFocused = focused,
                    isRightAligned = false,
                    onLineClicked = {},
                    onLinePressed = {},
                    blurRadius = { 0f },
                    inactiveAlpha = 0.3f,
                    modifier = Modifier.testTag("line"),
                ) {
                    Box(Modifier.height(80.dp))
                }
            }
        }

        compose.onNodeWithTag("line").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300L)
        val inactive = indicationAlpha()
        assertTrue(inactive > 0.06f)
        compose.runOnIdle { focused = true }
        compose.mainClock.advanceTimeBy(1000L)
        assertEquals(inactive, indicationAlpha(), 1f / 255f)
        compose.onNodeWithTag("line").performTouchInput { up() }
    }

    @Test
    fun shortTapCompletesTheHighlightBeforeFadingOut() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(240.dp).background(Color.Black)) {
                LyricsLineItem(
                    isFocused = true,
                    isRightAligned = false,
                    onLineClicked = {},
                    onLinePressed = {},
                    blurRadius = { 0f },
                    modifier = Modifier.testTag("line"),
                ) {
                    Box(Modifier.height(80.dp))
                }
            }
        }

        compose.onNodeWithTag("line").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(16L)
        compose.onNodeWithTag("line").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(150L)
        assertTrue(indicationAlpha() > 0.06f)
        compose.mainClock.advanceTimeBy(400L)
        assertEquals(0f, indicationAlpha(), 1f / 255f)
    }

    @Test
    fun consumedDownShowsPressFeedbackBeforeAnyMovementAndStillClicks() {
        var clicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(240.dp).background(Color.Black).consumeInitialDown()) {
                LyricsLineItem(
                    isFocused = true,
                    isRightAligned = false,
                    onLineClicked = { clicks++ },
                    onLinePressed = {},
                    blurRadius = { 0f },
                    modifier = Modifier.testTag("line"),
                ) {
                    Box(Modifier.height(80.dp))
                }
            }
        }

        compose.onNodeWithTag("line").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(48L)
        val pixels = compose.onNodeWithTag("line").captureToImage().toPixelMap()
        assertTrue(pixels[pixels.width / 2, pixels.height / 2].red > 0f)
        compose.onNodeWithTag("line").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun movementPastTouchSlopCancelsAClickEvenWhenDownWasConsumed() {
        var clicks = 0
        var movement = 0f
        compose.setContent {
            movement = with(LocalDensity.current) { 24.dp.toPx() }
            Box(Modifier.size(240.dp).consumeInitialDown()) {
                LyricsLineItem(
                    isFocused = true,
                    isRightAligned = false,
                    onLineClicked = { clicks++ },
                    onLinePressed = {},
                    blurRadius = { 0f },
                    modifier = Modifier.testTag("line"),
                ) {
                    Box(Modifier.height(120.dp))
                }
            }
        }

        compose.onNodeWithTag("line").performTouchInput {
            down(center)
            moveBy(Offset(0f, movement))
            up()
        }
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun tapLongPressAndAccessibilityClickKeepSeparateCallbacks() {
        var clicks = 0
        var longPresses = 0
        compose.setContent {
            LyricsLineItem(
                isFocused = true,
                isRightAligned = false,
                onLineClicked = { clicks++ },
                onLinePressed = { longPresses++ },
                blurRadius = { 0f },
                modifier = Modifier.testTag("line"),
            ) {
                Box(Modifier.height(80.dp))
            }
        }

        compose.onNodeWithTag("line").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithTag("line").performTouchInput { longClick(center) }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(1, longPresses)
        }
        compose.onNode(hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(2, clicks) }
    }

    @Test
    fun draggingFromTheLineScrollsItsParentWithoutClicking() {
        var clicks = 0
        var scrolled = { false }
        compose.setContent {
            val scroll = rememberScrollState()
            scrolled = { scroll.value > 0 }
            Box(Modifier.size(240.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    LyricsLineItem(
                        isFocused = true,
                        isRightAligned = false,
                        onLineClicked = { clicks++ },
                        onLinePressed = {},
                        blurRadius = { 0f },
                        modifier = Modifier.testTag("line"),
                    ) {
                        Box(Modifier.height(120.dp))
                    }
                    Box(Modifier.height(800.dp))
                }
            }
        }

        compose.onNodeWithTag("line").performTouchInput {
            swipe(start = center, end = center.copy(y = 0f), durationMillis = 200L)
        }
        compose.runOnIdle {
            assertTrue(scrolled())
            assertEquals(0, clicks)
        }
    }

    @Test
    fun callbackRecompositionDoesNotCancelAPress() {
        var generation by mutableIntStateOf(0)
        var clickedGeneration = -1
        compose.setContent {
            val current = generation
            LyricsLineItem(
                isFocused = true,
                isRightAligned = false,
                onLineClicked = { clickedGeneration = current },
                onLinePressed = {},
                blurRadius = { 0f },
                modifier = Modifier.testTag("line"),
            ) {
                Box(Modifier.height(80.dp))
            }
        }

        compose.onNodeWithTag("line").performTouchInput { down(center) }
        compose.runOnIdle { generation = 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("line").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, clickedGeneration) }
    }

    @Test
    fun disablingInteractionCancelsThePendingClick() {
        var interactive by mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            LyricsLineItem(
                isFocused = true,
                isRightAligned = false,
                onLineClicked = { clicks++ },
                onLinePressed = {},
                blurRadius = { 0f },
                isInteractive = interactive,
                modifier = Modifier.testTag("line"),
            ) {
                Box(Modifier.height(80.dp))
            }
        }

        compose.onNodeWithTag("line").performTouchInput { down(center) }
        compose.runOnIdle { interactive = false }
        compose.waitForIdle()
        compose.onNodeWithTag("line").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(0, clicks)
            interactive = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("line").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    private fun Modifier.consumeInitialDown() = pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                    if (change.changedToDownIgnoreConsumed()) change.consume()
                }
            }
        }
    }

    private fun indicationAlpha(): Float {
        val pixels = compose.onNodeWithTag("line").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2].red
    }
}
