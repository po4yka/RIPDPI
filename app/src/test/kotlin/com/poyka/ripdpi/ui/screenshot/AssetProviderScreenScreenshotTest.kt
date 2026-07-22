package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.screens.settings.AssetProviderBanner
import com.poyka.ripdpi.ui.screens.settings.AssetProviderOperation
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
                    activeOperation = null,
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
                    activeOperation = null,
                    resultBanner =
                        AssetProviderBanner(
                            title = "Update failed",
                            message = "Could not reach the asset provider. Check your connection.",
                            tone = WarningBannerTone.Error,
                        ),
                ),
        )
    }

    @Test
    fun assetProviderLargeFont() {
        capture(
            name = "assetProviderLargeFont",
            fontScale = 1.5f,
            state =
                AssetProviderScreenState(
                    providerId = "sagernet",
                    customBaseUrl = "",
                    geoipTag = "v1.2.3",
                    geositeTag = "v1.2.3",
                    staleness = GeoAssetStaleness.Today,
                    activeOperation = null,
                    resultBanner = null,
                ),
        )
    }

    @Test
    fun assetProviderUpdatedMaximumFont() {
        capture(
            name = "assetProviderUpdatedMaximumFont",
            fontScale = 2f,
            heightDp = 1600,
            state =
                AssetProviderScreenState(
                    providerId = "sagernet",
                    customBaseUrl = "",
                    geoipTag = "202607200000",
                    geositeTag = "202607201234",
                    staleness = GeoAssetStaleness.Today,
                    activeOperation = null,
                    resultBanner =
                        AssetProviderBanner(
                            title = "Assets updated",
                            message = "GeoIP 202607200000 and geosite 202607201234 are ready.",
                            tone = WarningBannerTone.Info,
                        ),
                ),
        )
    }

    @Test
    fun assetProviderBusy() {
        capture(
            name = "assetProviderBusy",
            state =
                AssetProviderScreenState(
                    providerId = "custom",
                    customBaseUrl = "https://provider.example/assets",
                    geoipTag = "v1.2.3",
                    geositeTag = "v1.2.3",
                    staleness = GeoAssetStaleness.Today,
                    activeOperation = AssetProviderOperation.CheckUpdates,
                    resultBanner = null,
                ),
        )
    }

    private fun capture(
        name: String,
        state: AssetProviderScreenState,
        fontScale: Float = 1f,
        heightDp: Int = 1100,
    ) {
        captureScreenBothThemes(
            name = name,
            widthDp = 420,
            heightDp = heightDp,
            testClassFqn = FQN,
            fontScale = fontScale,
        ) {
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
