package com.nevoit.cirro.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nevoit.material.theme.tokens.Springs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 松手时判定"这是要开还是要关"的速度门槛（每秒的展开进度）。 */
private const val SettleVelocity = 1.5f

/**
 * 播放页的展开进度。
 *
 * 播放页不是导航目的地、也不是独立窗口，而是常驻在最外层的覆盖层——它和吸底迷你条要由
 * **同一个进度值**同时驱动（容器展开、封面接续），所以这个值不能活在
 * 任何一个页面的组合里，得提到外面来。这里就是它唯一的真相源。
 *
 * **`progress` 只能在 draw / layer 阶段读**（`graphicsLayer`、`Canvas`、`drawBackground`
 * 这类 lambda 里）。它背后是快照状态，动画期间每帧都在变；在组合期读会让调用方每帧重组
 * 一整棵子树——这正是这个项目一直在躲的那件事。需要"要不要参与组合"这种粗粒度判断时，
 * 用 [present]。
 *
 * **动画由界面驱动**（[animate] 要在 `LaunchedEffect` 里跑）：弹簧动画每一帧都要挂到帧
 * 时钟上取下一帧，而帧时钟只有 Compose 的组合协程带着。控制器这边只负责说"要去哪、
 * 用多快的速度"，以及拖拽时把值同步按下去。
 */
class PlayerUiController {

    private val anim = Animatable(0f)

    /**
     * 拖拽落值用。必须是 `Main.immediate`：拖拽回调本来就在主线程，immediate 让
     * `launch` 的 body 当场跑完，值这一帧就到位；换成普通 `Main` 会排到消息队列末尾，
     * 手指和画面差一帧，快速拖能看出拖影。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 展开进度：0 = 只剩吸底迷你条，1 = 播放页完全铺开。**只在 draw/layer 阶段读。** */
    val progress: Float get() = anim.value

    /**
     * 覆盖层是否参与组合。
     *
     * 收干净之后整层从组合里摘掉——否则播放页那棵子树会一直挂在那儿，即使它被推到屏幕外。
     * 开合各翻转一次，可以放心在组合期读。
     */
    var present: Boolean by mutableStateOf(false)
        private set

    var isClosing: Boolean by mutableStateOf(false)
        private set

    var isDragging: Boolean by mutableStateOf(false)
        private set

    val acceptsInput: Boolean get() = present && !isClosing

    /**
     * 动画请求序号。开、合、松手各 +1，界面看到它变了就跑一次新动画（上一轮自动被打断）。
     *
     * 用序号而不是目标值：连着两次"关"应该各跑一次动画，光看目标值变没变是看不出来的。
     */
    var animationRequest: Int by mutableIntStateOf(0)
        private set

    private var target = 0f
    private var velocity = 0f

    fun open() = request(open = true, velocity = null)

    fun close() = request(open = false, velocity = null)

    /**
     * 拖拽跟手。[deltaProgress] 是这一帧的进度增量，正数朝展开方向。
     *
     * `snapTo` 会取消正在跑的开合动画，所以手指一按下去就能接管。
     */
    fun onDrag(deltaProgress: Float) {
        if (!present) present = true
        isClosing = false
        isDragging = true
        val next = (anim.value + deltaProgress).coerceIn(0f, 1f)
        scope.launch { anim.snapTo(next) }
    }

    /**
     * 松手。[velocityProgress] 是松手瞬间的进度速度，正数朝展开。
     *
     * 甩得够快就按方向走，甩不动才看停在哪半边——跟系统手势的习惯一致。
     */
    fun onDragEnd(velocityProgress: Float) {
        val open = when {
            velocityProgress > SettleVelocity -> true
            velocityProgress < -SettleVelocity -> false
            else -> anim.value > 0.5f
        }
        request(open, velocityProgress)
    }

    /** 界面侧跑动画前拿一次这一轮的目的地与初速。 */
    fun animationTarget(): Pair<Float, Float> = target to velocity

    /** 界面侧这轮动画跑完了（被下一轮打断时不会调到）。 */
    fun onAnimationSettled() {
        if (target <= 0f) present = false
    }

    /**
     * 跑一次开合动画。**必须在带帧时钟的协程里调**，也就是 `LaunchedEffect`。
     *
     * 沿用松手时的速度，所以拖到一半松手不会"顿一下再走"。
     */
    suspend fun animate() {
        anim.animateTo(
            targetValue = target,
            animationSpec = if (target > 0.5f) OpenSpec else CloseSpec,
            initialVelocity = velocity,
        )
    }

    private fun request(open: Boolean, velocity: Float?) {
        present = true
        isClosing = !open
        isDragging = false
        target = if (open) 1f else 0f
        // collectLatest cancels the previous animation and clears Animatable.velocity; capture it
        // at the request boundary so reversing a running spring keeps its current momentum.
        this.velocity = velocity ?: anim.velocity
        animationRequest++
    }

    private companion object {
        val OpenSpec =
            Springs.smooth(durationMillis = 350, visibilityThreshold = 0.0001f, extraBounce = -0.1)
        val CloseSpec = Springs.smooth(durationMillis = 350, visibilityThreshold = 0.0001f)
    }
}
