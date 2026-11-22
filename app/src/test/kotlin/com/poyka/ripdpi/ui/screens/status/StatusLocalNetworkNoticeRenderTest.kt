package com.poyka.ripdpi.ui.screens.status

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.diagnostics.HomeNetworkCharacterSummary
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU-w360dp-h900dp-mdpi")
class StatusLocalNetworkNoticeRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `Home distinguishes portal instructions from unvalidated access`() {
        val portal = projectedHome(portal = true, validated = false)
        val unvalidated = projectedHome(portal = false, validated = false)
        val normal = projectedHome(portal = false, validated = true)
        val state = mutableStateOf(portal)
        val signIn = "Откройте страницу входа в сеть. После входа запустите проверку снова."
        val validation = "Android не подтвердил доступ в интернет в этой сети."
        composeRule.setContent {
            RipDpiTheme {
                StatusDiagnosticsBottomSheetHost(
                    homeDiagnostics = state.value,
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onOpenAdvancedSettings = {},
                    onOpenModeEditor = {},
                    onOpenOwnedStackBrowser = {},
                    onShareAnalysis = {},
                    onDismissAnalysisSheet = {},
                    onDismissVerificationSheet = {},
                )
            }
        }
        val signInNode = composeRule.onNodeWithText(signIn, useUnmergedTree = true)
        val validationNode = composeRule.onNodeWithText(validation, useUnmergedTree = true)
        signInNode.performScrollTo().assertIsDisplayed()
        validationNode.assertDoesNotExist()

        composeRule.runOnIdle { state.value = unvalidated }
        validationNode.performScrollTo().assertIsDisplayed()
        signInNode.assertDoesNotExist()

        composeRule.runOnIdle { state.value = normal }
        signInNode.assertDoesNotExist()
        validationNode.assertDoesNotExist()
    }

    private fun projectedHome(
        portal: Boolean,
        validated: Boolean,
    ): StatusDiagnosticsUiState =
        projectHome(
            homeOutcome().copy(
                networkCharacter =
                    HomeNetworkCharacterSummary(
                        captivePortalDetected = portal,
                        networkValidated = validated,
                    ),
            ),
            "ru",
        )
}
