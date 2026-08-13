package com.poyka.ripdpi.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrollFrameTimingBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun logsScroll() {
        benchmarkRule.measureRepeated(
            packageName = PackageName,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            iterations = ScrollIterations,
            setupBlock = {
                pressHome()
                startAutomationActivity(startRoute = "logs", dataPreset = "diagnostics_demo")
                requireObject("logs-screen")
                requireObject("logs-content").scroll(Direction.DOWN, 1f)
                requireObject("logs-stream-list")
                device.waitForIdle()
            },
            measureBlock = {
                val measuredContainer = requireObject("logs-stream-list")
                scrollDown(measuredContainer)
            },
        )
    }

    @Test
    fun diagnosticsLiveScroll() {
        benchmarkRule.measureRepeated(
            packageName = PackageName,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            iterations = ScrollIterations,
            setupBlock = {
                pressHome()
                startAutomationActivity(startRoute = "diagnostics", dataPreset = "diagnostics_demo")
                requireObject("diagnostics-screen")
                requireObject("diagnostics-section-live").click()
                requireObject("diagnostics-live-list")
                device.waitForIdle()
            },
            measureBlock = {
                val measuredContainer = requireObject("diagnostics-live-list")
                scrollDown(measuredContainer)
            },
        )
    }

    @Test
    fun historyScroll() {
        benchmarkRule.measureRepeated(
            packageName = PackageName,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            iterations = ScrollIterations,
            setupBlock = {
                pressHome()
                startAutomationActivity(startRoute = "history", dataPreset = "diagnostics_report_demo")
                requireObject("history-screen")
                requireObject("history-section-events").click()
                requireObject("history-events-state-content")
                requireObject("history-event-automation-live-event-0")
                requireObject("history-events-list")
                device.waitForIdle()
            },
            measureBlock = {
                val measuredContainer = requireObject("history-events-list")
                scrollDown(measuredContainer)
            },
        )
    }

    private fun MacrobenchmarkScope.startAutomationActivity(
        startRoute: String,
        dataPreset: String,
    ) {
        startActivityAndWait { intent ->
            intent.putExtra(AutomationEnabledExtra, true)
            intent.putExtra(AutomationResetStateExtra, true)
            intent.putExtra(AutomationStartRouteExtra, startRoute)
            intent.putExtra(AutomationDisableMotionExtra, true)
            intent.putExtra(AutomationPermissionPresetExtra, "granted")
            intent.putExtra(AutomationServicePresetExtra, "connected_vpn")
            intent.putExtra(AutomationDataPresetExtra, dataPreset)
        }
    }

    private fun MacrobenchmarkScope.requireObject(resourceName: String): UiObject2 =
        checkNotNull(device.wait(Until.findObject(By.res(resourceName)), NavigationTimeoutMs)) {
            "Frame timing journey could not find $resourceName"
        }

    private fun MacrobenchmarkScope.scrollDown(container: UiObject2) {
        val bounds = container.visibleBounds
        val gestureX = bounds.right - bounds.width() / GestureHorizontalOffsetDivisor
        device.swipe(
            gestureX,
            bounds.bottom - GestureEdgeInsetPx,
            gestureX,
            bounds.top + GestureEdgeInsetPx,
            GestureSteps,
        )
        device.waitForIdle()
    }

    private companion object {
        const val PackageName = "com.poyka.ripdpi"
        const val ScrollIterations = 10
        const val NavigationTimeoutMs = 5_000L
        const val GestureHorizontalOffsetDivisor = 8
        const val GestureEdgeInsetPx = 32
        const val GestureSteps = 100
        const val AutomationEnabledExtra = "com.poyka.ripdpi.automation.ENABLED"
        const val AutomationResetStateExtra = "com.poyka.ripdpi.automation.RESET_STATE"
        const val AutomationStartRouteExtra = "com.poyka.ripdpi.automation.START_ROUTE"
        const val AutomationDisableMotionExtra = "com.poyka.ripdpi.automation.DISABLE_MOTION"
        const val AutomationPermissionPresetExtra = "com.poyka.ripdpi.automation.PERMISSION_PRESET"
        const val AutomationServicePresetExtra = "com.poyka.ripdpi.automation.SERVICE_PRESET"
        const val AutomationDataPresetExtra = "com.poyka.ripdpi.automation.DATA_PRESET"
    }
}
