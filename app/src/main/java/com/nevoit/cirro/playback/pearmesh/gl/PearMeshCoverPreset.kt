package com.nevoit.cirro.playback.pearmesh.gl

import java.security.MessageDigest

internal object PearMeshCoverPreset {
    const val Side = 512
    const val Time = 48.1093
    const val BlurMultiplier = 0.5f
    const val ScrimAlpha = 0f

    private const val MorphProgress = 0.62f
    private val source = PearMeshPresets.landscape[4]
    // 固定最终插值形状，避免渲染时钟再施加一次 A/B 过渡。
    private val points = Array(source.from.size) { index ->
        source.from[index] * (1f - MorphProgress) + source.to[index] * MorphProgress
    }

    val mesh = MeshPreset(points, points)
    val cacheKey: String = MessageDigest.getInstance("SHA-256").digest(
        buildString {
            append("$Side:$Time:$BlurMultiplier:$ScrimAlpha")
            for (point in points) append(":${point.x}:${point.y}")
        }.toByteArray(Charsets.UTF_8),
    ).joinToString("") { "%02x".format(it) }
}
