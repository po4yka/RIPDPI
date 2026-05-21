package com.poyka.ripdpi.ui.screens.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
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

        scrollTo("WHAT WE COLLECT")
        composeRule.onNodeWithText("WHAT WE COLLECT").fetchSemanticsNode()
    }

    @Test
    fun whatWeDoNotCollectSectionHeaderIsDisplayed() {
        setScreen()

        scrollTo("WHAT WE DO NOT COLLECT")
        composeRule.onNodeWithText("WHAT WE DO NOT COLLECT").fetchSemanticsNode()
    }

    @Test
    fun doNotCollectNoBrowsingBulletIsDisplayed() {
        setScreen()
        val copy = string(R.string.data_transparency_no_browsing)

        scrollTo(copy)
        composeRule.onNodeWithText(copy).assertIsDisplayed()
    }

    @Test
    fun doNotCollectNoPersonalDataBulletIsDisplayed() {
        setScreen()
        val copy = string(R.string.data_transparency_no_personal_data)

        scrollTo(copy)
        composeRule.onNodeWithText(copy).assertIsDisplayed()
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

        scrollTo("HOW DATA IS STORED")
        composeRule.onNodeWithText("HOW DATA IS STORED").fetchSemanticsNode()
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
        val copy = string(R.string.data_transparency_export_explicit)

        scrollTo(copy)
        composeRule.onNodeWithText(copy).assertIsDisplayed()
    }

    @Test
    fun exportPrivacySectionHeaderIsDisplayed() {
        setScreen()

        scrollTo("EXPORT PRIVACY")
        composeRule.onNodeWithText("EXPORT PRIVACY").fetchSemanticsNode()
    }

    @Test
    fun exportPrivacyExportRedactionBulletIsDisplayed() {
        setScreen()
        val copy = string(R.string.data_transparency_export_redaction)

        scrollTo(copy)
        composeRule.onNodeWithText(copy).assertIsDisplayed()
    }

    @Test
    fun exportPrivacyExportControlBulletIsDisplayed() {
        setScreen()
        val copy = string(R.string.data_transparency_export_control)

        scrollTo(copy)
        composeRule.onNodeWithText(copy).assertIsDisplayed()
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

    private fun string(resId: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
