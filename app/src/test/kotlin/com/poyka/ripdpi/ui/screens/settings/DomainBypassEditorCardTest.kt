package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
class DomainBypassEditorCardTest {
    @get:Rule val composeRule = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun `empty unsaved list disables copy and says why move is disabled`() {
        setCard(DomainBypassListScreenState(text = "", errors = emptyList(), cleanCount = 0, hasRule = false))

        composeRule.onNodeWithText(app.getString(R.string.domain_bypass_copy)).assertIsNotEnabled()
        composeRule.onNodeWithText(app.getString(R.string.domain_bypass_move_to_editor)).assertIsNotEnabled()
        composeRule.onNodeWithText(app.getString(R.string.domain_bypass_move_requires_save)).assertExists()
    }

    @Test
    fun `saved list enables copy and move without the reason`() {
        setCard(DomainBypassListScreenState(text = "example.net", errors = emptyList(), cleanCount = 1, hasRule = true))

        composeRule.onNodeWithText(app.getString(R.string.domain_bypass_copy)).assertIsEnabled()
        composeRule.onNodeWithText(app.getString(R.string.domain_bypass_move_to_editor)).assertIsEnabled()
        composeRule.onNodeWithText(app.getString(R.string.domain_bypass_move_requires_save)).assertDoesNotExist()
    }

    private fun setCard(state: DomainBypassListScreenState) {
        composeRule.setContent {
            RipDpiTheme { DomainBypassEditorCard(state, {}, {}, {}, {}, {}) }
        }
    }
}
