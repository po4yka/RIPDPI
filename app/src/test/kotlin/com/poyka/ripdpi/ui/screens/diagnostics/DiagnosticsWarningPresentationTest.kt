package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsLiveUiModel
import com.poyka.ripdpi.activities.DiagnosticsOverviewUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w320dp-h760dp-mdpi")
class DiagnosticsWarningPresentationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun guidedWatchlistExplainsUnavailableDnsComparisonAndPreservesTarget() {
        val warning = dnsWarning()
        renderOverview(warning, expertMode = false)
        assertVisibleWarning("DNS integrity", "example.org: Encrypted comparison unavailable")
        composeRule.onNodeWithText("Dns_integrity").assertDoesNotExist()
        composeRule.onNodeWithText("example.org: dns_oracle_unavailable").assertDoesNotExist()
        assertEquals("example.org: dns_oracle_unavailable", warning.message)
    }

    @Test
    @Config(qualifiers = "ru-w320dp-h760dp-mdpi")
    fun guidedWatchlistUsesLocalizedDnsLabels() {
        renderOverview(dnsWarning(), expertMode = false)
        assertVisibleWarning("Целостность DNS", "example.org: Зашифрованное сравнение недоступно")
        composeRule.onNodeWithText("Dns_integrity").assertDoesNotExist()
        composeRule.onNodeWithText("example.org: dns_oracle_unavailable").assertDoesNotExist()
    }

    @Test
    fun advancedWatchlistRetainsOriginalTechnicalEvidence() {
        renderOverview(dnsWarning(), expertMode = true)
        assertVisibleWarning("Dns_integrity", "example.org: dns_oracle_unavailable")
        composeRule.onNodeWithText("example.org: Encrypted comparison unavailable").assertDoesNotExist()
    }

    @Test
    fun guidedWatchlistKeepsHumanWrittenMessages() {
        renderOverview(dnsWarning().copy(message = "Comparison cancelled by the user."), expertMode = false)
        assertVisibleWarning("DNS integrity", "Comparison cancelled by the user.")
    }

    @Test
    fun guidedWatchlistDoesNotRewriteOtherEventSources() {
        renderOverview(dnsWarning().copy(source = "network", message = "Wi-Fi disconnected."), expertMode = false)
        assertVisibleWarning("network", "Wi-Fi disconnected.")
    }

    private fun renderOverview(
        warning: DiagnosticsEventUiModel,
        expertMode: Boolean,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                OverviewSection(
                    overview = DiagnosticsOverviewUiModel(warnings = persistentListOf(warning)),
                    scan = DiagnosticsScanUiModel(),
                    live = DiagnosticsLiveUiModel(),
                    isActiveScan = false,
                    onSelectSection = {},
                    onRunScan = {},
                    onReviewRecommendedPath = {},
                    onSelectSession = {},
                    onOpenHistory = {},
                    expertMode = expertMode,
                )
            }
        }
    }

    private fun assertVisibleWarning(
        title: String,
        message: String,
    ) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(message))
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }

    private fun dnsWarning() =
        DiagnosticsEventUiModel(
            id = "dns-warning",
            source = "Dns_integrity",
            severity = "WARN",
            message = "example.org: dns_oracle_unavailable",
            createdAtLabel = "12:00",
            tone = DiagnosticsTone.Warning,
        )
}
