package com.poyka.ripdpi.performance

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compare the same workloads before dependency changes, after dependency changes,
 * and after the Styles migration. Keep this harness identical in all three APKs.
 *
 * Frame tests use real screen composables with fixed local data. They do not
 * measure product navigation, storage, network, or service behavior. Production
 * Application initialization still runs outside the frame measurement blocks.
 */
@RunWith(AndroidJUnit4::class)
class ComposeStylesBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Before
    fun requireProfileableTarget() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("The profileable rendering comparison requires API 29 or newer")
        }
        val packageManager = InstrumentationRegistry.getInstrumentation().context.packageManager

        @Suppress("DEPRECATION")
        val application = packageManager.getApplicationInfo(TargetPackage, 0)
        check(application.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            "Use the release-derived GithubFullBenchmark APK, not a debug APK"
        }
        check(application.isProfileableByShell) { "The target APK must be profileable by shell" }
    }

    @Test
    fun settingsScroll() = measureScrollScene("settings")

    @Test
    fun logsScroll() = measureScrollScene("logs")

    @Test
    fun controlInteractions() {
        benchmarkRule.measureRepeated(
            packageName = TargetPackage,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            iterations = Iterations,
            setupBlock = { openScene("controls") },
        ) {
            val button = checkNotNull(device.findObject(By.res("style-benchmark-button")))
            val chip = checkNotNull(device.findObject(By.res("style-benchmark-chip")))
            val toggle = checkNotNull(device.findObject(By.res("style-benchmark-switch")))
            repeat(ControlCycles) {
                button.click(PressDurationMillis)
                chip.click(PressDurationMillis)
                toggle.click(PressDurationMillis)
                device.waitForIdle()
            }
            val completed = By.res("style-benchmark-result").text("Actions: 10/10/10")
            check(device.wait(Until.hasObject(completed), ReadyTimeoutMillis)) {
                "All control actions must update the local state"
            }
        }
    }

    /** Product startup is a separate result from controlled screen rendering. */
    @Test
    fun startupCold() {
        benchmarkRule.measureRepeated(
            packageName = TargetPackage,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Full(),
            iterations = Iterations,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }
    }

    private fun measureScrollScene(scene: String) {
        benchmarkRule.measureRepeated(
            packageName = TargetPackage,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            iterations = Iterations,
            setupBlock = { openScene(scene) },
        ) {
            val bounds =
                checkNotNull(device.findObject(By.scrollable(true))) {
                    "The $scene workload must contain a scrollable list"
                }.visibleBounds
            val x = bounds.centerX()
            val top = bounds.top + bounds.height() / 4
            val bottom = bounds.bottom - bounds.height() / 4
            repeat(ScrollCycles) {
                repeat(SwipesPerDirection) {
                    check(device.swipe(x, bottom, x, top, SwipeSteps)) { "Scroll down failed" }
                    device.waitForIdle()
                }
                repeat(SwipesPerDirection) {
                    check(device.swipe(x, top, x, bottom, SwipeSteps)) { "Scroll up failed" }
                    device.waitForIdle()
                }
            }
        }
    }

    private fun MacrobenchmarkScope.openScene(scene: String) {
        pressHome()
        startActivityAndWait(
            Intent(Intent.ACTION_MAIN)
                .setClassName(TargetPackage, TargetActivity)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(SceneExtra, scene),
        )
        check(device.wait(Until.hasObject(By.res(sceneTag(scene))), ReadyTimeoutMillis)) {
            "The $scene rendering workload did not become ready"
        }
        device.waitForIdle()
    }

    private fun sceneTag(scene: String): String = "style-benchmark-$scene"

    private companion object {
        const val TargetPackage = "com.poyka.ripdpi"
        const val TargetActivity = "com.poyka.ripdpi.benchmark.ComposeStylesBenchmarkActivity"
        const val SceneExtra = "com.poyka.ripdpi.benchmark.SCENE"
        const val Iterations = 10
        const val ControlCycles = 10
        const val PressDurationMillis = 100L
        const val ReadyTimeoutMillis = 5_000L
        const val ScrollCycles = 2
        const val SwipesPerDirection = 3
        const val SwipeSteps = 30
    }
}
