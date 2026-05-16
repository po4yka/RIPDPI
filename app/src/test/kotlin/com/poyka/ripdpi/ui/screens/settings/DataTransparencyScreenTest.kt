package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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

        composeRule.onNodeWithText("What we collect").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun whatWeDoNotCollectSectionHeaderIsDisplayed() {
        setScreen()

        composeRule.onNodeWithText("What we do NOT collect").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun doNotCollectNoBrowsingBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNodeWithText("No browsing history or URL content")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun doNotCollectNoPersonalDataBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNodeWithText("No personal data, accounts, or credentials")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun doNotCollectNoExternalServersBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNodeWithText("No data is sent to external servers automatically")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun doNotCollectNoAnalyticsBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNodeWithText("No analytics, crash reporting, or advertising SDKs")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun howStoredSectionHeaderIsDisplayed() {
        setScreen()

        composeRule.onNodeWithText("How data is stored").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun howStoredLocalDatabaseBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNodeWithText("All data stays on your device in a local database")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun howStoredRetentionPeriodBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNodeWithText("Configurable retention period (1–365 days)")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun howStoredDisableMonitoringBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNodeWithText("You can disable passive monitoring in Advanced Settings")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun howStoredExportExplicitBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNodeWithText("Export archives are only created when you explicitly request them")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun exportPrivacySectionHeaderIsDisplayed() {
        setScreen()

        composeRule.onNodeWithText("Export privacy").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun exportPrivacyExportRedactionBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNodeWithText("Exported archives redact IP addresses, WiFi identifiers, and other PII")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun exportPrivacyExportControlBulletIsDisplayed() {
        setScreen()

        composeRule
            .onNodeWithText("You control what is shared and with whom")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun setScreen(onBack: () -> Unit = {}) {
        composeRule.setContent {
            RipDpiTheme {
                DataTransparencyScreen(onBack = onBack)
            }
        }
    }
}
