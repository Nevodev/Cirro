package com.nevoit.cirro.macrobenchmark

import android.os.Bundle
import android.os.SystemClock
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualBaselineProfile {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun capture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("manualProfile") == "true")
        val targetPackage = requireNotNull(arguments.getString("targetPackage"))
        val finishFile = "/data/local/tmp/cirro-manual-profile-finish"

        val result = rule.collectWithResults(
            packageName = targetPackage,
            maxIterations = 1,
            stableIterations = 1,
            outputFilePrefix = "manual",
            includeInStartupProfile = false,
        ) {
            startActivityAndWait()
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\n采集已开始。请手动操作；完成后运行 finish 命令。\n")
            })
            val deadline = SystemClock.elapsedRealtime() + 30 * 60 * 1000L
            // 单轮等待用户结束，避免自动重放操作；登录数据由独立安装步骤保留。
            while (device.executeShellCommand("ls $finishFile").trim() != finishFile) {
                check(SystemClock.elapsedRealtime() < deadline) {
                    "Manual capture timed out after 30 minutes; no profile was exported."
                }
                SystemClock.sleep(1000)
            }
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("manualProfilePath", result.baselineProfiles.single())
        })
    }
}
