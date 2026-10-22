package com.nevoit.cirro.macrobenchmark

import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackRowBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val arguments = InstrumentationRegistry.getArguments()
    private val targetPackage = requireNotNull(arguments.getString("targetPackage"))
    private val iterations = (arguments.getString("iterations") ?: "25").toInt().also {
        require(it >= 5) { "Use at least 5 iterations; 25 is recommended." }
    }

    @Test
    fun openCollection() = measure(
        setup = { prepareEntry() },
        action = { enterCollection() },
    )

    @Test
    fun scrollDown() = measure(
        setup = {
            prepareEntry()
            enterCollection()
        },
        action = { scroll(forward = true) },
    )

    @Test
    fun scrollBack() = measure(
        setup = {
            prepareEntry()
            enterCollection()
            scroll(forward = true)
        },
        action = { scroll(forward = false) },
    )

    private fun measure(
        setup: MacrobenchmarkScope.() -> Unit,
        action: MacrobenchmarkScope.() -> Unit,
    ) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Disable,
            warmupIterations = 3,
        ),
        iterations = iterations,
        startupMode = StartupMode.WARM,
        setupBlock = setup,
        measureBlock = action,
    )

    private fun MacrobenchmarkScope.prepareEntry() {
        // Recreate the activity outside the capture; repository/image caches stay in the process.
        startActivityAndWait()
        await(By.pkg(targetPackage).desc("资料库")).click()
        awaitText("喜爱歌曲")
        SystemClock.sleep(500)
    }

    private fun MacrobenchmarkScope.enterCollection() {
        awaitText("喜爱歌曲").click()
        check(device.wait(Until.hasObject(By.pkg(targetPackage).res("collection-tracks-ready")), 30_000)) {
            "Liked songs did not load. Log in and open 资料库 → 喜爱歌曲 once before benchmarking."
        }
        // Accessibility text can precede presentation. Include the existing 200ms appearance fade.
        SystemClock.sleep(350)
    }

    private fun MacrobenchmarkScope.scroll(forward: Boolean) {
        val x = device.displayWidth / 2
        val top = (device.displayHeight * 0.30f).toInt()
        val bottom = (device.displayHeight * 0.68f).toInt()
        repeat(10) {
            check(device.swipe(x, if (forward) bottom else top, x, if (forward) top else bottom, 36))
            SystemClock.sleep(200)
        }
        SystemClock.sleep(350)
    }

    private fun MacrobenchmarkScope.awaitText(text: String): UiObject2 =
        await(By.pkg(targetPackage).text(text))

    private fun MacrobenchmarkScope.await(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), 15_000)) {
            "UI element '$selector' was not visible. Check the entry path and login state."
        }
}
