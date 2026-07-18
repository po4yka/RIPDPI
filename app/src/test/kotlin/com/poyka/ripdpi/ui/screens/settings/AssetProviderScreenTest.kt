package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
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

    private fun render(activeOperation: AssetProviderOperation?) {
        composeRule.setContent {
            RipDpiTheme {
                AssetProviderScreen(
                    state =
                        AssetProviderScreenState(
                            providerId = "custom",
                            customBaseUrl = "https://provider.example/assets",
                            geoipTag = "v1",
                            geositeTag = "v1",
                            staleness = GeoAssetStaleness.Today,
                            activeOperation = activeOperation,
                            resultBanner = null,
                        ),
                    onBack = {},
                    onProviderSelected = {},
                    onCustomUrlChanged = {},
                    onCheckForUpdates = {},
                    onImportGeoip = {},
                    onImportGeosite = {},
                )
            }
        }
    }
}
