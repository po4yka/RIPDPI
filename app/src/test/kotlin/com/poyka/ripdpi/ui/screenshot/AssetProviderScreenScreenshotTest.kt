package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.screens.settings.AssetProviderBanner
import com.poyka.ripdpi.ui.screens.settings.AssetProviderScreen
import com.poyka.ripdpi.ui.screens.settings.AssetProviderScreenState
import com.poyka.ripdpi.ui.screens.settings.GeoAssetStaleness
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AssetProviderScreenScreenshotTest {
    @Test
    fun assetProviderFresh() {
        capture(
            name = "assetProviderFresh",
            state =
                AssetProviderScreenState(
                    providerId = "sagernet",
                    customBaseUrl = "",
                    geoipTag = "v1.2.3",
                    geositeTag = "v1.2.3",
                    staleness = GeoAssetStaleness.Today,
                    checking = false,
                    resultBanner =
                        AssetProviderBanner(
                            title = "Up to date",
                            message = "Geo databases are already at the latest release.",
                            tone = WarningBannerTone.Info,
                        ),
                ),
        )
    }

    @Test
    fun assetProviderStale() {
        capture(
            name = "assetProviderStale",
            state =
                AssetProviderScreenState(
                    providerId = "custom",
                    customBaseUrl = "https://github.com/me/repo/releases/latest/download",
                    geoipTag = "202405010000",
                    geositeTag = "",
                    staleness = GeoAssetStaleness.DaysAgo(StaleDays),
                    checking = false,
                    resultBanner =
                        AssetProviderBanner(
                            title = "Update failed",
                            message = "Could not reach the asset provider. Check your connection.",
                            tone = WarningBannerTone.Error,
                        ),
                ),
        )
    }

    private fun capture(
        name: String,
        state: AssetProviderScreenState,
    ) {
        captureScreenBothThemes(name = name, widthDp = 420, heightDp = 1100, testClassFqn = FQN) {
            AssetProviderScreen(
                state = state,
                onBack = {},
                onProviderSelected = {},
                onCustomUrlChanged = {},
                onCheckForUpdates = {},
                onImportGeoip = {},
                onImportGeosite = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.AssetProviderScreenScreenshotTest"
        const val StaleDays = 17L
    }
}
