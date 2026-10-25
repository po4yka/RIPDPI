package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
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
@Config(sdk = [35])
class ModeEditorRelayEnabledTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `tagged relay switch updates state and reveals transport controls`() {
        val draft = mutableStateOf(ConfigDraft(relayEnabled = false))
        val changes = mutableListOf<Boolean>()
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModeEditorRelaySection(
                        draft = draft.value,
                        uiState = ConfigUiState(),
                        actions =
                            NoOpModeEditorActions.copy(
                                onRelayEnabledChanged = {
                                    changes += it
                                    draft.value = draft.value.copy(relayEnabled = it)
                                },
                            ),
                    )
                }
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.modeEditorRelaySection("tls-transports")).assertDoesNotExist()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ModeEditorRelayEnabled)
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
        composeRule.onNodeWithTag(RipDpiTestTags.modeEditorRelaySection("tls-transports")).assertExists()
        composeRule.runOnIdle { assertEquals(listOf(true), changes) }
    }
}
