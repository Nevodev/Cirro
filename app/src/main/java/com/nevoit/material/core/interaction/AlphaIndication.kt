package com.nevoit.material.core.interaction

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.draw.CacheDrawModifierNode
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object AlphaIndicationDefaults {
    const val MIN_ALPHA = 0.5f
}

/** 依赖外部背景的混合内容（如 Plus）应在自己的混合图层内使用，避免整体透明图层隔离背景。 */
data class AlphaIndication(
    val minAlpha: Float = AlphaIndicationDefaults.MIN_ALPHA,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return AlphaIndicationNode(interactionSource, minAlpha)
    }
}

private class AlphaIndicationNode(
    private val interactionSource: InteractionSource,
    minAlpha: Float,
) : DelegatingNode() {

    private var alphaAnimatable = Animatable(1f)
    private val targetAlpha = minAlpha.fastCoerceIn(0f, 1f)

    init {
        delegate(CacheDrawModifierNode {
            val layer = obtainGraphicsLayer()
            onDrawWithContent {
                val alpha = alphaAnimatable.value
                if (alpha >= 1f) {
                    drawContent()
                } else {
                    // 整体淡化才能让重叠内容保持原来的相对颜色；每次绘制重新记录，避免内容更新后复用旧画面。
                    layer.record { this@onDrawWithContent.drawContent() }
                    layer.alpha = alpha
                    drawLayer(layer)
                }
            }
        })
    }

    override fun onAttach() {
        super.onAttach()
        // 节点重新挂载时可能收不到上一次按压的 Release/Cancel。
        alphaAnimatable = Animatable(1f)
        coroutineScope.launch {
            interactionSource.interactions.collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        alphaAnimatable.animateTo(targetAlpha, DimIndicationDefaults.PressIn)
                    }

                    is PressInteraction.Release -> {
                        val halfwayAlpha = (1f + targetAlpha) * 0.5f
                        if (alphaAnimatable.value > halfwayAlpha) {
                            alphaAnimatable.animateTo(
                                halfwayAlpha,
                                DimIndicationDefaults.ReleaseOutFast,
                            )
                        }
                        alphaAnimatable.animateTo(1f, DimIndicationDefaults.ReleaseOut)
                    }

                    is PressInteraction.Cancel -> {
                        alphaAnimatable.animateTo(1f, DimIndicationDefaults.ReleaseOut)
                    }
                }
            }
        }
    }
}
