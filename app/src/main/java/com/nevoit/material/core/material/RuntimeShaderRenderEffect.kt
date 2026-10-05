package com.nevoit.material.core.material

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

internal fun runtimeShaderRenderEffect(
    shader: RuntimeShader,
    uniformShaderName: String
): RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

    return android.graphics.RenderEffect
        .createRuntimeShaderEffect(shader, uniformShaderName)
        .asComposeRenderEffect()
}
