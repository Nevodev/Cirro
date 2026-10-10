package com.nevoit.material.core.interaction.overscroll

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsetOverscrollEffectTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val factory = OffsetOverscrollFactory(animationScope = scope)

    @After
    fun disposeScope() {
        scope.cancel()
    }

    @Test
    fun effectsFromTheSameFactoryKeepGesturesAndNodesIndependent() {
        val first = factory.createOverscrollEffect()
        val second = factory.createOverscrollEffect()
        assertNotSame(first.node, second.node)

        first.applyToScroll(Offset(0f, 30f), NestedScrollSource.UserInput) { Offset.Zero }
        assertTrue(first.isInProgress)
        assertFalse(second.isInProgress)

        second.applyToScroll(Offset(0f, -20f), NestedScrollSource.UserInput) { Offset.Zero }
        first.applyToScroll(Offset(0f, -30f), NestedScrollSource.UserInput) { Offset.Zero }
        assertFalse(first.isInProgress)
        assertTrue(second.isInProgress)
    }

    @Test
    fun consumedScrollDoesNotStartOverscroll() {
        val effect = factory.createOverscrollEffect()
        effect.applyToScroll(Offset(0f, 30f), NestedScrollSource.UserInput) { it }
        assertFalse(effect.isInProgress)
    }
}
