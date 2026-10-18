package com.poyka.ripdpi.activities

import android.app.Application
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeProgress
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.platform.AndroidStringResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", application = Application::class)
class HomePersianRetainedProgressTest {
    private val locale = RetainedLocaleTestSupport()

    @After
    fun close() {
        locale.close()
    }

    @Test
    fun `retained resolver refreshes full and quick live stages and preserves candidate names`() {
        val strings = AndroidStringResolver(locale.englishContext())
        val stages =
            listOf(
                "automatic_audit" to "Automatic audit",
                "detection_signals" to "Detection signals",
                "default_connectivity" to "Default diagnostics",
                "ru_throttling" to "Throttling check",
                "ru_circumvention" to "Sensitive services reachability",
                "dpi_full" to "DPI detector full",
                "path_comparison" to "Proxy vs direct path",
                "vpn_route_evidence" to "VPN route evidence",
                "dpi_strategy" to "DPI strategy probe",
            )
        listOf(false, true).forEach { quick ->
            stages.forEach { (key, name) ->
                val runtime = runtime(key, name, quick)
                val english = project(runtime, strings)
                locale.select("fa")
                val persian = project(runtime, strings).analysisAction.supportingText
                assertTrue("Expected Persian live stage $key: $persian", persian.any { it in '\u0600'..'\u06ff' })
                assertFalse(persian.contains(name))
                assertEquals(
                    name,
                    runtime.activeRunProgress!!
                        .stages
                        .single()
                        .stageLabel,
                )
                locale.select("en")
                assertEquals(english, project(runtime, strings))
            }
        }
        val candidate =
            runtime("automatic_audit", "Automatic audit", true).copy(
                activeRunStageProgress = "Testing TCP candidate Customer TCP",
            )
        locale.select("fa")
        val persian = project(candidate, strings).analysisAction.supportingText
        assertTrue(persian.contains("Customer TCP"))
        assertFalse(persian.contains("Testing TCP candidate"))
        locale.select("en")
        assertTrue(
            project(candidate, strings).analysisAction.supportingText.contains("Testing TCP candidate Customer TCP"),
        )
    }

    @Test
    fun `localized producer epsilon reprojects through retained locales without changing raw value`() {
        val originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("fa"))
            val number = "%.2f".format(0.20f)
            assertEquals("۰٫۲۰", number)
            val setting = DiagnosticsAppliedSetting("Strategy evolution", "Epsilon $number")
            val raw = homeOutcome().copy(appliedSettings = listOf(setting))
            val runtime =
                HomeDiagnosticsRuntimeState(
                    latestCompositeOutcome = raw,
                    currentFingerprintHash = raw.fingerprintHash,
                    analysisSheetVisible = true,
                )
            val strings = AndroidStringResolver(locale.englishContext())
            val english = project(runtime, strings)
            locale.select("fa")
            val persian = project(runtime, strings).analysisSheet!!.appliedSettings.single()
            assertTrue(
                "Expected Persian applied-setting label: ${persian.label}",
                persian.label.any {
                    it in
                        '\u0600'..'\u06ff'
                },
            )
            assertEquals("اپسیلون $number", persian.value)
            assertEquals(setting, raw.appliedSettings.single())
            locale.select("en")
            assertEquals(english, project(runtime, strings))
            assertEquals(
                "Epsilon $number",
                english.analysisSheet!!
                    .appliedSettings
                    .single()
                    .value,
            )
            val custom = setting.copy(value = "Epsilon $number extra")
            assertEquals(custom.value, strings.homeAppliedSetting(custom).value)
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale)
        }
    }

    private fun project(
        runtime: HomeDiagnosticsRuntimeState,
        strings: AndroidStringResolver,
    ) = buildHomeDiagnosticsUiState(
        settings = AppSettingsSerializer.defaultValue,
        appStatus = AppStatus.Halted,
        connectionState = ConnectionState.Disconnected,
        runtime = runtime,
        stringResolver = strings,
    )

    private fun runtime(
        key: String,
        name: String,
        quick: Boolean,
    ) = HomeDiagnosticsRuntimeState(
        activeRunId = "frozen-run",
        quickScanActive = quick,
        activeRunProgress =
            DiagnosticsHomeCompositeProgress(
                runId = "frozen-run",
                activeStageIndex = 0,
                stages =
                    listOf(
                        DiagnosticsHomeCompositeStageSummary(
                            stageKey = key,
                            stageLabel = name,
                            profileId = key,
                            pathMode = ScanPathMode.RAW_PATH,
                            status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                            headline = "unused",
                            summary = "Waiting to run.",
                        ),
                    ),
            ),
    )
}
