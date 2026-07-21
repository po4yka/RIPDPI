package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AssetProviderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `one active operation disables every asset action`() {
        render(activeOperation = AssetProviderOperation.ImportGeoip)

        assertControlEnabled(RipDpiTestTags.AssetProviderDropdown, enabled = false)
        assertControlEnabled(RipDpiTestTags.AssetProviderCustomUrl, enabled = false)
        assertActionEnabled(RipDpiTestTags.AssetProviderCheckUpdates, enabled = false)
        assertActionEnabled(RipDpiTestTags.AssetProviderImport, enabled = false)
        assertActionEnabled(RipDpiTestTags.AssetProviderImportGeosite, enabled = false)
    }

    @Test
    fun `idle state enables every asset action`() {
        render(activeOperation = null)

        assertControlEnabled(RipDpiTestTags.AssetProviderDropdown, enabled = true)
        assertControlEnabled(RipDpiTestTags.AssetProviderCustomUrl, enabled = true)
        assertActionEnabled(RipDpiTestTags.AssetProviderCheckUpdates, enabled = true)
        assertActionEnabled(RipDpiTestTags.AssetProviderImport, enabled = true)
        assertActionEnabled(RipDpiTestTags.AssetProviderImportGeosite, enabled = true)
    }

    @Test
    fun `storage failure banner gives actionable recovery instead of network advice`() {
        render(
            activeOperation = null,
            resultBanner = AssetProviderCheckOutcome.Failed(AssetProviderFailureReason.Storage),
        )

        val expected = RuntimeEnvironment.getApplication().getString(R.string.asset_provider_failure_storage)
        composeRule.onNodeWithText(expected).assertExists()
    }

    @Test
    fun `missing custom URL disables check and explains defensive no-check result`() {
        render(
            activeOperation = null,
            customBaseUrl = " ",
            resultBanner = AssetProviderCheckOutcome.Failed(AssetProviderFailureReason.MissingConfiguration),
        )

        assertActionEnabled(RipDpiTestTags.AssetProviderCheckUpdates, enabled = false)
        val expected =
            RuntimeEnvironment.getApplication().getString(
                R.string.asset_provider_failure_missing_configuration,
            )
        composeRule
            .onNode(hasScrollAction().and(hasAnyDescendant(hasTestTag(RipDpiTestTags.AssetProviderDropdown))))
            .performScrollToNode(
                androidx.compose.ui.test
                    .hasText(expected),
            )
        composeRule.onNodeWithText(expected).assertExists()
    }

    @Test
    fun `persistence failure is visible and retryable`() {
        var retryClicks = 0
        render(
            activeOperation = null,
            hasPersistenceError = true,
            onRetryConfigurationPersistence = { retryClicks += 1 },
        )

        val expected = RuntimeEnvironment.getApplication().getString(R.string.asset_provider_persistence_failed_body)
        composeRule.onNodeWithText(expected).performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(1, retryClicks) }
    }

    private fun assertActionEnabled(
        tag: String,
        enabled: Boolean,
    ) {
        composeRule
            .onNode(hasScrollAction().and(hasAnyDescendant(hasTestTag(RipDpiTestTags.AssetProviderDropdown))))
            .performScrollToNode(hasTestTag(tag))
        val action = composeRule.onNodeWithTag(tag)
        if (enabled) action.assertIsEnabled() else action.assertIsNotEnabled()
    }

    private fun assertControlEnabled(
        tag: String,
        enabled: Boolean,
    ) {
        val control = composeRule.onNodeWithTag(tag)
        if (enabled) control.assertIsEnabled() else control.assertIsNotEnabled()
    }

    private fun render(
        activeOperation: AssetProviderOperation?,
        resultBanner: AssetProviderCheckOutcome? = null,
        customBaseUrl: String = "https://provider.example/assets",
        hasPersistenceError: Boolean = false,
        onRetryConfigurationPersistence: () -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                AssetProviderScreen(
                    state =
                        AssetProviderScreenState(
                            providerId = "custom",
                            customBaseUrl = customBaseUrl,
                            geoipTag = "v1",
                            geositeTag = "v1",
                            staleness = GeoAssetStaleness.Today,
                            activeOperation = activeOperation,
                            resultBanner = resultBanner?.let { rememberOutcomeBanner(it) },
                            hasPersistenceError = hasPersistenceError,
                        ),
                    onBack = {},
                    onProviderSelected = {},
                    onCustomUrlChanged = {},
                    onCheckForUpdates = {},
                    onRetryConfigurationPersistence = onRetryConfigurationPersistence,
                    onImportGeoip = {},
                    onImportGeosite = {},
                )
            }
        }
    }
}
