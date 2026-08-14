package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.ui.screens.proxyimport.ProfileImportConfirmScreen
import com.poyka.ripdpi.ui.screens.proxyimport.ProfileImportConfirmUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProfileImportConfirmScreenshotTest {
    @Test
    fun emptyState() {
        capture("emptyState", ProfileImportConfirmUiState(profile = null))
    }

    @Test
    fun populatedState() {
        capture(
            "populatedState",
            ProfileImportConfirmUiState(profile = sampleVlessProfile),
        )
    }

    @Test
    fun importedState() {
        capture(
            "importedState",
            ProfileImportConfirmUiState(
                profile = sampleShadowsocksProfile,
                importing = true,
            ),
        )
    }

    private fun capture(
        name: String,
        state: ProfileImportConfirmUiState,
    ) {
        captureScreenBothThemes(name = name, widthDp = 420, heightDp = 900, testClassFqn = FQN) {
            ProfileImportConfirmScreen(
                uiState = state,
                onBack = {},
                onConfirm = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.ProfileImportConfirmScreenshotTest"

        val sampleVlessProfile =
            ProxyProfile.Vless(
                id = "profile-vless-0001",
                displayName = "Frankfurt VLESS",
                groupId = "group-import-0001",
                server = "203.0.113.10",
                serverPort = 443,
                uuid = "11111111-2222-3333-4444-555555555555",
            )

        val sampleShadowsocksProfile =
            ProxyProfile.Shadowsocks(
                id = "profile-ss-0001",
                displayName = "Amsterdam SS",
                groupId = "group-import-0001",
                server = "198.51.100.20",
                serverPort = 8388,
                method = "2022-blake3-aes-256-gcm",
                // Obvious non-secret screenshot fixture (not a real credential).
                password = "fixture-password",
            )
    }
}
