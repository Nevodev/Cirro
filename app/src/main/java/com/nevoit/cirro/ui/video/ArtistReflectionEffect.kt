package com.nevoit.cirro.ui.video

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.ceil
import kotlin.math.log2

@OptIn(UnstableApi::class)
internal class ArtistReflectionEffect(
    private val riseRatio: Float,
    private val referenceSide: Int,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ReflectionShaderProgram(context, useHdr, riseRatio, referenceSide)
}

@OptIn(UnstableApi::class)
private class ReflectionShaderProgram(
    context: Context,
    private val useHdr: Boolean,
    private val riseRatio: Float,
    private val referenceSide: Int,
) : BaseGlShaderProgram(useHdr, 1) {
    private val vertex =
        context.assets.open("shaders/artist_header.vert").bufferedReader().use { it.readText() }
    private val compositeSource =
        context.assets.open("shaders/artist_lpf_composite.frag").bufferedReader()
            .use { it.readText() }
    private val programs = mutableListOf<GlProgram>()
    private val pyramid = mutableListOf<GlTextureInfo>()
    private val outputFbo = IntArray(1)
    private lateinit var geometry: ArtistReflectionGeometry
    private var composite: GlProgram? = null
    private val mirror: GlProgram
    private val down: GlProgram

    init {
        try {
            mirror = program(
                context.assets.open("shaders/artist_reflection.frag").bufferedReader()
                    .use { it.readText() })
            down = program(
                context.assets.open("shaders/artist_lpf_down.frag").bufferedReader()
                    .use { it.readText() })
        } catch (error: Exception) {
            programs.forEach { runCatching { it.delete() } }
            throw VideoFrameProcessingException.from(error)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        try {
            pyramid.forEach { it.release() }
            pyramid.clear()
            composite?.let { it.delete(); programs.remove(it) }
            val side = minOf(inputWidth, inputHeight, referenceSide)
            geometry = ArtistReflectionGeometry(side, riseRatio)
            // LPF 半径与封面 box 模糊的半径不等价，单独控制其强度并按处理分辨率折算。
            val radius = 60f * side / referenceSide
            val limits = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, limits, 0)
            val levels =
                ceil(log2(maxOf(1f, 1.6f * radius))).toInt().coerceAtMost(minOf(10, limits[0] - 1))
            for (level in 0..levels) {
                val width = (side shr level).coerceAtLeast(1)
                val height = (geometry.height shr level).coerceAtLeast(1)
                val texture = GlUtil.createTexture(width, height, useHdr)
                try {
                    pyramid += GlTextureInfo(
                        texture,
                        GlUtil.createFboForTexture(texture),
                        -1,
                        width,
                        height
                    )
                } catch (error: Exception) {
                    GlUtil.deleteTexture(texture)
                    throw error
                }
            }
            mirror.setFloatUniform("uSide", side.toFloat())
            mirror.setFloatUniform("uHeight", geometry.height.toFloat())
            mirror.setFloatUniform("uRise", geometry.rise.toFloat())
            mirror.setFloatUniform("uStripFraction", geometry.strip.toFloat() / side)
            val crop = minOf(inputWidth, inputHeight).toFloat()
            mirror.setFloatsUniform(
                "uCrop", floatArrayOf(
                    (inputWidth - crop) / (2f * inputWidth),
                    (inputHeight - crop) / (2f * inputHeight),
                    crop / inputWidth,
                    crop / inputHeight,
                )
            )
            composite = program(buildString {
                append("precision highp float;\nvarying vec2 vTexCoord;\n")
                for (level in 0..levels) append("uniform sampler2D level$level;\n")
                append("vec4 atLevel(vec2 p, int level) {\n")
                for (level in 0 until levels) append("if (level == $level) return texture2D(level$level, p);\n")
                append("return texture2D(level$levels, p);\n}\n")
                append(compositeSource)
            }).apply {
                setFloatsUniform(
                    "uImageSize",
                    floatArrayOf(side.toFloat(), geometry.height.toFloat())
                )
                setFloatUniform("uRadius", radius)
                setFloatUniform("uMaxLevel", levels.toFloat())
                setFloatUniform("uBlurStart", geometry.blurStart.toFloat())
                setFloatUniform("uBlurHeight", geometry.blurHeight.toFloat())
            }
            return Size(side, geometry.height)
        } catch (error: Exception) {
            throw VideoFrameProcessingException.from(error)
        }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            // Media3 拥有最终输出 FBO；中间各层渲染结束后必须恢复它，不能把内部纹理交给下游。
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, outputFbo, 0)
            focus(pyramid[0])
            mirror.use()
            mirror.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            draw(mirror)
            for (level in 1 until pyramid.size) {
                val parent = pyramid[level - 1]
                focus(pyramid[level])
                down.use()
                down.setSamplerTexIdUniform("uTexSampler", parent.texId, 0)
                down.setFloatsUniform(
                    "uParentSize",
                    floatArrayOf(parent.width.toFloat(), parent.height.toFloat())
                )
                draw(down)
            }
            GlUtil.focusFramebufferUsingCurrentContext(outputFbo[0], geometry.side, geometry.height)
            val final = checkNotNull(composite)
            final.use()
            pyramid.forEachIndexed { level, texture ->
                final.setSamplerTexIdUniform(
                    "level$level",
                    texture.texId,
                    level
                )
            }
            draw(final)
            GlUtil.checkGlError()
        } catch (error: Exception) {
            throw VideoFrameProcessingException.from(error, presentationTimeUs)
        }
    }

    override fun release() {
        var failure: Exception? = null
        val actions = listOf<() -> Unit>({ super.release() }) +
                pyramid.map { { it.release() } } + programs.map { { it.delete() } }
        for (action in actions) try {
            action()
        } catch (error: Exception) {
            failure = error
        }
        pyramid.clear()
        programs.clear()
        failure?.let { throw VideoFrameProcessingException.from(it) }
    }

    private fun program(fragment: String) = GlProgram(vertex, fragment).also {
        programs += it
        it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
    }

    private fun focus(texture: GlTextureInfo) =
        GlUtil.focusFramebufferUsingCurrentContext(texture.fboId, texture.width, texture.height)

    private fun draw(program: GlProgram) {
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
