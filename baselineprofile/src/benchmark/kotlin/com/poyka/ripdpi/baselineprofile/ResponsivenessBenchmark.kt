package com.poyka.ripdpi.baselineprofile

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Same production screens and inputs on both revisions; no network or service actions. */
@RunWith(AndroidJUnit4::class)
class ResponsivenessBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    @Test fun logsCounterBurst() = measure("logs")

    @Test fun telemetryShellCounterBurst() = measure("telemetry")

    @Test fun catalog100() = measure("catalog", 100)

    @Test fun catalog1000() = measure("catalog", 1000)

    @Test fun catalog5000() = measure("catalog", 5000)

    private fun measure(
        workload: String,
        size: Int = 100,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appInfo = instrumentation.context.packageManager.getApplicationInfo(PackageName, 0)
        assertEquals("Use the release-derived benchmark APK", 0, appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val source =
            requireNotNull(InstrumentationRegistry.getArguments().getString("responsivenessSource")) {
                "Pass -Pandroid.testInstrumentationRunnerArguments.responsivenessSource=<source-sha>"
            }
        benchmarkRule.measureRepeated(
            packageName = PackageName,
            metrics =
                listOf(
                    FrameTimingMetric(),
                    TraceSectionMetric("RIPDPI:counterDispatch", TraceSectionMetric.Mode.Sum),
                ),
            compilationMode = CompilationMode.None(),
            iterations = 3,
            setupBlock = {
                killProcess()
                startActivityAndWait(
                    Intent().apply {
                        component = ComponentName(PackageName, "$PackageName.benchmark.ResponsivenessActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra("workload", workload)
                        putExtra("size", size)
                        putExtra("source", source)
                    },
                )
                awaitText("$workload/ready/tick=0/size=$size")
                when (workload) {
                    "logs" -> requireNode("logs-stream")
                    "telemetry" -> requireNode("settings-screen")
                    "catalog" -> requireNode("profile_utility_search-query")
                }
                device.waitForIdle()
            },
        ) {
            requireNode("responsiveness-run").click()
            awaitText("$workload/done/tick=100/size=$size")
            if (workload == "catalog") exerciseCatalog(size)
            if (workload == "logs") {
                assertTrue(
                    "Terminal event must reach the real log screen",
                    device.wait(Until.hasObject(By.textContains("Benchmark event 250")), TimeoutMillis),
                )
            }
        }
    }

    private fun MacrobenchmarkScope.exerciseCatalog(size: Int) {
        val search = requireNode("profile_utility_search-query")
        val label = "Profile ${(size - 1).toString().padStart(5, '0')}"
        search.text = label
        device.pressBack()
        // The text field also contains the query, so assert the actual result row's identity.
        requireNode("profile_utility_native:benchmark-${size - 1}_row")
        requireNode("profile_utility_search-clear").click()
        requireNode("profile_utility_native:benchmark-0_row")
        val before = visibleCatalogRows()
        assertTrue("The catalog must expose visible production rows", before.isNotEmpty())
        repeat(3) {
            requireNode("profile_utility-screen").swipe(Direction.UP, 0.65f)
        }
        device.waitForIdle()
        val after = visibleCatalogRows()
        assertTrue("Scroll must reveal different production rows: $before -> $after", after.any { it !in before })
        Log.i("RipDpiResponsiveness", "catalogSize=$size visibleRowsBefore=$before visibleRowsAfter=$after")
    }

    private fun MacrobenchmarkScope.visibleCatalogRows(): Set<String> =
        device
            .findObjects(By.res(Pattern.compile("profile_utility_native:benchmark-[0-9]+_row")))
            .filter { !it.visibleBounds.isEmpty }
            .map { it.resourceName }
            .toSet()

    private fun MacrobenchmarkScope.awaitText(text: String) {
        assertTrue("Missing driver checkpoint: $text", device.wait(Until.hasObject(By.text(text)), TimeoutMillis))
    }

    private fun MacrobenchmarkScope.requireNode(id: String): UiObject2 =
        requireNotNull(device.wait(Until.findObject(By.res(id)), TimeoutMillis)) { "Missing resource-id: $id" }

    private companion object {
        const val PackageName = "com.poyka.ripdpi"
        const val TimeoutMillis = 15_000L
    }
}
