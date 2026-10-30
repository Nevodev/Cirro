package com.nevoit.material.core.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PopupMenuAnimationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun entranceScalesWithoutRecomposition() {
        showMenu()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Open").performClick()
        advanceFrames(3)
        val menu = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "菜单")
        val before = compose.onNode(menu).fetchSemanticsNode().boundsInRoot
        val changes = compose.runOnIdle { Recomposer.runningRecomposers.value.sumOf { it.changeCount } }
        advanceFrames(8)
        val after = compose.onNode(menu).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertEquals(changes, Recomposer.runningRecomposers.value.sumOf { it.changeCount })
            assertTrue(after.width > before.width)
            assertTrue(after.center.y > before.center.y)
        }
        advanceFrames(90)
        compose.onNodeWithContentDescription("关闭菜单").performClick()
        advanceFrames(3)
        val exitChanges = compose.runOnIdle { Recomposer.runningRecomposers.value.sumOf { it.changeCount } }
        advanceFrames(6)
        compose.runOnIdle {
            assertEquals(exitChanges, Recomposer.runningRecomposers.value.sumOf { it.changeCount })
        }
    }

    @Test
    fun submenuKeepsTheSelectedItemAsItsFirstRowAndCanReturnToTheParent() {
        var selected = false
        compose.setContent {
            PopupMenuHost {
                PopupMenu(
                    listOf(
                        PopupMenuEntry.Item("Artist", subtitle = "Current artist", submenu = listOf(PopupMenuEntry.Item("Name") { selected = true })),
                        PopupMenuEntry.Item("Album"),
                    ),
                ) { open -> Text("Open", Modifier.clickable(onClick = open)) }
            }
        }
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Artist").performClick()
        compose.runOnIdle { assertTrue(!selected) }
        compose.onNodeWithText("Artist").assertExists()
        compose.onNodeWithText("Current artist").assertExists()
        compose.onNodeWithText("Album").assertDoesNotExist()
        compose.onNodeWithContentDescription("收起子菜单").performClick()
        compose.onNodeWithText("Album").assertExists()
        compose.onNodeWithText("Artist").performClick()
        compose.onNodeWithContentDescription("返回上一级菜单").performClick()
        compose.onNodeWithText("Album").assertExists()
        compose.onNodeWithText("Artist").performClick()
        compose.onNodeWithText("Name").performClick()
        compose.runOnIdle { assertTrue(selected) }
    }

    @Test
    fun menuWrapsContentAboveMinimumAndKeepsSubmenuAtParentWidth() {
        val longLabel = "A longer menu item label that exceeds the default maximum width"
        compose.setContent {
            PopupMenuHost {
                Column {
                    PopupMenu(listOf(PopupMenuEntry.Item("A")), minWidth = 120.dp) { open ->
                        Text("Open short", Modifier.clickable(onClick = open))
                    }
                    PopupMenu(
                        listOf(
                            PopupMenuEntry.Item(
                                longLabel,
                                submenu = listOf(PopupMenuEntry.Item("A much longer submenu item label")),
                            ),
                        ),
                        minWidth = 120.dp,
                    ) { open -> Text("Open long", Modifier.clickable(onClick = open)) }
                }
            }
        }
        val menu = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "菜单")
        val submenu = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "子菜单")
        compose.onNodeWithText("Open short").performClick()
        val shortWidth = compose.onNode(menu).fetchSemanticsNode().boundsInRoot.width
        assertEquals(with(compose.density) { 120.dp.toPx() }, shortWidth, 1f)
        compose.onNodeWithContentDescription("关闭菜单").performClick()
        compose.onNodeWithText("Open long").performClick()
        val longWidth = compose.onNode(menu).fetchSemanticsNode().boundsInRoot.width
        assertTrue(longWidth > shortWidth)
        val screenWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue(longWidth <= screenWidth - with(compose.density) { 24.dp.toPx() } + 1f)
        assertTrue(longWidth <= with(compose.density) { PopupMenuDefaults.Width.toPx() } + 1f)
        compose.onNodeWithText(longLabel).performClick()
        val childWidth = compose.onNode(submenu).fetchSemanticsNode().boundsInRoot.width
        assertEquals(longWidth, childWidth, 1f)
    }

    @Test
    fun submenuMorphDoesNotRecomposeEachFrame() {
        compose.setContent {
            PopupMenuHost {
                PopupMenu(listOf(PopupMenuEntry.Item("Artist", submenu = listOf(PopupMenuEntry.Item("Name"))))) { open ->
                    Text("Open", Modifier.clickable(onClick = open))
                }
            }
        }
        compose.onNodeWithText("Open").performClick()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Artist").performClick()
        advanceFrames(3)
        val changes = compose.runOnIdle { Recomposer.runningRecomposers.value.sumOf { it.changeCount } }
        advanceFrames(8)
        compose.runOnIdle {
            assertEquals(changes, Recomposer.runningRecomposers.value.sumOf { it.changeCount })
        }
    }

    @Test
    fun submenuReusesTheParentRowCompositionWhenOpeningAndReturning() {
        var creations = 0
        var disposals = 0
        compose.setContent {
            PopupMenuHost {
                PopupMenu(
                    listOf(
                        PopupMenuEntry.Item(
                            "Artist",
                            submenu = listOf(PopupMenuEntry.Item("Name")),
                            leadingContent = {
                                val identity = remember { ++creations }
                                DisposableEffect(identity) { onDispose { disposals++ } }
                                Box(Modifier.size(40.dp))
                            },
                        ),
                    ),
                ) { open -> Text("Open", Modifier.clickable(onClick = open)) }
            }
        }
        compose.onNodeWithText("Open").performClick()
        repeat(2) {
            compose.onNodeWithText("Artist").performClick()
            compose.onNodeWithText("Name").assertExists()
            compose.runOnIdle {
                assertEquals(1, creations)
                assertEquals(0, disposals)
            }
            compose.onNodeWithContentDescription("返回上一级菜单").performClick()
            compose.onNodeWithText("Name").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(1, creations)
                assertEquals(0, disposals)
            }
        }
    }

    @Test
    fun tintedIconStillDrawsAfterMovingBetweenParentsAtTheSameSize() {
        val secondParent = mutableStateOf(false)
        compose.setContent {
            val icon = remember {
                movableContentOf {
                    Icon(
                        painter = ColorPainter(Color.White),
                        tint = { Color.Red },
                        contentDescription = null,
                        modifier = Modifier.size(24.dp).testTag("Moving icon"),
                    )
                }
            }
            Column(Modifier.background(Color.Black)) {
                Box(Modifier.size(48.dp)) { if (!secondParent.value) icon() }
                Box(Modifier.size(48.dp)) { if (secondParent.value) icon() }
            }
        }
        repeat(3) {
            val pixels = compose.onNodeWithTag("Moving icon").captureToImage().toPixelMap()
            assertEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])
            compose.runOnIdle { secondParent.value = !secondParent.value }
        }
    }

    @Test
    fun returningSubmenuCanReopenBeforeItsCollapseFinishes() {
        compose.setContent {
            PopupMenuHost {
                PopupMenu(
                    listOf(
                        PopupMenuEntry.Item("Artist", submenu = listOf(PopupMenuEntry.Item("Name"))),
                        PopupMenuEntry.Item("Album"),
                    ),
                ) { open -> Text("Open", Modifier.clickable(onClick = open)) }
            }
        }
        compose.onNodeWithText("Open").performClick()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Artist").performClick()
        advanceFrames(5)
        compose.onNodeWithContentDescription("返回上一级菜单").performClick()
        advanceFrames(3)
        compose.onNodeWithText("Album").assertExists()
        compose.onNode(
            hasText("Artist") and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "菜单")),
        ).performClick()
        advanceFrames(90)
        compose.onNodeWithText("Album").assertDoesNotExist()
        compose.onNodeWithText("Name").assertExists()
        compose.onNodeWithContentDescription("收起子菜单").performClick()
        advanceFrames(90)
        compose.onNodeWithText("Album").assertExists()
    }

    @Test
    fun outsideClickDuringSubmenuCollapseImmediatelyStartsClosingTheRoot() {
        compose.setContent {
            PopupMenuHost {
                PopupMenu(
                    listOf(PopupMenuEntry.Item("Artist", submenu = listOf(PopupMenuEntry.Item("Name")))),
                ) { open -> Text("Open", Modifier.clickable(onClick = open)) }
            }
        }
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Artist").performClick()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("返回上一级菜单").performClick()
        advanceFrames(2)
        compose.onNodeWithContentDescription("关闭菜单").performClick()
        advanceFrames(2)
        compose.onNodeWithContentDescription("关闭菜单").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回上一级菜单").assertDoesNotExist()
        advanceFrames(90)
        compose.onNodeWithText("Artist").assertDoesNotExist()
        compose.onNodeWithText("Name").assertDoesNotExist()
    }

    @Test
    fun collapsingSubmenuDoesNotInterceptTouchesOnTheParentOrBackdrop() {
        var albumClicks = 0
        compose.setContent {
            PopupMenuHost {
                PopupMenu(
                    listOf(
                        PopupMenuEntry.Item(
                            "Artist", submenu = List(5) { PopupMenuEntry.Item("Name $it") },
                        ),
                        PopupMenuEntry.Item("Album") { albumClicks++ },
                    ),
                ) { open -> Text("Open", Modifier.clickable(onClick = open)) }
            }
        }
        repeat(2) { attempt ->
            compose.mainClock.autoAdvance = true
            compose.onNodeWithText("Open").performClick()
            val album = compose.onNodeWithText("Album").fetchSemanticsNode().boundsInRoot.center
            compose.onNodeWithText("Artist").performClick()
            val submenu = compose.onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "子菜单"),
            ).fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("返回上一级菜单").performClick()
            advanceFrames(2)
            // Send real pointer events through the still visible submenu, rather than invoking semantics.
            val point = if (attempt == 0) album else Offset(submenu.center.x, submenu.bottom - 24f)
            compose.onRoot().performTouchInput { click(point) }
            advanceFrames(2)
            compose.onNodeWithContentDescription("关闭菜单").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, albumClicks) }
            advanceFrames(90)
        }
    }

    @Test
    fun pressNearTheEndOfSubmenuCollapseCanStillReopenIt() {
        compose.setContent {
            PopupMenuHost {
                PopupMenu(
                    listOf(
                        PopupMenuEntry.Item("Artist", submenu = listOf(PopupMenuEntry.Item("Name"))),
                        PopupMenuEntry.Item("Album"),
                    ),
                ) { open -> Text("Open", Modifier.clickable(onClick = open)) }
            }
        }
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Artist").performClick()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("返回上一级菜单").performClick()
        advanceFrames(14)
        val parentItem = compose.onNode(
            hasText("Artist") and hasAnyAncestor(
                SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "菜单"),
            ),
        )
        parentItem.performTouchInput { down(center) }
        // Finish the exit while the pointer remains down; removing either target here loses UP.
        advanceFrames(30)
        parentItem.performTouchInput { up() }
        advanceFrames(90)
        compose.onNodeWithText("Name").assertExists()
        compose.onNodeWithContentDescription("返回上一级菜单").assertExists()
        compose.onNodeWithText("Album").assertDoesNotExist()
    }

    @Test
    fun submenuGeometryDoesNotDependOnWhenTheParentEntranceWasInterrupted() {
        compose.setContent {
            PopupMenuHost {
                PopupMenu(
                    listOf(
                        PopupMenuEntry.Item("Album"),
                        PopupMenuEntry.Item("Artist", submenu = listOf(PopupMenuEntry.Item("Name"))),
                    ),
                ) { open -> Text("Open", Modifier.clickable(onClick = open)) }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Open").performClick()
        advanceFrames(3)
        val parent = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "菜单")
        val child = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "子菜单")
        val enteringRow = compose.onNodeWithText("Artist").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Artist").performClick()
        advanceFrames(90)
        val early = compose.onNode(child).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("返回上一级菜单").performClick()
        advanceFrames(90)
        val settledRow = compose.onNodeWithText("Artist").fetchSemanticsNode().boundsInRoot
        val settledMenu = compose.onNode(parent).fetchSemanticsNode().boundsInRoot
        assertTrue(enteringRow.width < settledRow.width)
        assertEquals(settledMenu.width, settledRow.width, 1f)
        compose.onNodeWithText("Artist").performClick()
        advanceFrames(90)
        val late = compose.onNode(child).fetchSemanticsNode().boundsInRoot
        assertEquals(late.left, early.left, 1f)
        assertEquals(late.top, early.top, 1f)
        assertEquals(late.width, early.width, 1f)
        assertEquals(late.height, early.height, 1f)
    }

    private fun showMenu() {
        compose.setContent {
            PopupMenuHost {
                PopupMenu(listOf(PopupMenuEntry.Item("Artist", subtitle = "Name"), PopupMenuEntry.Item("Album"))) { open ->
                    Box(Modifier.size(160.dp, 48.dp).clickable(indication = null, interactionSource = null, onClick = open)) {
                        Text("Open")
                    }
                }
            }
        }
    }

    private fun advanceFrames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }
}
