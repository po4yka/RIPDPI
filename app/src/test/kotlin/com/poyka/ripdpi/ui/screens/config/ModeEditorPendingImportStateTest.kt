package com.poyka.ripdpi.ui.screens.config

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ModeEditorPendingImportStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `permission result is consumed for its original editor session`() {
        var consumedSessionId: Long? = null

        composeRule.setContent {
            ModeEditorPendingDraftUpdateEffect(
                pendingSessionId = 88L,
                editorSessionId = 88L,
                onReady = { consumedSessionId = it },
                onDiscard = {},
            )
        }

        composeRule.runOnIdle {
            assertEquals(88L, consumedSessionId)
        }
    }

    @Test
    fun `permission result is discarded while editor session is absent`() {
        var consumedSessionId: Long? = null
        var discarded = false

        composeRule.setContent {
            ModeEditorPendingDraftUpdateEffect(
                pendingSessionId = 88L,
                editorSessionId = null,
                onReady = { consumedSessionId = it },
                onDiscard = { discarded = true },
            )
        }

        composeRule.runOnIdle {
            assertNull(consumedSessionId)
            assertEquals(true, discarded)
        }
    }

    @Test
    fun `permission result is discarded for a replacement editor session`() {
        var consumedSessionId: Long? = null
        var discarded = false

        composeRule.setContent {
            ModeEditorPendingDraftUpdateEffect(
                pendingSessionId = 88L,
                editorSessionId = 99L,
                onReady = { consumedSessionId = it },
                onDiscard = { discarded = true },
            )
        }

        composeRule.runOnIdle {
            assertNull(consumedSessionId)
            assertEquals(true, discarded)
        }
    }

    @Test
    fun `hydration failure remains visible until the user dismisses it`() {
        var dismissClicks = 0
        composeRule.setContent {
            RipDpiTheme {
                ModeEditorHydrationFailureDialog(
                    visible = true,
                    onDismiss = { dismissClicks += 1 },
                )
            }
        }

        composeRule
            .onNodeWithText("Couldn't open this configuration. Your saved credentials were not changed.")
            .assertExists()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissClicks)
    }
}
