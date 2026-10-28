package com.nevoit.cirro.ui

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.nevoit.cirro.ui.video.ArtistReflectionGeometry
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max

/**
 * 头部封面倒影的 GPU 版：拿一张软件位图，在自带的 EGL 上下文里**画一次**，读回成位图。
 *
 * 算法跟艺人页视频**逐段对应**，不是近似：
 * 1. `reflection_mirror.frag` 把封面和倒影镜像进金字塔第 0 层（全尺寸）；
 * 2. `artist_lpf_down.frag` 逐级 16-tap 下采样，直到第 [levels] 层；
 * 3. `reflection_composite.frag` 按 lod 在各级之间混合，半径随 y 线性增长。
 *
 * 早先这一版用源纹理自带的 mip 链代替金字塔，结果是 2x2 均值下采样，低级别糊成一格一格的
 * 方块，而且倒影带竖直拉伸 3 倍会让 mip 级别在各方向上的实际模糊不一致。金字塔没有这两个问题。
 *
 * 和视频的差别只剩纹理朝向：media3 给的纹理 t = 0 在底部，`GLUtils.texImage2D` 传上去的
 * t = 0 在顶部，所以 mirror 和 composite 里各有一处 `1.0 - …` 换成直接取值，并用
 * `reflection_flip.vert` 让第 0 行就是图像顶部，`glReadPixels` 直接写进 Bitmap 不用再翻。
 *
 * 全部资源只在 [executor] 这一个线程上碰，EGL 上下文也是它私有的，跟 Compose 的 RenderThread
 * 不共享。任何一步失败都返回 null，由调用方回落到 CPU 版本——所以 [Enabled] 关掉、或者这套东西
 * 在某台设备上跑不起来，都只是没变快，不会坏。
 */
internal object GlReflection {

    private const val Tag = "GlReflection"

    /** 出问题一行关掉，直接走原来的 CPU 倒影。 */
    const val Enabled = true

    /**
     * 日志里的版本号。改 shader 时顺手改一下：assets 读不到新版（只 Apply Changes 不重装）、
     * 或者 Coil 内存缓存命中旧图，都会表现为"调了参数没反应"，看这条日志能一眼分辨。
     */
    private const val ShaderVersion = "ramp2.0"

    /**
     * 视频里的 `uRadius`：`60f * side / referenceSide`。详情页这块方形边长跟那边的
     * `referenceSide` 是同一个量（都是 `min(屏宽, HeaderCoverCapPx)`），所以落到这里是常量 60。
     * 合成 shader 还会再乘 1.6，峰值半径 96px —— 跟视频完全一致。
     */
    internal const val VideoRadiusPx = 60f

