package com.nevoit.material.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.nevoit.material.clip.cornerMaskClipInLayer
import com.nevoit.material.navigation.shape.deviceCornerShape
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel as androidxViewModel

/**
 * Share of the entrance travel a page must complete before it *looks* settled — the default
 * threshold of [PageTransitionState.awaitNearlySettled].
 */
private const val DefaultRevealProgress = 0.995f

/**
 * The entrance/exit state of the page currently rendering, handed to that page through
 * [LocalPageTransitionState].
 *
 * Exists so a page can hold off work that would stutter its own entrance — a full list landing
 * halfway through the slide, for instance. [progress] changes every frame, so `awaitSettled` /
 * `awaitNearlySettled` (or a `snapshotFlow` of your own) is the only safe way to wait on it.
 */
@Stable
class PageTransitionState internal constructor(
    /** 0 = the page is fully off its resting place, 1 = in place. */
    val progress: State<Float>,
    /** True while the entrance or exit animation is still running. */
    val isTransitioning: State<Boolean>,
    /**
     * Full slide distance of this page, in device px (= host width). `(1 - progress) * slideDistancePx`
     * is how far the page still has to travel; zero when the parent gives no width to slide across.
     */
    val slideDistancePx: Float,
) {
    /** Suspends until the page has finished moving into or out of place. */
    suspend fun awaitSettled() {
        snapshotFlow { isTransitioning.value }.first { !it }
    }

    /**
     * Suspends until the page *looks* settled, skipping the tail of the slide.
     *
     * The default threshold is the last 5% of the travel ([DefaultRevealProgress]): by then the
     * spring has only a few px left and is moving at near-zero speed, so the eye already reads the
     * page as in place — while that tail still costs a good chunk of the entrance. Pass
     * [remainingDistancePx] to use an absolute distance (24f, say) instead; both are the same test.
     */
    suspend fun awaitNearlySettled(
        remainingDistancePx: Float = (1f - DefaultRevealProgress) * slideDistancePx,
    ) {
        val threshold = remainingDistancePx.coerceAtLeast(0f)
        snapshotFlow { isTransitioning.value && (1f - progress.value) * slideDistancePx > threshold }
            .first { !it }
    }
}

/**
 * Null outside a [NavigationHost] (previews, isolated tests) — pages must treat that as "no
 * transition to wait for" rather than skipping their content.
 */
val LocalPageTransitionState = staticCompositionLocalOf<PageTransitionState?> { null }

private class PageTransition(
    initialProgress: Float,
    initialUnderlayProgress: Float,
    parentEntryId: Long?,
    parentLifecycle: Lifecycle,
) {
    val progress = Animatable(initialProgress)
    val underlayProgress = Animatable(initialUnderlayProgress)
    var parentEntryId by mutableStateOf(parentEntryId)
    var ready by mutableStateOf(false)
    var composed = false
    var targetProgress by mutableFloatStateOf(1f)
    val isTransitioning = mutableStateOf(initialProgress != 1f)
    val lifecycleOwner = NavigationPageLifecycleOwner(parentLifecycle)
}

private class NavigationPageLifecycleOwner(
    private val parentLifecycle: Lifecycle,
) : LifecycleOwner, LifecycleEventObserver {
    private val registry = LifecycleRegistry(this)
    private var maxState = Lifecycle.State.CREATED

    override val lifecycle: Lifecycle = registry

    init {
        parentLifecycle.addObserver(this)
        syncState()
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        syncState()
    }

    fun moveTo(state: Lifecycle.State) {
        maxState = state
        syncState()
    }

    fun destroy() {
        parentLifecycle.removeObserver(this)
        registry.currentState = Lifecycle.State.DESTROYED
    }

    private fun syncState() {
        registry.currentState = when {
            parentLifecycle.currentState == Lifecycle.State.DESTROYED -> {
                Lifecycle.State.DESTROYED
            }

            !parentLifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) -> {
                Lifecycle.State.INITIALIZED
            }

            else -> minOf(parentLifecycle.currentState, maxState)
        }
    }
}

