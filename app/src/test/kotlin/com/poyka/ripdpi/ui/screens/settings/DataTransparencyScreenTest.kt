package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

        composeRule.onNodeWithText("What we collect").fetchSemanticsNode()
    }

    @Test
    fun whatWeDoNotCollectSectionHeaderIsDisplayed() {
        setScreen()

        scrollTo("No browsing history or URL content")
        composeRule.onNodeWithText("What we do NOT collect").fetchSemanticsNode()
    }

    @Test
    fun doNotCollectNoBrowsingBulletIsDisplayed() {
        setScreen()

        scrollTo("No browsing history or URL content")
        composeRule.onNodeWithText("No browsing history or URL content").assertIsDisplayed()
    }

    @Test
    fun doNotCollectNoPersonalDataBulletIsDisplayed() {
        setScreen()

        scrollTo("No personal data, accounts, or credentials")
        composeRule.onNodeWithText("No personal data, accounts, or credentials").assertIsDisplayed()
    }

    @Test
    fun doNotCollectNoExternalServersBulletIsDisplayed() {
        setScreen()

        scrollTo("No data is sent to external servers automatically")
        composeRule
            .onNodeWithText("No data is sent to external servers automatically")
            .assertIsDisplayed()
    }

    @Test
    fun doNotCollectNoAnalyticsBulletIsDisplayed() {
        setScreen()

        scrollTo("No analytics, crash reporting, or advertising SDKs")
        composeRule
            .onNodeWithText("No analytics, crash reporting, or advertising SDKs")
            .assertIsDisplayed()
    }

    @Test
    fun howStoredSectionHeaderIsDisplayed() {
        setScreen()

        scrollTo("All data stays on your device in a local database")
        composeRule.onNodeWithText("How data is stored").fetchSemanticsNode()
    }

    @Test
    fun howStoredLocalDatabaseBulletIsDisplayed() {
        setScreen()

        scrollTo("All data stays on your device in a local database")
        composeRule
            .onNodeWithText("All data stays on your device in a local database")
            .assertIsDisplayed()
    }

    @Test
    fun howStoredRetentionPeriodBulletIsDisplayed() {
        setScreen()

        scrollTo("Configurable retention period (1–365 days)")
        composeRule
            .onNodeWithText("Configurable retention period (1–365 days)")
            .assertIsDisplayed()
    }

    @Test
    fun howStoredDisableMonitoringBulletIsDisplayed() {
        setScreen()

        scrollTo("You can disable passive monitoring in Advanced Settings")
        composeRule
            .onNodeWithText("You can disable passive monitoring in Advanced Settings")
            .assertIsDisplayed()
    }

    @Test
    fun howStoredExportExplicitBulletIsDisplayed() {
        setScreen()

        scrollTo("Export archives are only created when you explicitly request them")
        composeRule
            .onNodeWithText("Export archives are only created when you explicitly request them")
            .assertIsDisplayed()
    }

    @Test
    fun exportPrivacySectionHeaderIsDisplayed() {
        setScreen()

        scrollTo("You control what is shared and with whom")
        composeRule.onNodeWithText("Export privacy").fetchSemanticsNode()
    }

    @Test
    fun exportPrivacyExportRedactionBulletIsDisplayed() {
        setScreen()

        scrollTo("Exported archives redact IP addresses, WiFi identifiers, and other PII")
        composeRule
            .onNodeWithText("Exported archives redact IP addresses, WiFi identifiers, and other PII")
            .assertIsDisplayed()
    }

    @Test
    fun exportPrivacyExportControlBulletIsDisplayed() {
        setScreen()

        scrollTo("You control what is shared and with whom")
        composeRule.onNodeWithText("You control what is shared and with whom").assertIsDisplayed()
    }

    private fun scrollTo(text: String) {
        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(text))
    }

    private fun setScreen(onBack: () -> Unit = {}) {
        composeRule.setContent {
            RipDpiTheme {
                DataTransparencyScreen(onBack = onBack)
            }
        }
    }
}
