package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric UI tests for [ProfileShareDialog]: QR + URI share for a saved profile.
 *
 * The decisive assertion is that the secrets-redaction warning shows first and the share
 * content (URI / QR / copy / share buttons) is not present until the warning is
 * acknowledged after its 5-second hold.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProfileShareDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val vlessProfile =
        ProxyProfile.Vless(
            id = "p1",
            displayName = "Tokyo",
            groupId = "g1",
            server = "example.com",
            serverPort = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
        )

    @Test
    fun `the warning is shown first and the share content is hidden`() {
        val viewModel = ProfileShareViewModel()
        viewModel.onShareProfileRequested(vlessProfile)
        composeRule.setContent {
            RipDpiTheme {
                ProfileShareDialog(
                    onDismiss = {},
                    onCopyUri = {},
                    onShareImage = {},
                    viewModel = viewModel,
                )
            }
        }

        // The warning copy is visible.
        composeRule.onNodeWithText("This QR code contains secrets").assertExists()
        // The share URI is not exposed yet.
        composeRule.onNodeWithText("Profile link").assertDoesNotExist()
    }

    @Test
    fun `acknowledging after the hold reveals the share content`() {
        val viewModel = ProfileShareViewModel()
        viewModel.onShareProfileRequested(vlessProfile)
        composeRule.setContent {
            RipDpiTheme {
                ProfileShareDialog(
                    onDismiss = {},
                    onCopyUri = {},
                    onShareImage = {},
                    viewModel = viewModel,
                )
            }
        }

        // Drive the 5s hold to elapsed, then acknowledge.
        composeRule.runOnIdle {
            viewModel.onWarningHoldElapsed()
            viewModel.onWarningAcknowledged()
        }

        composeRule.onNodeWithText("Profile link").assertExists()
        composeRule.onNodeWithText("Copy link").assertExists()
    }

    @Test
    fun `copying the uri emits the canonical share link`() {
        val viewModel = ProfileShareViewModel()
        var copied: String? = null
        composeRule.setContent {
            RipDpiTheme {
                viewModel.onShareProfileRequested(vlessProfile)
                ProfileShareDialog(
                    onDismiss = {},
                    onCopyUri = { copied = it },
                    onShareImage = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.runOnIdle {
            viewModel.onWarningHoldElapsed()
            viewModel.onWarningAcknowledged()
        }
        composeRule.onNodeWithText("Copy link").performClick()

        composeRule.runOnIdle {
            org.junit.Assert.assertNotNull(copied)
            org.junit.Assert.assertTrue(copied!!.startsWith("vless://"))
        }
    }
}