// Host-level controls for callers outside the page tree (a persistent bottom bar that
// wants to pop back to the root when a tab is tapped).
@Stable
class NavigationHostHandle internal constructor(
    private val closeTopInternal: () -> Unit,
    private val hasPagesAboveRoot: () -> Boolean,
) {
    /** Closes every page above the root. No-op when already at the root. */
    fun closeToRoot() {
        while (hasPagesAboveRoot()) closeTopInternal()
    }
}

/**
 * Renders the current page and pages participating in a transition, coordinating system back,
 * saveable state, lifecycle state, and per-page ViewModel stores.
 *
 * The receiver content is invoked once for each visible page. Use [NavigationScope.destination] to
 * render the route and [NavigationScope.navigate] or [NavigationScope.back] from page actions. When
 * [pageViewModelStores] is omitted, the host creates one ViewModel-backed store manager for the
 * current ViewModel owner.
 *
 * The slide distance is the host's own width. [windowWidthPx] overrides it — two hosts side by side in
 * a two-pane layout each measure their own pane, which is what makes that layout work — and
 * [underlayTranslation], [scrimColor] and [scrimAlpha] are optional visual
 * policy hooks; they do not participate in back-stack or lifecycle decisions.
 * [onHostReady] hands out a [NavigationHostHandle] once, for callers outside the page tree.
 *
 * Ported from `Bilineo/.../core/navigation`, with two changes for Compose Multiplatform: the window
 * width is measured by the host itself rather than read from `LocalWindowInfo`, and system back goes
 * through [PlatformBackHandler] instead of `androidx.activity.compose` directly — see its KDoc for why
 * that one is hand-rolled.
 */
