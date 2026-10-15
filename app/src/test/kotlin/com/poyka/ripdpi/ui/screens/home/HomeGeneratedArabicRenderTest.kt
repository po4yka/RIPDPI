package com.poyka.ripdpi.ui.screens.home

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.activities.HomeDiagnosticsAnalysisSheetUiState
import com.poyka.ripdpi.activities.homeOutcome
import com.poyka.ripdpi.activities.projectHome
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.diagnostics.DiagnosticsCapabilityEvidence
import com.poyka.ripdpi.diagnostics.HomeBufferbloatGrade
import com.poyka.ripdpi.diagnostics.HomeBufferbloatResult
import com.poyka.ripdpi.diagnostics.HomeDnsCharacterization
import com.poyka.ripdpi.diagnostics.HomeDnsResolverClass
import com.poyka.ripdpi.diagnostics.HomeNetworkCharacterSummary
import com.poyka.ripdpi.diagnostics.HomeRoutingSanityFinding
import com.poyka.ripdpi.diagnostics.HomeRoutingSanitySummary
import com.poyka.ripdpi.ui.localization.assertArabicTextLayout
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
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
@Config(sdk = [35], qualifiers = "ar-rEG-w420dp-h900dp-mdpi")
class HomeGeneratedArabicRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var expectedFontScale = 1f

    @Test
    fun normalArabicAnalysis() = renderGeneratedAnalysis("normal", 1f)

    @Test
    @Config(qualifiers = "ar-rEG-w360dp-h900dp-mdpi")
    fun narrowArabicAnalysisAtLargeFont() = renderGeneratedAnalysis("narrow-large-font", 1.5f)

    private fun renderGeneratedAnalysis(
        name: String,
        fontScale: Float,
    ) {
        expectedFontScale = fontScale
        // Dialog windows obtain density from Android configuration, outside the caller's CompositionLocal.
        RuntimeEnvironment.setFontScale(fontScale)
        val outcome = frozenArabicHomeOutcome()
        val projected = projectHome(outcome, "ar")
        val sheet = requireNotNull(projected.analysisSheet)
        val state = mutableStateOf(projected)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                RipDpiTheme(themePreference = "light") {
                    HomeDiagnosticsBottomSheetHost(
                        homeDiagnostics = state.value,
                        onOpenDiagnostics = {},
                        onOpenHistory = {},
                        onOpenAdvancedSettings = {},
                        onOpenModeEditor = {},
                        onOpenOwnedStackBrowser = {},
                        onShareAnalysis = {},
                        onDismissAnalysisSheet = {},
                        onDismissVerificationSheet = {},
                    )
                }
            }
        }
        inspectGeneratedSections(sheet, name)

        val unavailable =
            projectHome(
                outcome.copy(
                    fingerprintHash = null,
                    actionable = false,
                    headline = "Network scope unavailable",
                    summary =
                        "Network changed during analysis or its original scope could not be verified. " +
                            "Repeat analysis on a stable network.",
                    capabilityEvidence = emptyList(),
                    networkCharacter = null,
                    routingSanity = null,
                    bufferbloat = null,
                    dnsCharacterization = null,
                    actionableHeadline = null,
                    actionableNextSteps = emptyList(),
                ),
                "ar",
            )
        composeRule.runOnIdle { state.value = unavailable }
        val unavailableSheet = requireNotNull(unavailable.analysisSheet)
        assertVisibleText(unavailableSheet.headline)
        assertVisibleText(unavailableSheet.summary)
        capture("$name-scope")
    }

    private fun inspectGeneratedSections(
        sheet: HomeDiagnosticsAnalysisSheetUiState,
        name: String,
    ) {
        val headline = requireNotNull(sheet.actionableHeadline)
        assertVisibleText(headline)
        capture("$name-actionable")
        sheet.actionableNextSteps.forEach { assertVisibleText("• $it") }
        val capability = sheet.capabilityEvidence.single()
        assertVisibleText(capability.summary)
        capability.fields.forEach {
            assertVisibleText(it.label)
            assertVisibleText(it.value)
        }
        capture("$name-capability")
        sheet.networkCharacterRows.forEach { assertVisibleText(it.value) }
        sheet.networkCharacterNotes.forEach { assertVisibleText("• $it") }
        assertVisibleText(requireNotNull(sheet.bufferbloatSummary))
        sheet.dnsCharacterizationNotes.forEach { assertVisibleText("• $it") }
        capture("$name-network-dns")
        sheet.routingSanityFindings.forEach { assertVisibleText(it.value) }
        capture("$name-routing")
    }

    private fun assertVisibleText(text: String) {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true)
        node.performScrollTo().assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Missing text layout: $text", results.isNotEmpty())
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport =
            composeRule
                .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsAnalysisSheet, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        results.forEach { result ->
            assertEquals(
                "Actual modal text font scale for $text",
                expectedFontScale,
                result.layoutInput.density.fontScale,
            )
            assertArabicTextLayout(result, bounds, viewport, "Arabic Home text: $text")
        }
    }

    private fun capture(name: String) {
        val output = File("build/reports/localization-ar/home-$name.png").apply { parentFile.mkdirs() }
        output.outputStream().use {
            composeRule
                .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsAnalysisSheet, useUnmergedTree = true)
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

private fun frozenArabicHomeOutcome() =
    homeOutcome().copy(
        actionableHeadline = "Analysis complete and bypass applied.",
        actionableNextSteps = listOf("Encrypted DNS endpoint unreachable — check connectivity and retry"),
        networkCharacter =
            HomeNetworkCharacterSummary(
                transport = "cellular",
                operatorOrSsid = "Carrier 25001",
                captivePortalDetected = true,
                ipv6Reachable = false,
                notes = listOf("Active network is a VPN", "Network reports as metered"),
            ),
        capabilityEvidence =
            listOf(
                DiagnosticsCapabilityEvidence(
                    authority = "example.org:443",
                    summary = "QUIC blocked · Auth accepted · Fallback required",
                    details =
                        listOf(
                            DiagnosticsAppliedSetting("QUIC", "Blocked"),
                            DiagnosticsAppliedSetting("Auth", "Available"),
                            DiagnosticsAppliedSetting("Fallback", "Required"),
                        ),
                    source = "diagnostics",
                ),
            ),
        routingSanity =
            HomeRoutingSanitySummary(
                1,
                1,
                listOf(
                    HomeRoutingSanityFinding(
                        packageName = "org.example.app",
                        severity = "high",
                        description = "VPN-detector app — verify per-app routing override (detection: transport_vpn)",
                    ),
                ),
            ),
        bufferbloat = HomeBufferbloatResult(HomeBufferbloatGrade.UNKNOWN),
        dnsCharacterization =
            HomeDnsCharacterization(
                HomeDnsResolverClass.DOH_UNREACHABLE,
                dohEndpoint = "https://1.1.1.1/dns-query",
                notes =
                    listOf(
                        "DoH endpoint https://1.1.1.1/dns-query unreachable",
                        "DNS comparison is incomplete",
                        "System and DoH answers differ; location and CDN routing can cause this",
                    ),
            ),
    )
