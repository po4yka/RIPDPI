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
import com.poyka.ripdpi.activities.DiagnosticsAllowlistSniAsnUiModel
import com.poyka.ripdpi.activities.DiagnosticsAllowlistSniState
import com.poyka.ripdpi.activities.DiagnosticsAllowlistSniToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsByohCompatibilityState
import com.poyka.ripdpi.activities.DiagnosticsByohCompatibilityToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsByohDomainUiModel
import com.poyka.ripdpi.activities.DiagnosticsCompatibleSniUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
@Config(sdk = [35], qualifiers = "es-w411dp-h1400dp-mdpi", application = Application::class)
class DiagnosticsToolSpanishCaptionsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `SNI rows localize zero one and two compatible entries and preserve raw evidence`() {
        sniRows(fontScale = 1f)
    }

    @Test
    fun `SNI rows remain visible with large Spanish text`() {
        sniRows(fontScale = 1.5f)
    }

    @Test
    fun `BYOH byte caption uses Spanish and preserves the measured count`() {
        byohCaption(fontScale = 1f)
    }

    @Test
    fun `BYOH byte caption remains visible with large Spanish text`() {
        byohCaption(fontScale = 1.5f)
    }

    private fun sniRows(fontScale: Float) {
        val tool = sniTool()
        composeRule.setContent {
            val density = Density(LocalDensity.current.density, fontScale)
            CompositionLocalProvider(LocalDensity provides density) {
                RipDpiTheme {
                    Column(Modifier.fillMaxWidth()) {
                        AllowlistSniFinderCard(tool = tool, onRun = {})
                    }
                }
            }
        }
        composeRule.onNodeWithText("AS64500: 0 compatibles").assertIsDisplayed()
        composeRule.onNodeWithText("AS64501: 1 compatible").assertIsDisplayed()
        composeRule.onNodeWithText("AS64502: 2 compatibles").assertIsDisplayed()
        (0..2).forEach { count ->
            composeRule.onNodeWithText("Provider_$count · 192.0.2.${count + 1} · probados: 9").assertIsDisplayed()
        }
        composeRule.onNodeWithText("AS64502: 2 compatible").assertDoesNotExist()
        capture("spanish-sni-$fontScale.png")
    }

    private fun byohCaption(fontScale: Float) {
        val row = DiagnosticsByohDomainUiModel("MiHost.test", "Cuerpo incompleto", "1234", DiagnosticsTone.Warning)
        val tool =
            DiagnosticsByohCompatibilityToolUiModel(
                state = DiagnosticsByohCompatibilityState.Complete,
                dstIp = "192.0.2.1",
                rows = listOf(row).toPersistentList(),
            )
        composeRule.setContent {
            val density = Density(LocalDensity.current.density, fontScale)
            CompositionLocalProvider(LocalDensity provides density) {
                RipDpiTheme {
                    ByohCompatibilityCard(
                        tool = tool,
                        onDstIpChange = {},
                        onUrlPathChange = {},
                        onSyntheticFixtureEnabledChange = {},
                        onRun = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("1234 B recibidos").assertIsDisplayed()
        composeRule.onNodeWithText("MiHost.test: Cuerpo incompleto").assertIsDisplayed()
        composeRule.onNodeWithText("1234 B received").assertDoesNotExist()
        capture("spanish-byoh-$fontScale.png")
    }

    private fun sniTool(): DiagnosticsAllowlistSniToolUiModel {
        val rows =
            (0..2).map { count ->
                val snis =
                    (1..count).map { index ->
                        DiagnosticsCompatibleSniUiModel("MiHost$index.test [$index]", "MiHost$index.test")
                    }
                DiagnosticsAllowlistSniAsnUiModel(
                    asn = "AS6450$count",
                    provider = "Provider_$count",
                    ip = "192.0.2.${count + 1}",
                    triedCount = "9",
                    compatibleSnis = snis.toPersistentList(),
                    tone = if (count == 0) DiagnosticsTone.Warning else DiagnosticsTone.Positive,
                )
            }
        return DiagnosticsAllowlistSniToolUiModel(
            state = DiagnosticsAllowlistSniState.Complete,
            enabled = true,
            rows = rows.toPersistentList(),
        )
    }

    private fun capture(name: String) {
        val output = File("build/compose-previews/renders/$name")
        output.parentFile?.mkdirs()
        output.outputStream().use { stream ->
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
