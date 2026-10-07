package com.nevoit.cirro.lyrics.ui.composable.list

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * The leading item's scroll keeps its configured duration/easing; following items spring behind it.
 *
 * 两个旋钮各管一件事，别混着调（质量恒为 1，位移单位是像素）：
 *
 * - **快慢 = [stiffness]**：自然频率 `ω₀ = √stiffness`（rad/s）。100 → 周期 0.63s；400 → 0.31s。
 * - **弹不弹 = [damping] / (2·√stiffness)**：阻尼比 `ζ`。`1.0` 临界（一丝不过冲，但显得木）；
 *   `0.5` 过冲约 16%（明显弹性）；`0.6` 约 9.5%。
 *
 * 只调 [damping] 是错的：它改的是"弹不弹"，完全不改节奏。想要"又快又弹"必须一起抬
 * [stiffness]，[damping] 按 `≈1.2·√stiffness` 跟着走。
 *
 * 嫌裸物理量不好读就用 [of] —— 换成项目里那套「感知时长 + 回弹」的参数化。
 */
@Immutable
data class LyricsScrollChain(
    val stiffness: Float = 400f,
    val damping: Float = 20f,
    val coupling: Float = 0.35f,
    val distanceFalloff: Float = 0.05f,
    val minResponse: Float = 0.35f,
) {
    init {
        require(stiffness.isFinite() && stiffness in 1f..1000f)
        require(damping.isFinite() && damping in 1f..100f)
        require(coupling.isFinite() && coupling in 0f..0.95f)
        require(distanceFalloff.isFinite() && distanceFalloff in 0f..1f)
        require(minResponse.isFinite() && minResponse in 0.05f..1f)
    }

    companion object {
        /**
         * [stiffness] 上限 1000 反推出的时长下限：`2π / √1000` 秒 ≈ 198.7ms。
         * 比它短的话换算出来的刚度会超出 [init] 的校验范围。
         */
        const val MinDurationMillis = 198.7f

        /**
         * [stiffness] 下限 1 反推出的时长上限：`2π` 秒 ≈ 6283ms（这里取 6280 留一点余量）。
         * 比它长的话换算出来的刚度会掉到 1 以下。
         */
        const val MaxDurationMillis = 6280f

        /**
         * 用 `com.nevoit.material.core.animation.spring` 那套参数化造一份：**感知时长 + 回弹**，
         * 而不是刚度 + 阻尼。想调手感时优先用这个，比裸物理量直观。
         *
         * 换算就是那个函数的内部实现（`material/core/animation/Spring.kt`）——积分器
         * （`LyricsScrollChainState.advance()`，mass 恒为 1、`a = -k·x - c·v`）要的是 `k` 和 `c`
         * 两个 float，吃不了 `SpringSpec`，所以只能借公式、不能用它的返回值：
         *
         * ```
         * ω₀        = 2π / (durationMillis / 1000)
         * stiffness = ω₀²
         * ζ         = 1 - bounce              // bounce < 0 时是 over-damped 分支 1/(1 + bounce)
         * damping   = 2 · ζ · √stiffness      // c = 2ζ√(k·m)，m = 1
         * ```
         *
         * ⚠️ **`durationMillis` 是一个自然周期 `2π/ω₀`，不是"多久落定"。** ζ < 1 时实际停稳比它长
         * 得多（包络 `e^(-ζω₀t)`）：ζ=0.5 要 ×1.47，ζ=0.7 要 ×1.05，ζ=1 才 ×0.73。
         *
         * ⚠️ **`bounce` 是 `1 - ζ`，不是"越大越弹"的百分比**，别乱给：`0` 完全不弹（显得木）、
         * `0.5` 过冲约 16%、`0.4` 过冲约 9.5%；`≥0.8` 时 ζ≤0.2、几乎不衰减，看起来像坏了。
         *
         * 常见对应（左列 ≡ 右列）。左上角那组就是本类默认值：
         *
         * | `of(durationMillis, bounce)` | `(stiffness, damping)` | ω₀ | ζ | 周期 |
         * |---|---|---|---|---|
         * | `(250f, 0.5f)` | `(632f, 25.1f)` | 25.1 | 0.50 | 0.25s |
         * | `(314f, 0.5f)` | `(400f, 20f)`   | 20.0 | 0.50 | 0.31s |
         * | `(314f, 0.4f)` | `(400f, 24f)`   | 20.0 | 0.60 | 0.31s |
         * | `(314f, 0f)`   | `(400f, 40f)`   | 20.0 | 1.00 | 0.31s |
         * | `(500f, 0.5f)` | `(158f, 12.6f)` | 12.6 | 0.50 | 0.50s |
         *
         * [coupling] / [distanceFalloff] / [minResponse] 原样沿用默认值：换参数化**不碰**它们，
         * 衰减仍是"每远一行，刚度乘一个系数"那套（见 `internal/layout/LyricsScrollChainState.kt`
         * 的 `configure`）。
         */
        fun of(durationMillis: Float, bounce: Float = 0f): LyricsScrollChain {
            require(durationMillis.isFinite() && durationMillis > 0f) {
                "Duration must be positive, was $durationMillis"
            }
            val stiffness =
                ((2.0 * PI / (durationMillis / 1000.0)).let { it * it }).toFloat()
            require(stiffness in 1f..1000f) {
                "durationMillis=$durationMillis 换算出的 stiffness=$stiffness 超出 init 的 " +
                        "1f..1000f，可用范围约 $MinDurationMillis..$MaxDurationMillis"
            }
            // ζ 夹在「让 damping 留在 1f..100f」的区间里，免得 init 抛一个没有上下文的异常。
            val ratio =
                (if (bounce >= 0f) 1f - bounce else 1f / (1f + bounce))
                    .coerceIn(1f / (2f * sqrt(stiffness)), 100f / (2f * sqrt(stiffness)))
            return LyricsScrollChain(
                stiffness = stiffness,
                damping = 2f * ratio * sqrt(stiffness),
            )
        }
    }
}
