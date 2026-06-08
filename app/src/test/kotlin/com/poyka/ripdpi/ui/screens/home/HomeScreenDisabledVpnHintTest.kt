package com.poyka.ripdpi.ui.screens.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeScreenDisabledVpnHintTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `disabled vpn relay hint opens mode editor`() {
        var vpnConfigOpened = false
        var modeEditorOpened = false
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            modeCards =
                                persistentListOf(
                                    card(HomeMode.LocalDpiBypass),
                                    card(HomeMode.RemoteVpn).copy(
                                        primaryLabel = "Relay disabled",
                                        primaryActionEnabled = false,
                                        primaryActionDisabledHint = "Enable a relay in Configure to turn this on",
                                    ),
                                    card(HomeMode.Diagnostic),
                                ),
                        ),
                    onToggleConnection = {},
                    onVpnCardClick = { vpnConfigOpened = true },
                    onOpenModeEditor = { modeEditorOpened = true },
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeModeDisabledHint)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertTrue(modeEditorOpened)
            assertEquals(false, vpnConfigOpened)
        }
    }

    private fun card(mode: HomeMode): HomeModeCardUiState =
        HomeModeCardUiState(
            mode = mode,
            title =
                when (mode) {
                    HomeMode.LocalDpiBypass -> "Local bypass"
                    HomeMode.RemoteVpn -> "VPN"
                    HomeMode.Diagnostic -> "Network Diagnostic"
                },
            primaryLabel =
                when (mode) {
                    HomeMode.LocalDpiBypass -> "tlsrec_split_host - AdGuard DoH"
                    HomeMode.RemoteVpn -> "relay.example"
                    HomeMode.Diagnostic -> "No analysis yet"
                },
            statusLine = "Inactive",
            primaryActionLabel = if (mode == HomeMode.Diagnostic) "Run Scan" else "Enable",
            configureLabel = "Configure",
            primaryActionEnabled = true,
        )
}
