/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "CANNOT_OVERRIDE_INVISIBLE_MEMBER", "EXPOSED_SUPER_CLASS", "EXPOSED_PROPERTY_TYPE", "EXPOSED_PARAMETER_TYPE")

package com.nevoit.kinetic.layout.internal.gestures

import androidx.compose.foundation.gestures.*

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.ComposeFoundationFlags.isClearNestedScrollCoroutineScopeFixEnabled
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation.Horizontal
import androidx.compose.foundation.gestures.Orientation.Vertical
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.internal.PlatformOptimizedCancellationException
import androidx.compose.foundation.relocation.BringIntoViewResponderNode
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.rememberPlatformOverscrollEffect
import androidx.compose.foundation.scrollableArea
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.focus.getFocusedRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollSource.Companion.SideEffect
import androidx.compose.ui.input.nestedscroll.NestedScrollSource.Companion.UserInput
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.dispatchOnScrollChanged
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.atan2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.node.currentValueOf
import com.nevoit.kinetic.layout.FlingVelocityProvider
import com.nevoit.kinetic.layout.LocalLowSpeedClickThreshold
import com.nevoit.kinetic.layout.canClickThroughFling
import com.nevoit.kinetic.layout.kineticDefaultFlingBehavior
import kotlinx.coroutines.CoroutineStart

