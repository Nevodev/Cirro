package com.nevoit.material.navigation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun deepStackDisposesCoveredUiAndRestoresStateWithoutClearingItsViewModel() {
        val pages = Pages()
        show(pages)
        for (page in 0 until 8) {
            compose.runOnIdle {
                pages.setValue.getValue(page)(page + 100)
                pages.scopes.getValue(page).navigate(page + 1)
            }
            compose.waitForIdle()
        }
        val coveredModel = pages.models.getValue(7)
        val outgoingModel = pages.models.getValue(8)
        compose.runOnIdle {
            assertEquals(setOf(8), pages.composed)
            for (page in 0 until 8) {
                assertEquals(Lifecycle.State.CREATED, pages.lifecycles.getValue(page).currentState)
                assertFalse(pages.models.getValue(page).cleared)
            }
            assertEquals(Lifecycle.State.RESUMED, pages.lifecycles.getValue(8).currentState)
            pages.scopes.getValue(8).back()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(setOf(7), pages.composed)
            assertEquals(107, pages.values.getValue(7))
            assertSame(coveredModel, pages.models.getValue(7))
            assertFalse(coveredModel.cleared)
            assertTrue(outgoingModel.cleared)
            assertEquals(Lifecycle.State.DESTROYED, pages.lifecycles.getValue(8).currentState)
        }
    }

    @Test
    fun forwardAndBackWaitForTheIncomingPagesFirstPlacement() {
        val pages = Pages()
        show(pages)
        val forward = mutableListOf<Float>()
        val backward = mutableListOf<Float>()
        compose.runOnIdle {
            pages.onFirstPlacement = { page ->
                if (page == 1) forward += pages.transitions.getValue(1).progress.value
            }
            pages.scopes.getValue(0).navigate(1)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(setOf(1), pages.composed)
            assertEquals(listOf(0f), forward)
            pages.onFirstPlacement = { page ->
                if (page == 0) backward += pages.transitions.getValue(1).progress.value
            }
            pages.scopes.getValue(1).back()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(1f), backward)
            assertEquals(2, pages.compositions.getValue(0))
            assertEquals(setOf(0), pages.composed)
        }
    }

    @Test
    fun closeToRootDoesNotRecomposeHiddenIntermediatePages() {
        val pages = Pages()
        show(pages)
        for (page in 0 until 6) {
            compose.runOnIdle { pages.scopes.getValue(page).navigate(page + 1) }
            compose.waitForIdle()
        }
        val compositions = pages.compositions.toMap()
        compose.runOnIdle { pages.handle.closeToRoot() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(setOf(0), pages.composed)
            assertEquals(listOf(0), pages.navigator.backStack.map { it.destination })
            assertEquals(compositions.getValue(0) + 1, pages.compositions.getValue(0))
            for (page in 1..6) {
                assertEquals(compositions.getValue(page), pages.compositions.getValue(page))
                assertTrue(pages.models.getValue(page).cleared)
            }
            assertFalse(pages.models.getValue(0).cleared)
        }
    }

    @Test
    fun reopeningDuringExitKeepsTheEntryViewModelAndAnimationPosition() {
        val pages = Pages()
        show(pages)
        compose.runOnIdle { pages.scopes.getValue(0).navigate(1) }
        compose.waitForIdle()
        val entry = pages.navigator.current
        val model = pages.models.getValue(1)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { pages.scopes.getValue(1).back() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        compose.runOnIdle {
            val progress = pages.transitions.getValue(1).progress.value
            assertTrue(progress > 0f && progress < 1f)
            pages.scopes.getValue(0).navigate(1)
            assertEquals(entry, pages.navigator.current)
            assertEquals(progress, pages.transitions.getValue(1).progress.value, 0f)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(setOf(1), pages.composed)
            assertSame(model, pages.models.getValue(1))
            assertFalse(model.cleared)
            assertEquals(1f, pages.transitions.getValue(1).progress.value, 0f)
        }
    }

    @Test
    fun alternatingPagesKeepMovingWhenTheExitingSiblingIsDisposed() {
        val pages = Pages()
        show(pages)
        compose.runOnIdle { pages.scopes.getValue(0).navigate(1) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        var outgoing = 1
        for (incoming in listOf(2, 1, 2)) {
            compose.runOnIdle { pages.scopes.getValue(outgoing).back() }
            advanceFrames(6)
            compose.runOnIdle { pages.scopes.getValue(0).navigate(incoming) }
            advanceFrames(4)

            var previous = pages.transitions.getValue(incoming).progress.value
            var lastStep = 0f
            var removed = false
            for (frame in 0 until 24) {
                advanceFrames(1)
                val progress = pages.transitions.getValue(incoming).progress.value
                lastStep = progress - previous
                previous = progress
                if (outgoing !in pages.composed) {
                    removed = true
                    break
                }
            }
            assertTrue("The outgoing page never left composition", removed)
            assertTrue("The incoming page should still be moving", previous in 0.1f..0.9f)
            assertTrue(lastStep > 0f)

            // The linear tween must keep the same frame step across sibling removal. Restarting
            // it from the current position introduces a stationary frame and a slower remainder.
            repeat(3) {
                advanceFrames(1)
                val progress = pages.transitions.getValue(incoming).progress.value
                assertEquals(lastStep, progress - previous, 0.005f)
                previous = progress
            }
            advanceFrames(24)
            compose.runOnIdle {
                assertEquals(setOf(incoming), pages.composed)
                assertEquals(1f, pages.transitions.getValue(incoming).progress.value, 0f)
            }
            outgoing = incoming
        }
    }

    private fun advanceFrames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    @Test
    fun restoredBackStackInitiallyComposesOnlyItsTopEntry() {
        val navigator = Navigator(
            initialBackStack = (0..5).map { PageEntry(it.toLong() + 40, it) },
            nextEntryId = 46,
        )
        val pages = Pages(navigator)
        show(pages)
        compose.runOnIdle {
            assertEquals(setOf(5), pages.composed)
            assertEquals(setOf(5), pages.compositions.keys)
            pages.scopes.getValue(5).back()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(setOf(4), pages.composed)
            assertEquals(1f, pages.transitions.getValue(4).progress.value, 0f)
        }
    }

    private fun show(pages: Pages) {
        compose.setContent {
            NavigationHost(
                navigator = pages.navigator,
                pageViewModelStores = pages.stores,
                animationSpec = tween(320, easing = LinearEasing),
                onHostReady = { pages.handle = it },
            ) {
                val scope = this
                val page = destination
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                val transition = checkNotNull(LocalPageTransitionState.current)
                val model = viewModel { PageModel() }
                var value by rememberSaveable { mutableIntStateOf(0) }
                val placed = remember { booleanArrayOf(false) }
                DisposableEffect(entry.id) {
                    pages.composed += page
                    pages.compositions[page] = pages.compositions.getOrDefault(page, 0) + 1
                    onDispose { pages.composed -= page }
                }
                SideEffect {
                    pages.scopes[page] = scope
                    pages.lifecycles[page] = lifecycle
                    pages.transitions[page] = transition
                    pages.models[page] = model
                    pages.values[page] = value
                    pages.setValue[page] = { value = it }
                }
                Box(
                    Modifier.fillMaxSize().layout { measurable, constraints ->
                        val child = measurable.measure(constraints)
                        layout(child.width, child.height) {
                            child.place(0, 0)
                            if (!placed[0]) {
                                placed[0] = true
                                pages.onFirstPlacement(page)
                            }
                        }
                    }
                )
            }
        }
        compose.waitForIdle()
    }

    private class Pages(val navigator: Navigator<Int> = Navigator(0)) {
        val stores = PageViewModelStores()
        val composed = mutableSetOf<Int>()
        val compositions = mutableMapOf<Int, Int>()
        val scopes = mutableMapOf<Int, NavigationScope<Int>>()
        val lifecycles = mutableMapOf<Int, Lifecycle>()
        val transitions = mutableMapOf<Int, PageTransitionState>()
        val models = mutableMapOf<Int, PageModel>()
        val values = mutableMapOf<Int, Int>()
        val setValue = mutableMapOf<Int, (Int) -> Unit>()
        var onFirstPlacement: (Int) -> Unit = {}
        lateinit var handle: NavigationHostHandle
    }

    private class PageModel : ViewModel() {
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }
}
