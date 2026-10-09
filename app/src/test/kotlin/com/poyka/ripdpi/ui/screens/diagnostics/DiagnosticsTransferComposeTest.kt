package com.poyka.ripdpi.ui.screens.diagnostics

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsTransferRunUiModel
import com.poyka.ripdpi.activities.DiagnosticsTransferSampleUiModel
import com.poyka.ripdpi.activities.DiagnosticsTransferUiModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h839dp-mdpi")
class DiagnosticsTransferComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun liveCardUpdatesBytesWithoutInventingAnExpectedLength() {
        val model =
            mutableStateOf(
                evidence(
                    run().copy(
                        expectedBodyByteCount = null,
                        terminationReason = null,
                        windowComplete = false,
                    ),
                ),
            )
        composeRule.setContent { TransferViewport { DiagnosticsTransferCard(model.value) } }
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_transfer_bytes_unknown, 128)))
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_transfer_running)))
        composeRule.runOnIdle {
            model.value =
                evidence(
                    model.value.runs
                        .single()
                        .copy(receivedBodyByteCount = 192, elapsedMs = 40),
                )
        }
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_transfer_bytes_unknown, 192)))
        composeRule.onNodeWithText(text(R.string.diagnostics_transfer_complete)).assertDoesNotExist()
        capture("transfer-live")
    }

    @Test
    fun finalEvidenceDistinguishesTheOwnLimitAndRetainsTimeline() {
        showFinal()
        assertFinal("transfer-final")
    }

    @Test
    @Config(qualifiers = "ar-rEG-w411dp-h839dp-mdpi")
    fun finalEvidenceRemainsReadableWithLargeRtlText() {
        showFinal(fontScale = 1.5f, direction = LayoutDirection.Rtl)
        assertFinal("transfer-final-rtl-large")
    }

    private fun showFinal(
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            TransferViewport(fontScale, direction) { DiagnosticsTransferCard(evidence(run())) }
        }
    }

    private fun assertFinal(name: String) {
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_transfer_scope_unverified)))
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_transfer_bytes_known, 128, 256)))
        assertComplete(
            composeRule.onNodeWithText(
                text(R.string.diagnostics_transfer_first, text(R.string.diagnostics_transfer_ms, 10)),
            ),
        )
        assertComplete(
            composeRule.onNodeWithText(
                text(R.string.diagnostics_transfer_last, text(R.string.diagnostics_transfer_ms, 20)),
            ),
        )
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_transfer_stop_window)))
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_transfer_partial)))
        composeRule.onNodeWithText(text(R.string.diagnostics_transfer_complete)).assertDoesNotExist()
        capture(name)
        composeRule.onNodeWithText(text(R.string.diagnostics_transfer_show_timeline)).performScrollTo().performClick()
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_transfer_timeline_explanation)))
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_transfer_sample, 20, 128)))
        capture("$name-timeline")
        composeRule.onNodeWithText(text(R.string.diagnostics_transfer_hide_timeline)).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsTransferTimeline).assertDoesNotExist()
    }

    @Composable
    private fun TransferViewport(
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(1f, fontScale),
            LocalLayoutDirection provides direction,
        ) {
            RipDpiTheme(themePreference = "light") {
                Box(
                    Modifier
                        .requiredSize(360.dp, 760.dp)
                        .background(RipDpiThemeTokens.colors.background)
                        .ripDpiTestTag("transfer-viewport"),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                }
            }
        }
    }

    private fun assertComplete(node: SemanticsNodeInteraction) {
        node.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout", layouts.isNotEmpty())
        layouts.forEach { assertFalse("Clipped text: ${it.layoutInput.text}", it.hasVisualOverflow) }
    }

    private fun capture(name: String) {
        val file = File("build/compose-previews/renders/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            assertTrue(
                composeRule
                    .onNodeWithTag("transfer-viewport")
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun evidence(run: DiagnosticsTransferRunUiModel) =
        DiagnosticsTransferUiModel(
            target = "example.org",
            runs = persistentListOf(run),
            networkScopeUnverified = true,
        )

    private fun run() =
        DiagnosticsTransferRunUiModel(
            runIndex = 1,
            runCount = 2,
            receivedBodyByteCount = 128,
            expectedBodyByteCount = 256,
            elapsedMs = 30,
            firstBodyByteMs = 10,
            lastBodyProgressMs = 20,
            terminationReason = "window_limit",
            responseComplete = false,
            windowComplete = true,
            samples =
                persistentListOf(
                    DiagnosticsTransferSampleUiModel(10, 64),
                    DiagnosticsTransferSampleUiModel(20, 128),
                ),
        )
}
