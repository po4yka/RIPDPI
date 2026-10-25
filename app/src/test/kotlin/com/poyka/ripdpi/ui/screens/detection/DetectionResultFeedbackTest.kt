package com.poyka.ripdpi.ui.screens.detection

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DetectionResultFeedbackTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `retained detection result stays quiet when its screen is reopened`() {
        val feedback = mutableListOf<Int>()
        val recordingView =
            object : View(RuntimeEnvironment.getApplication()) {
                override fun performHapticFeedback(feedbackConstant: Int): Boolean {
                    feedback += feedbackConstant
                    return true
                }
            }
        val visible = mutableStateOf(true)
        val state = DetectionCheckUiState(result = detectionResult())
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(LocalView provides recordingView) {
                    if (visible.value) {
                        DetectionCheckScreen(
                            uiState = state,
                            onStart = {},
                            onStop = {},
                            onBack = {},
                            onDismissOnboarding = {},
                            onReloadCommunityStats = {},
                            onRequestPermissions = {},
                        )
                    }
                }
            }
        }
        composeRule.runOnIdle { visible.value = false }
        composeRule.runOnIdle { visible.value = true }
        composeRule.runOnIdle { assertEquals(emptyList<Int>(), feedback) }
    }
}
