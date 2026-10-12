package com.poyka.ripdpi.ui.screens.diagnostics

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsCompressionCodecUiModel
import com.poyka.ripdpi.activities.DiagnosticsCompressionProbeState
import com.poyka.ripdpi.activities.DiagnosticsCompressionProbeToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsIpv4WhitelistState
import com.poyka.ripdpi.activities.DiagnosticsIpv4WhitelistSubnetUiModel
import com.poyka.ripdpi.activities.DiagnosticsIpv4WhitelistToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h839dp-mdpi", application = Application::class)
class DiagnosticsToolChineseCaptionsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `compression byte caption is Chinese and keeps measured counts`() {
        composeRule.setContent {
            RipDpiTheme {
                HttpCompressionProbeCard(
                    tool =
                        DiagnosticsCompressionProbeToolUiModel(
                            state = DiagnosticsCompressionProbeState.Complete,
                            rows =
                                persistentListOf(
                                    DiagnosticsCompressionCodecUiModel(
                                        "gzip",
                                        "正常",
                                        "1024",
                                        "8192",
                                        DiagnosticsTone.Positive,
                                    ),
                                ),
                        ),
                    onRun = {},
                    onZstdEnabledChange = {},
                )
            }
        }
        composeRule.onNodeWithText("压缩后 1024 B · 解码后 8192 B").assertIsDisplayed()
        composeRule.onNodeWithText("1024 B compressed · 8192 B decoded").assertDoesNotExist()
    }

    @Test
    fun `expanded subnet caption is Chinese and keeps sample ratio and verdict`() {
        composeRule.setContent {
            RipDpiTheme {
                Ipv4WhitelistSubnetDiscoveryCard(
                    tool =
                        DiagnosticsIpv4WhitelistToolUiModel(
                            state = DiagnosticsIpv4WhitelistState.Complete,
                            rows =
                                persistentListOf(
                                    DiagnosticsIpv4WhitelistSubnetUiModel(
                                        "Example",
                                        "192.0.2.0/24",
                                        "4/25",
                                        "常规",
                                        DiagnosticsTone.Neutral,
                                    ),
                                ),
                        ),
                    onCache = {},
                    onCheck = {},
                    onSaveCsv = {},
                )
            }
        }
        val showResults =
            RuntimeEnvironment
                .getApplication()
                .getString(R.string.diagnostics_ipv4_whitelist_show_results)
        composeRule.onNodeWithText(showResults).performClick()
        composeRule.onNodeWithText("可达 4/25 · 常规").assertIsDisplayed()
        composeRule.onNodeWithText("alive 4/25 · 常规").assertDoesNotExist()
    }
}
