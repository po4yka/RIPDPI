package com.poyka.ripdpi.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fa")
class LocalizedDigitsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `Persian counts use Persian digits without grouping and other text is kept`() {
        composeRule.setContent {
            Column {
                Text(localizedDigits("36"))
                Text(localizedInteger(8080L))
                Text(localizedDigits("1/2"))
            }
        }
        // U+06F3 U+06F6 and U+06F8 U+06F0 U+06F8 U+06F0 are the Persian digits for 36 and 8080.
        composeRule.onNodeWithText("۳۶").assertExists()
        composeRule.onNodeWithText("۸۰۸۰").assertExists()
        composeRule.onNodeWithText("1/2").assertExists()
    }
}
