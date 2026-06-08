package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.screens.settings.CurrentNetworkBindingUiState
import com.poyka.ripdpi.ui.screens.settings.NetworkAutoProfileBindingUiState
import com.poyka.ripdpi.ui.screens.settings.NetworkAutoProfilesScreen
import com.poyka.ripdpi.ui.screens.settings.NetworkAutoProfilesUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NetworkAutoProfilesScreenScreenshotTest {
    @Test
    fun boundNetworkAutoProfilesScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiTheme(themePreference = "light") {
                NetworkAutoProfilesScreen(
                    uiState =
                        NetworkAutoProfilesUiState(
                            currentNetwork =
                                CurrentNetworkBindingUiState.Available(
                                    fingerprintHash = "current",
                                    label = "Wifi · Validated · DNS 2 · Private DNS",
                                ),
                            currentProfileName = "VPN · split · Disabled",
                            bindings =
                                persistentListOf(
                                    NetworkAutoProfileBindingUiState(
                                        id = "wifi",
                                        fingerprintHash = "wifi",
                                        networkLabel = "Wifi · Validated · DNS 2",
                                        profileName = "VPN · split · Disabled",
                                        updatedAt = 1_000L,
                                    ),
                                    NetworkAutoProfileBindingUiState(
                                        id = "cellular",
                                        fingerprintHash = "cellular",
                                        networkLabel = "Cellular · Validated · DNS 1",
                                        profileName = "Proxy · split · TUIC v5",
                                        updatedAt = 2_000L,
                                    ),
                                ),
                        ),
                    onBack = {},
                    onRefreshNetwork = {},
                    onBindCurrentProfile = {},
                    onDeleteBinding = {},
                )
            }
        }
    }
}
