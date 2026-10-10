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
import com.poyka.ripdpi.activities.toConnectionStageUiModel
import com.poyka.ripdpi.diagnostics.ConnectionLaneKind
import com.poyka.ripdpi.diagnostics.ConnectionStage
import com.poyka.ripdpi.diagnostics.ConnectionStageLane
import com.poyka.ripdpi.diagnostics.ConnectionStageMeasurement
import com.poyka.ripdpi.diagnostics.ConnectionStageScale
import com.poyka.ripdpi.diagnostics.ConnectionStageState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
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
class DiagnosticsConnectionStageComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun scaleStartsCompactAndExpandsWithSeparateTimingLabels() {
        show()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsConnectionStageLanes).assertDoesNotExist()
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_transfer_scope_unverified)))
        capture("connection-stages-collapsed-unverified")
        composeRule.onNodeWithText(text(R.string.diagnostics_stages_show, 2)).performScrollTo().performClick()
        verifyExpanded("connection-stages")
        composeRule.onNodeWithText(text(R.string.diagnostics_stages_hide, 2)).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsConnectionStageLanes).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ar-rEG-w411dp-h839dp-mdpi")
    fun expandedHistoryRemainsReadableAt360DpWithLargeRtlText() {
        show(fontScale = 1.5f, direction = LayoutDirection.Rtl, expanded = true)
        verifyExpanded("connection-stages-rtl-large")
    }

    private fun show(
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        expanded: Boolean = false,
    ) {
        composeRule.setContent {
            StageViewport(
                fontScale,
                direction,
            ) { DiagnosticsConnectionStageCard(evidence(), initiallyExpanded = expanded) }
        }
    }

    private fun verifyExpanded(name: String) {
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_stages_hide, 2)))
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_stages_legend)))
        capture("$name-overview")
        assertComplete(
            composeRule.onNodeWithText(
                text(
                    R.string.diagnostics_stages_state,
                    text(R.string.diagnostics_stages_dns),
                    text(R.string.diagnostics_stages_not_applicable),
                ),
            ),
        )
        assertComplete(
            composeRule.onNodeWithText(
                text(
                    R.string.diagnostics_stages_state,
                    text(R.string.diagnostics_stages_tcp),
                    text(R.string.diagnostics_stages_unknown),
                ),
            ),
        )
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_transfer_scope_unverified)))
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_stages_duration, 20)))
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_stages_elapsed, 40)))
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_stages_bytes, 128)))
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_stages_http_status, 403)))
        assertComplete(
            composeRule.onNodeWithText(
                text(
                    R.string.diagnostics_stages_state,
                    text(R.string.diagnostics_stages_body),
                    text(R.string.diagnostics_stages_partial),
                ),
            ),
        )
        capture(name)
        assertComplete(
            composeRule.onNodeWithText(
                text(
                    R.string.diagnostics_stages_attempt,
                    text(R.string.diagnostics_stages_lane_http),
                    2,
                ),
            ),
        )
        assertComplete(
            composeRule.onNodeWithText(
                text(
                    R.string.diagnostics_stages_state,
                    text(R.string.diagnostics_stages_headers),
                    text(R.string.diagnostics_stages_not_reached),
                ),
            ),
        )
        capture("$name-second-attempt")
    }

    @Composable
    private fun StageViewport(
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
                        .ripDpiTestTag("stage-viewport"),
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
                    .onNodeWithTag("stage-viewport")
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

    private fun evidence() =
        ConnectionStageScale(
            listOf(
                ConnectionStageLane(
                    ConnectionLaneKind.TRANSFER,
                    1,
                    listOf(
                        ConnectionStageMeasurement(ConnectionStage.DNS, ConnectionStageState.NOT_APPLICABLE),
                        ConnectionStageMeasurement(ConnectionStage.TCP, ConnectionStageState.UNKNOWN),
                        ConnectionStageMeasurement(
                            ConnectionStage.TLS,
                            ConnectionStageState.SUCCEEDED,
                            durationMs = 20,
                        ),
                        ConnectionStageMeasurement(
                            ConnectionStage.HTTP_HEADERS,
                            ConnectionStageState.OBSERVED,
                            httpStatusCode = 403,
                        ),
                        ConnectionStageMeasurement(
                            ConnectionStage.FIRST_BODY_BYTE,
                            ConnectionStageState.OBSERVED,
                            elapsedMs = 40,
                        ),
                        ConnectionStageMeasurement(ConnectionStage.BODY, ConnectionStageState.PARTIAL, byteCount = 128),
                    ),
                    networkScopeUnverified = true,
                ),
                ConnectionStageLane(
                    ConnectionLaneKind.HTTP,
                    2,
                    listOf(
                        ConnectionStageMeasurement(ConnectionStage.TCP, ConnectionStageState.TIMED_OUT),
                        ConnectionStageMeasurement(ConnectionStage.HTTP_HEADERS, ConnectionStageState.NOT_REACHED),
                    ),
                ),
            ),
        ).toConnectionStageUiModel()
}
