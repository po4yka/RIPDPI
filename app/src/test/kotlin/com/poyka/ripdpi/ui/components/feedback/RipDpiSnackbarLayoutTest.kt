package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RipDpiSnackbarLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `capped snackbar stays centered in a wide host`() {
        composeRule.setContent {
            RipDpiTheme {
                // The same shape RipDpiSnackbarHost builds: a full-width bottom-center box with a width cap below the window.
                Box(Modifier.fillMaxWidth().ripDpiTestTag("host"), contentAlignment = Alignment.BottomCenter) {
                    RipDpiSnackbar(
                        message = "Saved",
                        modifier = Modifier.widthIn(max = 200.dp).fillMaxWidth(),
                        testTag = "snackbar",
                    )
                }
            }
        }
        val host = composeRule.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        val snackbar = composeRule.onNodeWithTag("snackbar").fetchSemanticsNode().boundsInRoot
        assertEquals("Snackbar width honors the cap", 200f * density(), snackbar.width, 1f)
        assertEquals("Snackbar is centered", host.center.x, snackbar.center.x, 1f)
    }

    private fun density(): Float = composeRule.density.density
}
