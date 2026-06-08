package com.poyka.ripdpi.ui.screens.detection

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
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
    fun detectionCheckScreenShowsConditionalStealthMetric() {
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
        composeRule.onNodeWithText("You look like ordinary HTTPS traffic").fetchSemanticsNode()
        val cleanReason =
            "Reason: Detection probes did not expose bypass, VPN, IP, DNS, TLS, " +
                "timing, or native observability signals."
        val cleanHint = "What to adjust: Keep the current profile and rerun after changing networks or profiles."
        composeRule
            .onNodeWithText(cleanReason)
            .fetchSemanticsNode()
        composeRule
            .onNodeWithText(cleanHint)
            .fetchSemanticsNode()
        composeRule.onNodeWithText("Where masking applied, stealth").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Stealth Score").assertCountEquals(0)
    }

    @Test
    fun detectionCheckScreenShowsDetectionOutcomeBeforeStealthScore() {
        val result = detectedResult()
        composeRule.setContent {
            RipDpiTheme {
                DetectionCheckScreen(
                    uiState =
                        DetectionCheckUiState(
                            result = result,
                            stealthScore = 77,
                            stealthLabel = "Good",
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

        composeRule.onNodeWithText("Your traffic is distinguishable by Android VPN binding").fetchSemanticsNode()
        val vpnReason = "Reason: Android network capabilities expose VPN routing to local observers."
        val vpnHint = "What to adjust: Review VPN mode, per-app routing, and active VPN ownership before retesting."
        composeRule
            .onNodeWithText(vpnReason)
            .fetchSemanticsNode()
        composeRule
            .onNodeWithText(vpnHint)
            .fetchSemanticsNode()
        composeRule.onNodeWithText("77/100").fetchSemanticsNode()
        composeRule.onAllNodesWithText("100% detected").assertCountEquals(0)
        composeRule.onAllNodesWithText("Detected by 5/5 probes").assertCountEquals(0)
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

    private fun detectedResult(): DetectionCheckResult =
        DetectionCheckResult(
            geoIp = emptyCategory("GeoIP"),
            directSigns = detectedCategory("Direct"),
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
            verdict = Verdict.DETECTED,
        )

    private fun emptyCategory(name: String): CategoryResult =
        CategoryResult(
            name = name,
            detected = false,
            findings = emptyList(),
        )

    private fun detectedCategory(name: String): CategoryResult =
        CategoryResult(
            name = name,
            detected = true,
            findings = emptyList(),
            evidence =
                if (name == "Direct") {
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.NETWORK_CAPABILITIES,
                            detected = true,
                            confidence = EvidenceConfidence.HIGH,
                            description = "TRANSPORT_VPN",
                        ),
                    )
                } else {
                    emptyList()
                },
        )
}
