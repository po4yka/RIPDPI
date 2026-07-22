package com.poyka.ripdpi.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.poyka.ripdpi.ui.screens.config.ModeEditorHydrationFailureDialog
import com.poyka.ripdpi.ui.screens.config.ModeEditorUnsavedChangesDialog
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
class ModeEditorDialogScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unsavedChangesDialogLight() {
        captureUnsavedChangesDialog(themePreference = "light")
    }

    @Test
    fun unsavedChangesDialogDark() {
        captureUnsavedChangesDialog(themePreference = "dark")
    }

    @Test
    fun hydrationFailureDialogLight() {
        captureHydrationFailureDialog(themePreference = "light")
    }

    @Test
    fun hydrationFailureDialogDark() {
        captureHydrationFailureDialog(themePreference = "dark")
    }

    private fun captureUnsavedChangesDialog(themePreference: String) {
        composeRule.setContent {
            RipDpiTheme(themePreference = themePreference) {
                ModeEditorUnsavedChangesDialog(
                    visible = true,
                    onKeepEditing = {},
                    onDiscard = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(RipDpiTestTags.UnsavedChangesDialog)
            .captureRoboImage()
    }

    private fun captureHydrationFailureDialog(themePreference: String) {
        composeRule.setContent {
            RipDpiTheme(themePreference = themePreference) {
                ModeEditorHydrationFailureDialog(
                    visible = true,
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onRoot().captureRoboImage()
    }
}
