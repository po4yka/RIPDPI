package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
class UnsavedChangesDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `keep editing dismisses without discarding`() {
        var keepCalls = 0
        var discardCalls = 0
        render(
            onKeepEditing = { keepCalls += 1 },
            onDiscard = { discardCalls += 1 },
        )

        composeRule.onNodeWithTag(RipDpiTestTags.UnsavedChangesDialog).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.UnsavedChangesKeepEditing).performClick()

        assertEquals(1, keepCalls)
        assertEquals(0, discardCalls)
    }

    @Test
    fun `discard invokes destructive exit`() {
        var discardCalls = 0
        render(onKeepEditing = {}, onDiscard = { discardCalls += 1 })

        composeRule.onNodeWithTag(RipDpiTestTags.UnsavedChangesDiscard).performClick()

        assertEquals(1, discardCalls)
    }

    private fun render(
        onKeepEditing: () -> Unit,
        onDiscard: () -> Unit,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                UnsavedChangesDialog(
                    onKeepEditing = onKeepEditing,
                    onDiscard = onDiscard,
                )
            }
        }
    }
}
