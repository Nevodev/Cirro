package com.nevoit.kinetic.layout.indicator

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Mutable only at gesture boundaries. Ownership prevents an old cancellation resetting a new drag. */
internal class ScrollIndicatorDragState {
    var active by mutableStateOf(false)
        private set
    private var owner: Any? = null
    fun start(token: Any) { owner = token; active = true }
    fun finish(token: Any): Boolean {
        if (owner !== token) return false
        owner = null; active = false
        return true
    }
}

/**
 * Translation * contentExtent / indicatorTrackExtent, applied incrementally. Absolute pointer
 * samples may be conflated without losing movement. Changes to estimated content size affect only
 * subsequent movement, never move the content beneath a stationary finger.
 */
internal class ScrollIndicatorDragMapping(private var lastPosition: Float) {
    fun delta(position: Float, content: Int, viewport: Int, track: Float, reverse: Boolean): Float {
        val previous = lastPosition
        lastPosition = position
        if (!position.isFinite() || !previous.isFinite() || content == Int.MAX_VALUE ||
            viewport == Int.MAX_VALUE || viewport <= 0 || content <= viewport ||
            !track.isFinite() || track <= 0f) return 0f
        val result = (position.toDouble() - previous) * content / track * if (reverse) -1 else 1
        return result.toFloat().takeIf { it.isFinite() } ?: 0f
    }
}

/** Holds the public ScrollableState mutation for the entire drag; no raw-delta bypass or release fling. */
internal suspend fun runScrollIndicatorDrag(
    state: ScrollableState,
    positions: ReceiveChannel<Float>,
    initialPosition: Float,
    metrics: () -> ScrollIndicatorState?,
    track: () -> Float,
    reverse: () -> Boolean,
    onStarted: () -> Unit,
    onFinished: () -> Unit,
) {
    var started = false
    try {
        state.scroll(MutatePriority.UserInput) {
            started = true
            onStarted()
            val mapping = ScrollIndicatorDragMapping(initialPosition)
            for (position in positions) {
                val current = metrics()
                val delta = mapping.delta(position, current?.contentSize ?: Int.MAX_VALUE,
                    current?.viewportSize ?: Int.MAX_VALUE, track(), reverse())
                if (delta != 0f) scrollBy(delta)
            }
        }
    } finally {
        if (started) onFinished()
    }
}

/**
 * Reserve only the visible bar (plus [ScrollIndicatorSpec.dragHitSlop]) at the Initial pass, so the
 * item under the finger never also clicks. Below the long-press threshold the press is handed back
 * to the list's own drag. After activation the ScrollableState mutation is owned until release.
 */
internal fun Modifier.dragKineticScrollIndicator(
    state: ScrollableState,
    dragState: ScrollIndicatorDragState,
    vertical: Boolean,
    rtl: Boolean,
    spec: ScrollIndicatorSpec,
    expansion: () -> Float,
    alpha: () -> Float,
    overscroll: () -> Float,
    reverseScrolling: () -> Boolean,
    haptic: HapticFeedback,
    onDraggingChanged: (Boolean) -> Unit,
): Modifier = pointerInput(state, vertical, rtl, spec) {
    val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val insets = spec.resolveInsets(this, direction, vertical)
    val left = insets.left
    val right = insets.right
    val top = insets.top
    val bottom = insets.bottom
    val axisStart = if (vertical) top else if (rtl) right else left
    val axisEnd = if (vertical) bottom else if (rtl) left else right
    val slop = spec.dragHitSlop.toPx()
    val normalWidth = spec.thickness.toPx()
    val expandedWidth = spec.expandedThickness.toPx()
    fun extent() = if (vertical) size.height.toFloat() else size.width.toFloat()
    fun axis(p: Offset) = if (vertical) p.y else p.x
    fun track() = extent() - axisStart.coerceAtLeast(0f) - axisEnd.coerceAtLeast(0f)
    fun hit(position: Offset): Boolean {
        if (alpha() <= 0f) return false
        val metrics = state.scrollIndicatorState ?: return false
        if (metrics.viewportSize == Int.MAX_VALUE || metrics.contentSize == Int.MAX_VALUE ||
            metrics.scrollOffset == Int.MAX_VALUE ||
            kotlin.math.abs(metrics.viewportSize - extent()) > 1f) return false
        val bar = calculateScrollIndicator(extent(), metrics.contentSize.toFloat(),
            metrics.scrollOffset.toFloat(), overscroll(), density, axisStart, axisEnd, !vertical && rtl)
        if (!bar.isVisible) return false
        val width = normalWidth + (expandedWidth - normalWidth) * expansion().coerceIn(0f, 1f)
        if (!width.isFinite() || width <= 0f || !slop.isFinite()) return false
        val crossStart = if (vertical) { if (rtl) left else size.width - right - width }
            else size.height - bottom - width
        val cross = if (vertical) position.x else position.y
        val along = axis(position)
        return position.x >= 0f && position.y >= 0f && position.x <= size.width && position.y <= size.height &&
            along >= bar.startPx - slop && along <= bar.endPx + slop &&
            cross >= crossStart - slop && cross <= crossStart + width + slop
    }
    coroutineScope {
        val workerScope = this
        awaitEachGesture {
            // Reserve only the visible thumb's touch region before child click/long-click handlers.
            // Scrollables accept a consumed DOWN and can still take over if slop is crossed first.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.isConsumed || currentEvent.changes.count { it.pressed } != 1 || !hit(down.position)) {
                return@awaitEachGesture
            }
            down.consume()
            awaitPointerEvent(PointerEventPass.Final) // skip our own consumed DOWN during recognition
            val press = awaitIndicatorLongPress(down, spec.longPressMillis) ?: return@awaitEachGesture
            if (!hit(press.position)) return@awaitEachGesture
            val positions = Channel<Float>(Channel.CONFLATED)
            val token = Any()
            val worker = workerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runScrollIndicatorDrag(state, positions, axis(press.position), { state.scrollIndicatorState },
                    { track() }, { (!vertical && rtl) xor reverseScrolling() },
                    onStarted = {
                        dragState.start(token)
                        onDraggingChanged(true)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onFinished = {
                        if (dragState.finish(token)) onDraggingChanged(false)
                    })
            }
            var released = false
            try {
                while (worker.isActive) {
                    // Consume before the child scrollable/clickable sees this event.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (!worker.isActive) break
                    if (event.changes.any { it.id != down.id && it.pressed }) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    positions.trySend(axis(change.position))
                    change.consume()
                    if (!change.pressed) { released = true; break }
                }
            } finally {
                // A normal UP drains the final position; cancellation drops pending work.
                if (released) positions.close() else {
                    positions.cancel()
                    worker.cancel()
                }
            }
        }
    }
}

/** No lead-finger transfer: a second finger, movement, UP, or another scroll cancels recognition. */
internal suspend fun AwaitPointerEventScope.awaitIndicatorLongPress(
    down: PointerInputChange,
    timeoutMillis: Long,
): PointerInputChange? {
    var latest = down
    val early = withTimeoutOrNull(timeoutMillis) {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.changes.any { it.id != down.id && it.pressed }) return@withTimeoutOrNull true
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
            if (!change.pressed || change.isConsumed ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                change.position.x < 0f || change.position.y < 0f ||
                change.position.x > size.width || change.position.y > size.height) {
                return@withTimeoutOrNull true
            }
            latest = change
            if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) {
                return@withTimeoutOrNull true
            }
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }
    return if (early == null) latest else null
}
