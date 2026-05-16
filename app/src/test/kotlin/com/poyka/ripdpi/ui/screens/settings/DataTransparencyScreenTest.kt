package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
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
class DataTransparencyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun screenExposesRouteTestTag() {
        setScreen()

        composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.DataTransparency)).fetchSemanticsNode()
    }

    @Test
    fun whatWeCollectSectionHeaderIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("What we collect"))
        composeRule.onNode(hasText("What we collect")).assertIsDisplayed()
    }

    @Test
    fun whatWeDoNotCollectSectionHeaderIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("What we do NOT collect"))
        composeRule.onNode(hasText("What we do NOT collect")).assertIsDisplayed()
    }

    @Test
    fun doNotCollectNoBrowsingBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("No browsing history or URL content"))
        composeRule.onNode(hasText("No browsing history or URL content")).assertIsDisplayed()
    }

    @Test
    fun doNotCollectNoPersonalDataBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("No personal data, accounts, or credentials"))
        composeRule.onNode(hasText("No personal data, accounts, or credentials")).assertIsDisplayed()
    }

    @Test
    fun doNotCollectNoExternalServersBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("No data is sent to external servers automatically"))
        composeRule.onNode(hasText("No data is sent to external servers automatically")).assertIsDisplayed()
    }

    @Test
    fun doNotCollectNoAnalyticsBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("No analytics, crash reporting, or advertising SDKs"))
        composeRule.onNode(hasText("No analytics, crash reporting, or advertising SDKs")).assertIsDisplayed()
    }

    @Test
    fun howStoredSectionHeaderIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("How data is stored"))
        composeRule.onNode(hasText("How data is stored")).assertIsDisplayed()
    }

    @Test
    fun howStoredLocalDatabaseBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("All data stays on your device in a local database"))
        composeRule.onNode(hasText("All data stays on your device in a local database")).assertIsDisplayed()
    }

    @Test
    fun howStoredRetentionPeriodBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Configurable retention period (1–365 days)"))
        composeRule.onNode(hasText("Configurable retention period (1–365 days)")).assertIsDisplayed()
    }

    @Test
    fun howStoredDisableMonitoringBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("You can disable passive monitoring in Advanced Settings"))
        composeRule.onNode(hasText("You can disable passive monitoring in Advanced Settings")).assertIsDisplayed()
    }

    @Test
    fun howStoredExportExplicitBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Export archives are only created when you explicitly request them"))
        composeRule
            .onNode(hasText("Export archives are only created when you explicitly request them"))
            .assertIsDisplayed()
    }

    @Test
    fun exportPrivacySectionHeaderIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Export privacy"))
        composeRule.onNode(hasText("Export privacy")).assertIsDisplayed()
    }

    @Test
    fun exportPrivacyExportRedactionBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Exported archives redact IP addresses, WiFi identifiers, and other PII"))
        composeRule
            .onNode(hasText("Exported archives redact IP addresses, WiFi identifiers, and other PII"))
            .assertIsDisplayed()
    }

    @Test
    fun exportPrivacyExportControlBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("You control what is shared and with whom"))
        composeRule.onNode(hasText("You control what is shared and with whom")).assertIsDisplayed()
    }

    private fun setScreen(onBack: () -> Unit = {}) {
        composeRule.setContent {
            RipDpiTheme {
                DataTransparencyScreen(onBack = onBack)
            }
        }
    }
}
