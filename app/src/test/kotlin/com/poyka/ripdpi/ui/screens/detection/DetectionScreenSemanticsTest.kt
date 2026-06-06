package com.poyka.ripdpi.ui.screens.detection

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.StealthScore
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DetectionScreenSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun detectionCheckScreenExposesRouteTag() {
        composeRule.setContent {
            RipDpiTheme {
                DetectionCheckScreen(
                    uiState = DetectionCheckUiState(),
                    onStart = {},
                    onStop = {},
                    onBack = {},
                    onDismissOnboarding = {},
                    onApplyFixes = {},
                    onPrivacyModeChange = {},
                    onReloadCommunityStats = {},
                    onRequestPermissions = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.DetectionCheck)).fetchSemanticsNode()
    }

    @Test
    fun detectionCheckScreenShowsVisibilityScale() {
        val result = cleanDetectionResult()
        composeRule.setContent {
            RipDpiTheme {
                DetectionCheckScreen(
                    uiState =
                        DetectionCheckUiState(
                            result = result,
                            stealthScore = StealthScore.compute(result),
                            stealthLabel = "Low visibility",
                        ),
                    onStart = {},
                    onStop = {},
                    onBack = {},
                    onDismissOnboarding = {},
                    onApplyFixes = {},
                    onPrivacyModeChange = {},
                    onReloadCommunityStats = {},
                    onRequestPermissions = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DetectionVisibilityScale, useUnmergedTree = true).fetchSemanticsNode()
        composeRule.onNodeWithText("Visibility").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Stealth Score").assertCountEquals(0)
    }

    @Test
    fun detectionSettingsScreenExposesRouteTag() {
        composeRule.setContent {
            RipDpiTheme {
                DetectionSettingsScreen(
                    state = DetectionSettingsUiState(),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.DetectionSettings)).fetchSemanticsNode()
    }

    private fun cleanDetectionResult(): DetectionCheckResult =
        DetectionCheckResult(
            geoIp = emptyCategory("GeoIP"),
            directSigns = emptyCategory("Direct"),
            indirectSigns = emptyCategory("Indirect"),
            locationSignals = emptyCategory("Location"),
            bypassResult =
                BypassResult(
                    proxyEndpoint = null,
                    directIp = null,
                    proxyIp = null,
                    xrayApiScanResult = null,
                    findings = emptyList(),
                    detected = false,
                ),
            verdict = Verdict.NOT_DETECTED,
        )

    private fun emptyCategory(name: String): CategoryResult =
        CategoryResult(
            name = name,
            detected = false,
            findings = emptyList(),
        )
}
