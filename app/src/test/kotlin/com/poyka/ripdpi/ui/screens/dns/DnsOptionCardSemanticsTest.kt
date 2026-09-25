package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.poyka.ripdpi.ui.theme.RipDpiIcons
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
class DnsOptionCardSemanticsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `DNS cards announce selected state`() {
        composeRule.setContent {
            RipDpiTheme {
                Column {
                    DnsOptionCard(RipDpiIcons.Dns, "Selected DNS", "", true, emptyList(), {})
                    DnsOptionCard(RipDpiIcons.Dns, "Other DNS", "", false, emptyList(), {})
                }
            }
        }
        composeRule.onNodeWithContentDescription("Selected DNS").assertIsSelected()
        composeRule.onNodeWithContentDescription("Other DNS").assertIsNotSelected()
    }
}