@OptIn(ExperimentalFoundationApi::class)
internal class ClickThroughScrollableNode(
    state: ScrollableState,
    overscrollEffect: OverscrollEffect?,
    flingBehavior: FlingBehavior?,
    orientation: Orientation,
    enabled: Boolean,
    reverseDirection: Boolean,
    interactionSource: MutableInteractionSource?,
    bringIntoViewSpec: BringIntoViewSpec?,
) :
    AbstractScrollableNode(
        overscrollEffect = overscrollEffect,
        flingBehavior = flingBehavior,
        enabled = enabled,
        interactionSource = interactionSource,
        orientation = orientation,
    ),
    KeyInputModifierNode,
    OnScrollChangedDispatcher {


    // The sole input behavior change. Freeze the decision on the first DOWN.
    private var allowClickForThisPress = false

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        if (enabled && pass == PointerEventPass.Initial &&
            pointerEvent.changes.none { it.previousPressed } &&
            pointerEvent.changes.any { it.pressed && it.type != PointerType.Mouse }
        ) {
            val threshold = with(requireDensity()) {
                currentValueOf(LocalLowSpeedClickThreshold).toPx()
            }
            val velocity = ((flingBehavior ?: defaultFlingBehavior)
                as? FlingVelocityProvider)?.currentFlingVelocity
            allowClickForThisPress = pointerEvent.changes.none { it.isConsumed } &&
                canClickThroughFling(
                    isFlinging = scrollLogic.isFlinging,
                    overscrollInProgress = overscrollEffect?.isInProgress == true,
                    velocity = velocity,
                    threshold = threshold,
                )
            if (allowClickForThisPress) {
                // This starts immediately; the latched DOWN decision remains valid even if
                // cancellation needs to suspend while the previous mutation finishes.
                coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    scrollLogic.scrollableState.stopScroll(MutatePriority.UserInput)
                }
            }
        }
        super.onPointerEvent(pointerEvent, pass, bounds)
        if (pass == PointerEventPass.Final && pointerEvent.changes.none { it.pressed }) {
            allowClickForThisPress = false
        }
    }

    override fun startDragImmediately(): Boolean =
        if (allowClickForThisPress) false else super.startDragImmediately()

    override fun onCancelPointerInput() {
        allowClickForThisPress = false
        super.onCancelPointerInput()
    }

    // Fallback when the caller passes no behavior. The decay is density independent, so this
    // instance is complete right away instead of waiting for a density update.
    override val defaultFlingBehavior = kineticDefaultFlingBehavior()

    override val scrollLogic =
        ScrollingLogic(
            scrollableState = state,
            orientation = orientation,
            overscrollEffect = overscrollEffect,
            reverseDirection = reverseDirection,
            flingBehavior = flingBehavior ?: defaultFlingBehavior,
            nestedScrollDispatcher = nestedScrollDispatcher,
            onScrollChangedDispatcher = this,
            isScrollableNodeAttached = { isAttached },
        )

    override val nestedScrollConnection =
        ScrollableNestedScrollConnection(enabled = enabled, scrollingLogic = scrollLogic)

    private val focusTargetModifierNode =
        delegate(FocusTargetModifierNode(focusability = Focusability.Never))

    private val contentInViewNode =
        delegate(
            ContentInViewNode(
                orientation = orientation,
                scrollingLogic = scrollLogic,
                reverseDirection = reverseDirection,
                bringIntoViewSpec = bringIntoViewSpec,
                getFocusedRect = { focusTargetModifierNode.getFocusedRect() },
            )
        )

    override fun createMouseWheelScrollingLogic() =
        MouseWheelScrollingLogic(
            scrollingLogic = scrollLogic,
            mouseWheelScrollConfig = platformScrollConfig(),
            onScrollStopped = ::onMouseWheelScrollStopped,
            density = requireDensity(),
        )

    override fun createTrackpadScrollingLogic() =
        TrackpadScrollingLogic(
            scrollingLogic = scrollLogic,
            onScrollStopped = ::onTrackpadScrollStopped,
            density = requireDensity(),
        )

    init {
        // Must be called here because in AbstractScrollableNode.init nestedScrollConnection hasn't
        // been created yet
        initializeNestedScrollingDelegation()

        /** Focus scrolling */
        delegate(BringIntoViewResponderNode(contentInViewNode))
    }

    override fun dispatchScrollDeltaInfo(delta: Offset) {
        if (!isAttached) return
        dispatchOnScrollChanged(delta)
    }

    override suspend fun drag(
        forEachDelta: suspend ((dragDelta: DragEvent.DragDelta) -> Unit) -> Unit
    ) {
        with(scrollLogic) {
            scroll(scrollPriority = MutatePriority.UserInput) {
                forEachDelta {
                    // Indirect pointer Events should be reverted to account for the reverse we
                    // do in Scrollable. Regular touchscreen events are inverted in scrollable, but
                    // that shouldn't happen for indirect pointer events, so we cancel the reverse
                    // here.
                    val invertIndirectPointer = if (it.isIndirectPointerEvent) -1f else 1f
                    scrollByWithOverscroll(
                        it.delta.singleAxisOffset() * invertIndirectPointer,
                        source = UserInput,
                    )
                }
            }
        }
    }

    override fun onDragStopped(event: DragEvent.DragStopped) {
        if (isClearNestedScrollCoroutineScopeFixEnabled && !isAttached) return
        nestedScrollDispatcher.coroutineScope.launch {
            // Indirect pointer Events should be reverted to account for the reverse we
            // do in Scrollable. Regular touchscreen events are inverted in scrollable, but
            // that shouldn't happen for indirect pointer events, so we cancel the reverse
            // here.
            val invertIndirectPointer = if (event.isIndirectPointerEvent) -1f else 1f
            scrollLogic.onScrollStopped(
                event.velocity * invertIndirectPointer,
                isMouseWheel = false,
            )
        }
    }

    private fun onMouseWheelScrollStopped(velocity: Velocity) {
        nestedScrollDispatcher.coroutineScope.launch {
            scrollLogic.onScrollStopped(velocity, isMouseWheel = true)
        }
    }

    private fun onTrackpadScrollStopped(velocity: Velocity) {
        nestedScrollDispatcher.coroutineScope.launch {
            scrollLogic.onScrollStopped(velocity, isMouseWheel = false)
        }
    }

    fun update(
        state: ScrollableState,
        orientation: Orientation,
        overscrollEffect: OverscrollEffect?,
        enabled: Boolean,
        reverseDirection: Boolean,
        flingBehavior: FlingBehavior?,
        interactionSource: MutableInteractionSource?,
        bringIntoViewSpec: BringIntoViewSpec?,
    ) {
        update(
            enabled = enabled,
            overscrollEffect = overscrollEffect,
            flingBehavior = flingBehavior,
        )

        // a new fling behavior was set, change the resolved one.
        val resolvedFlingBehavior = flingBehavior ?: defaultFlingBehavior
        val resetPointerInputHandling =
            scrollLogic.update(
                scrollableState = state,
                orientation = orientation,
                overscrollEffect = overscrollEffect,
                reverseDirection = reverseDirection,
                flingBehavior = resolvedFlingBehavior,
                nestedScrollDispatcher = nestedScrollDispatcher,
            )
        contentInViewNode.update(orientation, reverseDirection, bringIntoViewSpec)

        // update DragGestureNode
        update(
            canDrag = CanDragCalculation,
            enabled = enabled,
            interactionSource = interactionSource,
            orientation = if (scrollLogic.isVertical()) Vertical else Horizontal,
            shouldResetPointerInputHandling = resetPointerInputHandling,
        )
    }

    // Key handler for Page up/down scrolling behavior.
    override fun onKeyEvent(event: KeyEvent): Boolean {
        return if (
            enabled &&
                (event.key == Key.PageDown || event.key == Key.PageUp) &&
                (event.type == KeyEventType.KeyDown) &&
                (!event.isCtrlPressed)
        ) {

            val scrollAmount: Offset =
                if (scrollLogic.isVertical()) {
                    val viewportHeight = contentInViewNode.viewportSizeOrZero.height

                    val yAmount =
                        if (event.key == Key.PageUp) {
                            viewportHeight.toFloat()
                        } else {
                            -viewportHeight.toFloat()
                        }

                    Offset(0f, yAmount)
                } else {
                    val viewportWidth = contentInViewNode.viewportSizeOrZero.width

                    val xAmount =
                        if (event.key == Key.PageUp) {
                            viewportWidth.toFloat()
                        } else {
                            -viewportWidth.toFloat()
                        }

                    Offset(xAmount, 0f)
                }

            // A coroutine is launched for every individual scroll event in the
            // larger scroll gesture. If we see degradation in the future (that is,
            // a fast scroll gesture on a slow device causes UI jank [not seen up to
            // this point), we can switch to a more efficient solution where we
            // lazily launch one coroutine (with the first event) and use a Channel
            // to communicate the scroll amount to the UI thread.
            coroutineScope.launch {
                scrollLogic.scroll(scrollPriority = MutatePriority.UserInput) {
                    scrollBy(offset = scrollAmount, source = UserInput)
                }
            }
            true
        } else {
            false
        }
    }

    override fun onPreKeyEvent(event: KeyEvent) = false

    override suspend fun semanticsScrollBy(offset: Offset): Offset {
        return scrollLogic.semanticsScrollBy(offset)
    }
}


private suspend fun ScrollingLogic.semanticsScrollBy(offset: Offset): Offset {
    var previousValue = 0f
    scroll(scrollPriority = MutatePriority.Default) {
        animate(0f, offset.toFloat()) { currentValue, _ ->
            val delta = currentValue - previousValue
            val consumed =
                scrollBy(offset = delta.reverseIfNeeded().toOffset(), source = UserInput)
                    .toFloat()
                    .reverseIfNeeded()
            previousValue += consumed
        }
    }
    return previousValue.toOffset()
}

