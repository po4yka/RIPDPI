package com.poyka.ripdpi.ui.screens.diagnostics

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsTcp16AsnUiModel
import com.poyka.ripdpi.activities.DiagnosticsTcp16FatHeaderState
import com.poyka.ripdpi.activities.DiagnosticsTcp16FatHeaderToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DpiFailureClass
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w411dp-h1400dp-mdpi", application = Application::class)
class DiagnosticsToolHindiCaptionsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `TCP16 rows use Hindi counter labels and retain zero one and two counts`() {
        tcp16Rows(hindi = true, fontScale = 1f)
    }

    @Test
    fun `TCP16 Hindi rows remain visible with large font`() {
        tcp16Rows(hindi = true, fontScale = 1.5f)
    }

    @Test
    @Config(qualifiers = "en-w411dp-h1400dp-mdpi")
    fun `TCP16 English row captions remain stable`() {
        tcp16Rows(hindi = false, fontScale = 1f)
    }

    @Test
    fun `default PT model displays localized idle guidance before controller emissions`() {
        ptIdle(hindi = true)
    }

    @Test
    @Config(qualifiers = "en-w411dp-h1400dp-mdpi")
    fun `default PT model retains English idle guidance`() {
        ptIdle(hindi = false)
    }

    @Test
    fun `scan progress presents the Hindi DPI failure label`() {
        composeRule.setContent {
            RipDpiTheme {
                ScanProgressCard(
                    progress =
                        DiagnosticsProgressUiModel(
                            phase = "tls",
                            summary = "",
                            completedSteps = 1,
                            totalSteps = 2,
                            fraction = 0.5f,
                            scanKind = ScanKind.CONNECTIVITY,
                            isFullAudit = false,
                            scanStartedAtMs = 0L,
                            phaseSteps = persistentListOf(),
                            currentProbeLabel = "Exact_Host.test",
                            dpiFailureClass = DpiFailureClass.TLS_HANDSHAKE_FAILURE,
                        ),
                    strategyProbeSelected = false,
                )
            }
        }
        composeRule.onNodeWithText("DPI: TLS हैंडशेक विफल").assertIsDisplayed()
        composeRule.onNodeWithText("DPI: TLS Handshake Failure").assertDoesNotExist()
        composeRule.onNodeWithText("Exact_Host.test").assertIsDisplayed()
        capture("hindi-dpi-failure.png")
    }

    private fun tcp16Rows(
        hindi: Boolean,
        fontScale: Float,
    ) {
        val tool =
            DiagnosticsTcp16FatHeaderToolUiModel(
                state = DiagnosticsTcp16FatHeaderState.Complete,
                rows =
                    (0..2)
                        .map { count ->
                            DiagnosticsTcp16AsnUiModel(
                                asn = "AS6450$count",
                                providers = "Raw_Provider_$count",
                                checked = "3",
                                detected = count.toString(),
                                dead = count.toString(),
                                errors = count.toString(),
                                tone = if (count == 0) DiagnosticsTone.Neutral else DiagnosticsTone.Warning,
                            )
                        }.toPersistentList(),
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                RipDpiTheme {
                    Column(Modifier.fillMaxWidth()) {
                        Tcp16FatHeaderProbeCard(tool = tool, onRun = {})
                    }
                }
            }
        }
        (0..2).forEach { count ->
            val label =
                if (hindi) "AS6450$count: $count/3 में पता चला" else "AS6450$count: $count/3 detected"
            val detail =
                if (hindi) {
                    "Raw_Provider_$count · अनुपलब्ध $count · त्रुटियाँ $count"
                } else {
                    "Raw_Provider_$count · dead $count · errors $count"
                }
            composeRule.onNodeWithText(label).assertIsDisplayed()
            composeRule.onNodeWithText(detail).assertIsDisplayed()
        }
        if (hindi) composeRule.onNodeWithText("AS64500: 0/3 detected").assertDoesNotExist()
        capture("${if (hindi) "hindi" else "english"}-tcp16-$fontScale.png")
    }

    private fun ptIdle(hindi: Boolean) {
        composeRule.setContent {
            RipDpiTheme {
                PluggableTransportProbeCard(tool = DiagnosticsPluggableTransportToolUiModel(), onRun = {})
            }
        }
        val guidance =
            if (hindi) {
                "obfs4, Snowflake और meek के सार्वजनिक PT एंडपॉइंट तक पहुँच जाँचें।"
            } else {
                "Check public PT endpoint reachability for obfs4, Snowflake, and meek."
            }
        composeRule.onNodeWithText(guidance).assertIsDisplayed()
        capture("${if (hindi) "hindi" else "english"}-pt-idle.png")
    }

    private fun capture(name: String) {
        val output = File("build/compose-previews/renders/$name")
        output.parentFile.mkdirs()
        output.outputStream().use { stream ->
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