    /**
     * 合成时**不采样**最粗的那几级。1024 宽出 8 级金字塔，第 7 级只有 8x10，放大 128 倍画出来
     * 就是一团浆糊；它原本承担的是 lod 6.0~6.6 那一段，去掉之后由第 6 级（16x20）加上合成里的
     * 3x3 核顶上，总模糊量基本不变，参与采样的内容却细了一倍。
     *
     * 想更糊：改成 0（把最粗一级也放进来，观感就跟视频完全一致）；想更清晰：往 1 以上加。
     */
    internal const val MaxLodTrim = 1

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reflection-gl").apply { isDaemon = true }
    }

    // 下面这几项只在 executor 线程上读写。
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var mirrorProgram = 0
    private var downProgram = 0
    private var compositeProgram = 0

    /** 金字塔最深一级。跟视频一样由半径推出来，跟图像尺寸无关。 */
    private var levels = 0
    @Volatile private var broken = false
    /** 只在 executor 线程上读写；成功的第一次打一条日志，用来确认走的是 GPU 而不是回落。 */
    private var logged = false
    private val uniforms = HashMap<String, Int>()

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    /**
     * 画一张倒影图。输入必须是软件位图（`ReflectionTransformation` 的请求已经关掉硬件位图）。
     *
     * [riseRatio] 以「方形边长」为 1，几何比例跟 CPU 版一一对应。[bleedRatio] 是模糊带往上溢进
     * 封面的高度（按倒影带高度算），跟 CPU 版共用同一个来源。
     */
    suspend fun render(
        context: Context,
        input: Bitmap,
        riseRatio: Float,
        bleedRatio: Float,
    ): Bitmap? {
        if (!Enabled || broken || input.config == Bitmap.Config.HARDWARE) return null
        return suspendCancellableCoroutine { continuation ->
            executor.execute {
                val result = runCatching { draw(context, input, riseRatio, bleedRatio) }
                    .getOrElse { error ->
                        Log.w(Tag, "GPU 倒影失败，回落 CPU", error)
                        null
                    }
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

    private fun draw(
        context: Context,
        input: Bitmap,
        riseRatio: Float,
        bleedRatio: Float,
    ): Bitmap? {
        // 镜像 shader 只有一个 uSide 同时当宽度和方形边长，所以只在输入本来就方正时接管。
        // CPU 版会把非方形居中裁成方形再拉伸，那条路继续留给它。
        if (input.width != input.height) return null

        ensureContext(context)

        val width = input.width
        val geometry = ArtistReflectionGeometry(width, riseRatio, bleedRatio)
        val height = geometry.height

        val source = IntArray(1)
        val pyramid = IntArray(levels + 1)
        val pyramidFramebuffers = IntArray(levels + 1)
        val target = IntArray(1)
        val targetFramebuffer = IntArray(1)
        try {
            GLES30.glGenTextures(1, source, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source[0])
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, input, 0)
            configureTexture()

            // 金字塔：第 0 层是全尺寸的镜像图，之后每层宽高减半。
            for (level in 0..levels) {
                val levelWidth = (width shr level).coerceAtLeast(1)
                val levelHeight = (height shr level).coerceAtLeast(1)
                GLES30.glGenTextures(1, pyramid, level)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyramid[level])
                // glTexStorage2D 而不是 glTexImage2D(…, null)：后者在 GLES20 的继承里有一堆 Buffer
                // 重载，传 null 在 Kotlin 里是重载歧义。
                GLES30.glTexStorage2D(
                    GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, levelWidth, levelHeight
                )
                configureTexture()

                GLES30.glGenFramebuffers(1, pyramidFramebuffers, level)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pyramidFramebuffers[level])
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D,
                    pyramid[level], 0
                )
                check(
                    GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
                            GLES30.GL_FRAMEBUFFER_COMPLETE
                ) { "第 $level 层 FBO 不完整" }
            }

            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)

            // 第 0 层：封面 + 镜像，含清晰的倒影带（模糊在最后一步做）。
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pyramidFramebuffers[0])
            GLES30.glViewport(0, 0, width, height)
            GLES30.glUseProgram(mirrorProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source[0])
            GLES30.glUniform1i(location(mirrorProgram, "uTexSampler"), 0)
            GLES30.glUniform1f(location(mirrorProgram, "uSide"), width.toFloat())
            GLES30.glUniform1f(location(mirrorProgram, "uHeight"), height.toFloat())
            GLES30.glUniform1f(location(mirrorProgram, "uRise"), geometry.rise.toFloat())
            GLES30.glUniform1f(
                location(mirrorProgram, "uStripFraction"),
                geometry.strip.toFloat() / width,
            )
            // 输入已经保证是方形，居中裁切自然退化成整张图。
            GLES30.glUniform4f(location(mirrorProgram, "uCrop"), 0f, 0f, 1f, 1f)
            drawQuad()

            // 逐级下采样。
            for (level in 1..levels) {
                val parentWidth = (width shr (level - 1)).coerceAtLeast(1)
                val parentHeight = (height shr (level - 1)).coerceAtLeast(1)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pyramidFramebuffers[level])
                GLES30.glViewport(
                    0, 0,
                    (width shr level).coerceAtLeast(1),
                    (height shr level).coerceAtLeast(1),
                )
                GLES30.glUseProgram(downProgram)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyramid[level - 1])
                GLES30.glUniform1i(location(downProgram, "uTexSampler"), 0)
                GLES30.glUniform2f(
                    location(downProgram, "uParentSize"),
                    parentWidth.toFloat(),
                    parentHeight.toFloat(),
                )
                drawQuad()
            }

            GLES30.glGenTextures(1, target, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target[0])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height)
            configureTexture()

            GLES30.glGenFramebuffers(1, targetFramebuffer, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFramebuffer[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D,
                target[0], 0
            )
            check(
                GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
                        GLES30.GL_FRAMEBUFFER_COMPLETE
            ) { "输出 FBO 不完整" }

            // 合成：各级各占一个纹理单元。
            GLES30.glViewport(0, 0, width, height)
            GLES30.glUseProgram(compositeProgram)
            for (level in 0..levels) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + level)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyramid[level])
                GLES30.glUniform1i(location(compositeProgram, "level$level"), level)
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glUniform2f(
                location(compositeProgram, "uImageSize"), width.toFloat(), height.toFloat()
            )
            GLES30.glUniform1f(location(compositeProgram, "uRadius"), VideoRadiusPx)
            val maxLod = (levels - MaxLodTrim).coerceAtLeast(1)
            GLES30.glUniform1f(location(compositeProgram, "uMaxLevel"), maxLod.toFloat())
            // 斜坡从 bleed 顶部起算（在接缝**上方**），跟 CPU 版和视频完全同一个来源。
            GLES30.glUniform1f(
                location(compositeProgram, "uBlurStart"), geometry.blurStart.toFloat()
            )
            GLES30.glUniform1f(
                location(compositeProgram, "uBlurHeight"), geometry.blurHeight.toFloat()
            )
            drawQuad()

            // 读回：`reflection_flip.vert` 已经把图像顶部放在第 0 行，正好对上 Bitmap 的行序。
            // RGBA 字节序也正好是 ARGB_8888 在内存里的排布，不用逐像素换序。
            val pixels = ByteBuffer
                .allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(
                0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels
            )
            pixels.position(0)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            output.copyPixelsFromBuffer(pixels)
            if (!logged) {
                logged = true
                Log.i(
                    Tag,
                    "GPU 倒影生效 $ShaderVersion ${width}x$height, 金字塔 ${levels + 1} 级, " +
                            "最高采样 L$maxLod, radius=${VideoRadiusPx * 1.6}px"
                )
            }
            return output
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (targetFramebuffer[0] != 0) GLES30.glDeleteFramebuffers(1, targetFramebuffer, 0)
            if (target[0] != 0) GLES30.glDeleteTextures(1, target, 0)
            for (level in 0..levels) {
                if (pyramidFramebuffers[level] != 0) {
                    GLES30.glDeleteFramebuffers(1, pyramidFramebuffers, level)
                }
                if (pyramid[level] != 0) GLES30.glDeleteTextures(1, pyramid, level)
            }
            if (source[0] != 0) GLES30.glDeleteTextures(1, source, 0)
        }
    }

    private fun configureTexture() {
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )
    }

    private fun drawQuad() {
        quad.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * uniform 位置。查不到返回 -1，`glUniform*` 对 -1 是规范允许的空操作。
     *
     * 这里**不能**抛异常：shader 里声明了却没用上的 uniform 会被编译器优化掉，位置就成了 -1。
     * 早先这里写的是 `check(it >= 0)`，结果一个没参与计算的 uniform 就让每一次渲染都在取位置时
     * 抛异常、被 `runCatching` 吞掉，GPU 路径一次都没跑成过。
     *
     * 三个 program 的 uniform 编号各自独立，所以 key 带上 program。
     */
    private fun location(program: Int, name: String): Int = uniforms.getOrPut("$program:$name") {
        GLES30.glGetUniformLocation(program, name).also {
            if (it < 0) Log.w(Tag, "uniform $name 没被 shader 使用，跳过")
        }
    }

    /** 建一次上下文和 program，之后复用。失败就标记 [broken]，不再重试。 */
    private fun ensureContext(context: Context) {
        if (display !== EGL14.EGL_NO_DISPLAY) return
        try {
            val newDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(newDisplay !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay 失败" }
            check(EGL14.eglInitialize(newDisplay, IntArray(2), 0, IntArray(2), 1)) {
                "eglInitialize 失败: 0x${EGL14.eglGetError().toString(16)}"
            }

            val config = chooseConfig(newDisplay)
            val newContext = EGL14.eglCreateContext(
                newDisplay,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
            check(newContext !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext 失败" }

            // 只往 FBO 里画，表面本身用不上，给个 1x1 的 pbuffer 让上下文有落脚点。
            val newSurface = EGL14.eglCreatePbufferSurface(
                newDisplay,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            check(newSurface !== EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface 失败" }
            check(EGL14.eglMakeCurrent(newDisplay, newSurface, newSurface, newContext)) {
                "eglMakeCurrent 失败"
            }

            // 层级数跟视频一样由半径推出：ceil(log2(1.6 * radius))。
            val textureUnits = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_IMAGE_UNITS, textureUnits, 0)
            levels = ceil(log2(max(1f, 1.6f * VideoRadiusPx))).toInt()
                .coerceAtMost(minOf(10, textureUnits[0] - 1))

            mirrorProgram = buildProgram(
                context,
                "shaders/reflection_flip.vert",
                readAsset(context, "shaders/reflection_mirror.frag"),
                "reflection_mirror.frag",
            )
            downProgram = buildProgram(
                context,
                "shaders/reflection_plain.vert",
                readAsset(context, "shaders/artist_lpf_down.frag"),
                "artist_lpf_down.frag",
            )
            compositeProgram = buildProgram(
                context,
                "shaders/reflection_plain.vert",
                compositeSource(context),
                "reflection_composite.frag",
            )
            display = newDisplay
            eglContext = newContext
            eglSurface = newSurface
        } catch (error: Throwable) {
            broken = true
            Log.w(Tag, "建 GL 上下文失败", error)
            throw error
        }
    }

    /**
     * 合成 shader 的源码：GLSL ES 1.00 不能对 sampler 数组做动态索引，所以按视频的做法把每级
     * 声明成一个独立的 sampler，再拼一条 if 链出来。
     */
    private fun compositeSource(context: Context): String = buildString {
        append("precision highp float;\nvarying vec2 vTexCoord;\n")
        for (level in 0..levels) append("uniform sampler2D level$level;\n")
        append("vec4 atLevel(vec2 p, int level) {\n")
        for (level in 0 until levels) {
            append("if (level == $level) return texture2D(level$level, p);\n")
        }
        append("return texture2D(level$levels, p);\n}\n")
        append(readAsset(context, "shaders/reflection_composite.frag"))
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) {
            "eglChooseConfig 失败"
        }
        check(count[0] > 0) { "没有可用的 GLES3 EGLConfig" }
        return checkNotNull(configs[0])
    }

    private fun buildProgram(
        context: Context,
        vertexAsset: String,
        fragmentSource: String,
        fragmentName: String,
    ): Int {
        val vertex = compile(
            GLES30.GL_VERTEX_SHADER, readAsset(context, vertexAsset), vertexAsset
        )
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource, fragmentName)
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vertex)
        GLES30.glAttachShader(id, fragment)
        GLES30.glBindAttribLocation(id, 0, "aFramePosition")
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        val log = GLES30.glGetProgramInfoLog(id)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        check(status[0] == GLES30.GL_TRUE) { "program $fragmentName 链接失败: $log" }
        return id
    }

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun compile(type: Int, source: String, name: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) {
            "shader $name 编译失败: ${GLES30.glGetShaderInfoLog(shader)}"
        }
        return shader
    }
}
