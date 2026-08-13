package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class ProfileImportConfirmScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `supported halted profile exposes separate accessible check action`() {
        var checkRequests = 0
        composeRule.setContent {
            RipDpiTheme {
                ProfileImportConfirmScreen(
                    uiState =
                        ProfileImportConfirmUiState(
                            profile = sampleProfile,
                            serviceHalted = true,
                            checkSupported = true,
                        ),
                    onBack = {},
                    onConfirm = {},
                    onCheck = { checkRequests += 1 },
                )
            }
        }

        composeRule
            .onNodeWithText("Check profile")
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithText("Add").assertHasClickAction().assertIsEnabled()
        composeRule.runOnIdle { assertEquals(1, checkRequests) }
    }

    private companion object {
        val sampleProfile =
            ProxyProfile.VlessReality(
                id = "candidate",
                displayName = "Candidate",
                groupId = "",
                server = "relay.example",
                serverPort = 443,
                uuid = "11111111-2222-3333-4444-555555555555",
                realityPublicKey = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=",
                realityShortId = "abcd1234",
                serverName = "www.example.com",
            )
    }
}
