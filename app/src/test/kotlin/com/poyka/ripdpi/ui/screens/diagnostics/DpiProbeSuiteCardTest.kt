package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.activities.DiagnosticsDpiSuiteProbeDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsDpiSuiteProbeRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsDpiSuiteToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DpiProbeSuiteCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun titleNamesDpiChComprehensiveSurface() {
        composeRule.setContent {
            RipDpiTheme {
                DpiProbeSuiteCard(
                    tool = DiagnosticsDpiSuiteToolUiModel(),
                    onProbeEnabledChange = { _, _ -> },
                    onCustomDomainsChange = {},
                    onConcurrencyDelta = {},
                    onRun = {},
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText("DPI-CH Comprehensive").assertIsDisplayed()
    }

    @Test
    fun quicMatrixRowsAreDisplayed() {
        composeRule.setContent {
            RipDpiTheme {
                DpiProbeSuiteCard(
                    tool =
                        DiagnosticsDpiSuiteToolUiModel(
                            rows =
                                persistentListOf(
                                    DiagnosticsDpiSuiteProbeRowUiModel(
                                        kind = DpiProbeKind.QUIC_H3,
                                        label = "QUIC/H3 fingerprint",
                                        status = "flagged",
                                        detail = "0/1 targets passed QUIC/H3 fingerprint checks",
                                        tone = DiagnosticsTone.Warning,
                                        detailRows =
                                            persistentListOf(
                                                DiagnosticsDpiSuiteProbeDetailUiModel(
                                                    label = "cloudflare.com",
                                                    detail = "Chrome blocked | Firefox ok | Generic ok | VN ok | 42 ms",
                                                    tone = DiagnosticsTone.Warning,
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    onProbeEnabledChange = { _, _ -> },
                    onCustomDomainsChange = {},
                    onConcurrencyDelta = {},
                    onRun = {},
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText("cloudflare.com").assertIsDisplayed()
        composeRule.onNodeWithText("Chrome blocked | Firefox ok | Generic ok | VN ok | 42 ms").assertIsDisplayed()
    }
}
