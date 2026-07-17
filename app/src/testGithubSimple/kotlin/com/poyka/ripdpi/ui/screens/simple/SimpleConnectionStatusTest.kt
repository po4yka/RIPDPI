package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class SimpleConnectionStatusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `connection status announces state changes politely`() {
        var connectionState by mutableStateOf(ConnectionState.Disconnected)
        composeRule.setContent {
            RipDpiTheme {
                SimpleConnectionStatus(connectionState = connectionState)
            }
        }

        assertAccessibleStatus("Disconnected")

        composeRule.runOnIdle { connectionState = ConnectionState.Connecting }
        composeRule.waitForIdle()

        assertAccessibleStatus("Connecting…")
    }

    private fun assertAccessibleStatus(label: String) {
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    label,
                ),
            ).assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }
}
