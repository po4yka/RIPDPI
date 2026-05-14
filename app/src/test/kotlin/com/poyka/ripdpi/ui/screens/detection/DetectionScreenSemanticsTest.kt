package com.poyka.ripdpi.ui.screens.detection

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.poyka.ripdpi.ui.navigation.Route
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
class DetectionScreenSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun detectionCheckScreenExposesRouteTag() {
        composeRule.setContent {
            RipDpiTheme {
                DetectionCheckScreen(
                    uiState = DetectionCheckUiState(),
                    onStart = {},
                    onStop = {},
                    onBack = {},
                    onDismissOnboarding = {},
                    onApplyFixes = {},
                    onPrivacyModeChange = {},
                    onReloadCommunityStats = {},
                    onRequestPermissions = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.DetectionCheck)).fetchSemanticsNode()
    }

    @Test
    fun detectionSettingsScreenExposesRouteTag() {
        composeRule.setContent {
            RipDpiTheme {
                DetectionSettingsScreen(
                    state = DetectionSettingsUiState(),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.DetectionSettings)).fetchSemanticsNode()
    }
}
