package com.nevoit.cirro.lyrics.ui.internal.layout

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/**
 * 子节点测量完成时回调 —— 跟 `com.nevoit.material.navigation` 的 `navigationPageReady` 同一个做法：
 * 先量完子节点再汇报，于是「回调已到」就等于「这一帧已经有真实几何了」。
 *
 * 播放页拿它把封面的收起动画推迟到歌词落定之后，免得歌词区还是空的、封面已经开始缩。
 * [ready] 为 null 时**不装这个布局节点**（默认路径零开销）。
 *
 * ⚠️ 每次布局都会调，幂等由调用方自己保证。
 */
internal fun Modifier.onMeasured(ready: (() -> Unit)?): Modifier =
    if (ready == null) this
    else
        layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            ready()
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