@Composable
fun <D : Any> NavigationHost(
    modifier: Modifier = Modifier,
    navigator: Navigator<D>,
    pageViewModelStores: PageViewModelStores? = null,
    windowWidthPx: Int? = null,
    animationSpec: AnimationSpec<Float>? = null,
    underlayTranslation: (windowWidthPx: Int, progress: Float) -> Float = { windowWidthPx, progress ->
        -(windowWidthPx / 3f) * progress
    },
    scrimColor: Color = Color.Black,
    scrimAlpha: Float = 0.6f,
    onHostReady: (NavigationHostHandle) -> Unit = {},
    content: @Composable NavigationScope<D>.() -> Unit,
) {
    val pageShape = deviceCornerShape()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // An unbounded parent (a horizontal scroller, say) has no meaningful slide distance; zero
        // makes the page appear in place instead of sliding from infinity.
        val hostWidthPx = windowWidthPx
            ?: constraints.maxWidth.takeIf { it in 1 until Int.MAX_VALUE }
            ?: 0
        val resolvedAnimationSpec = animationSpec
            ?: Springs.smooth(350, 0.0, if (hostWidthPx > 0) 1f / hostWidthPx else 1f)

        val stores = pageViewModelStores ?: androidxViewModel<PageViewModelStores>()
        val parentLifecycle = LocalLifecycleOwner.current.lifecycle
        val initialBackStack = remember(navigator) { navigator.backStack }
        // A plain list in a state holder, not `mutableStateListOf`: the retained set is only ever
        // replaced wholesale, and `SnapshotStateList` is `android.os.Parcelable` in the Android source
        // set — a supertype a common source set cannot resolve, which is what makes an IDE report
        // "Cannot access 'android.os.Parcelable' which is a supertype of 'SnapshotStateList'".
        var retainedEntries by remember(navigator) { mutableStateOf(initialBackStack) }
        val transitions = remember(navigator, parentLifecycle) {
            mutableMapOf<Long, PageTransition>().apply {
                initialBackStack.forEachIndexed { index, entry ->
                    this[entry.id] = PageTransition(
                        initialProgress = 1f,
                        initialUnderlayProgress = 1f,
                        parentEntryId = initialBackStack.getOrNull(index - 1)?.id,
                        parentLifecycle = parentLifecycle,
                    )
                }
            }
        }
        val pageStateHolder = rememberSaveableStateHolder()

        fun entriesToCompose(): List<PageEntry<D>> {
            val ids = mutableSetOf(navigator.current.id)
            transitions.forEach { (id, transition) ->
                if (transition.isTransitioning.value) {
                    ids += id
                    transition.parentEntryId?.let { ids += it }
                }
            }
            return retainedEntries.filter { it.id in ids }
        }

        DisposableEffect(transitions) {
            onDispose {
                transitions.values.forEach { transition ->
                    transition.lifecycleOwner.destroy()
                }
            }
        }

        fun release(entry: PageEntry<D>) {
            retainedEntries -= entry
            transitions.remove(entry.id)?.lifecycleOwner?.destroy()
            pageStateHolder.removeState(entry.id)
            stores.remove(entry.id)
        }

        fun open(destination: D) {
            val exitingEntry = retainedEntries.lastOrNull { entry ->
                entry.destination == destination && transitions[entry.id]?.targetProgress == 0f
            }
            if (exitingEntry != null) {
                val transition = transitions.getValue(exitingEntry.id)
                transition.parentEntryId = navigator.current.id
                navigator.reopen(exitingEntry)
                transition.targetProgress = 1f
                transition.isTransitioning.value = true
                // Move the entry back to the top of the draw order.
                retainedEntries = retainedEntries - exitingEntry + exitingEntry
                return
            }
            if (navigator.current.destination == destination) return

            val parentEntry = navigator.current
            val interruptedSibling = retainedEntries.asReversed().firstOrNull { candidate ->
                transitions.getValue(candidate.id).parentEntryId == parentEntry.id
            }
            val initialUnderlayProgress = interruptedSibling?.let { sibling ->
                transitions.getValue(sibling.id).underlayProgress.value
            } ?: 0f
            val entry = navigator.open(destination)
            transitions[entry.id] = PageTransition(
                initialProgress = 0f,
                initialUnderlayProgress = initialUnderlayProgress,
                parentEntryId = parentEntry.id,
                parentLifecycle = parentLifecycle,
            )
            retainedEntries += entry
        }

        fun closeTop() {
            val entry = navigator.current
            val transition = transitions[entry.id] ?: return
            if (navigator.closeTop(expectedEntryId = entry.id) == null) return

            if (!transition.composed) {
                // A batch pop can cross hidden pages. Keep the actual outgoing UI over the final
                // destination instead of composing and animating every intermediate page.
                transitions.values.forEach { candidate ->
                    if (candidate.parentEntryId == entry.id) {
                        candidate.parentEntryId = navigator.current.id
                    }
                }
                release(entry)
                return
            }
            transition.targetProgress = 0f
            transition.isTransitioning.value = true
        }

        val hostHandle = remember(navigator) {
            NavigationHostHandle(
                closeTopInternal = { closeTop() },
                hasPagesAboveRoot = { navigator.backStack.size > 1 },
            )
        }
        LaunchedEffect(hostHandle) { onHostReady(hostHandle) }

        val visibleEntries = entriesToCompose()
        val visibleEntryIds = visibleEntries.map { it.id }
        val isTransitioning = transitions.values.any { it.isTransitioning.value }
        SideEffect {
            transitions.forEach { (id, transition) ->
                transition.lifecycleOwner.moveTo(
                    when {
                        id !in visibleEntryIds -> Lifecycle.State.CREATED
                        id == navigator.current.id && !isTransitioning -> Lifecycle.State.RESUMED
                        else -> Lifecycle.State.STARTED
                    }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val entryTransitions = visibleEntries.map { entry -> transitions.getValue(entry.id) }
            val underlayDrivers = mutableMapOf<Long, PageTransition>()
            visibleEntries.asReversed().forEach { candidate ->
                val candidateTransition = transitions.getValue(candidate.id)
                val parentEntryId = candidateTransition.parentEntryId ?: return@forEach
                if (parentEntryId !in underlayDrivers) {
                    underlayDrivers[parentEntryId] = candidateTransition
                }
            }

            visibleEntries.forEachIndexed { index, entry ->
                key(entry.id) {
                    val isRoot = entry.id == initialBackStack.first().id
                    val transition = entryTransitions[index]
                    val underlayDriver = underlayDrivers[entry.id]
                    val pageViewModelStoreOwner = stores.owner(entry.id)

                    DisposableEffect(transition) {
                        transition.composed = true
                        onDispose {
                            transition.composed = false
                            transition.ready = false
                        }
                    }

                    LaunchedEffect(transition, transition.targetProgress) {
                        // Sibling disposal must not restart an animation already in flight. Wait
                        // once for this page and its underlay; disposal expires their ready signals.
                        snapshotFlow {
                            transition.ready &&
                                (transitions[transition.parentEntryId]?.ready != false)
                        }.first { it }

                        val targetProgress = transition.targetProgress
                        if (
                            transition.progress.value != targetProgress ||
                            transition.underlayProgress.value != targetProgress
                        ) {
                            coroutineScope {
                                launch {
                                    transition.progress.animateTo(
                                        targetValue = targetProgress,
                                        animationSpec = resolvedAnimationSpec,
                                    )
                                }
                                launch {
                                    transition.underlayProgress.animateTo(
                                        targetValue = targetProgress,
                                        animationSpec = resolvedAnimationSpec,
                                    )
                                }
                            }
                            transition.progress.snapTo(targetProgress)
                            transition.underlayProgress.snapTo(targetProgress)
                        }
                        if (transition.targetProgress != targetProgress) return@LaunchedEffect

                        transition.isTransitioning.value = false
                        if (targetProgress == 0f && transition.progress.value == 0f) {
                            release(entry)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationPageReady { transition.ready = true }
                            .graphicsLayer {
                                // Keep child clip masks in page coordinates while either page moves.
                                compositingStrategy = if (
                                    transition.isTransitioning.value ||
                                    underlayDriver?.isTransitioning?.value == true
                                ) {
                                    CompositingStrategy.Offscreen
                                } else {
                                    CompositingStrategy.Auto
                                }
                                val ownTranslation = if (isRoot) {
                                    0f
                                } else {
                                    hostWidthPx * (1f - transition.progress.value)
                                }
                                translationX = ownTranslation + underlayTranslation(
                                    hostWidthPx,
                                    underlayDriver?.underlayProgress?.value ?: 0f,
                                )
                            }
                            .cornerMaskClipInLayer(
                                shape = pageShape,
                                enabled = transition.isTransitioning.value,
                            )
                            .zIndex(index.toFloat()),
                    ) {
                        if (!isRoot) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(entry.id) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                awaitPointerEvent()
                                            }
                                        }
                                    },
                            )
                        }

                        pageStateHolder.SaveableStateProvider(key = entry.id) {
                            CompositionLocalProvider(
                                LocalLifecycleOwner provides transition.lifecycleOwner,
                                LocalViewModelStoreOwner provides pageViewModelStoreOwner,
                                // Remembered: a fresh instance every recomposition would invalidate
                                // the whole page under a static local.
                                LocalPageTransitionState provides remember(transition) {
                                    PageTransitionState(
                                        // `Animatable` is no longer a `State` (animation-core 1.12);
                                        // `asState()` is the only way to hand it out read-only.
                                        progress = transition.progress.asState(),
                                        isTransitioning = transition.isTransitioning,
                                        slideDistancePx = hostWidthPx.toFloat(),
                                    )
                                },
                            ) {
                                if (!isRoot) {
                                    BackHandler(
                                        enabled = entry.id == navigator.current.id,
                                        onBack = ::closeTop,
                                    )
                                }
                                NavigationScope(
                                    entry = entry,
                                    navigateTo = ::open,
                                    goBack = ::closeTop,
                                ).content()
                            }
                        }

                        if (underlayDriver != null && scrimAlpha > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = underlayDriver.underlayProgress.value
                                    }
                                    .background(scrimColor.copy(alpha = scrimAlpha)),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.navigationPageReady(onReady: () -> Unit): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
            onReady()
        }
    }
