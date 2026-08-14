package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.ui.theme.RipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `error status is destructive and never reads as a resting state`() {
        lateinit var colors: RipDpiExtendedColors
        composeRule.setContent {
            RipDpiTheme { colors = RipDpiThemeTokens.colors }
        }

        composeRule.runOnIdle {
            assertEquals(colors.destructive, simpleStatusColor(ConnectionState.Error, colors))
            assertEquals(colors.success, simpleStatusColor(ConnectionState.Connected, colors))
            assertEquals(colors.mutedForeground, simpleStatusColor(ConnectionState.Disconnected, colors))
            assertEquals(colors.mutedForeground, simpleStatusColor(ConnectionState.Connecting, colors))
            assertNotEquals(colors.mutedForeground, colors.destructive)
        }
    }

    /**
     * The label is the node's accessible name, not a stateDescription hung on a nameless
     * node, so it must both be findable by text and announce politely on change.
     */
    private fun assertAccessibleStatus(label: String) {
        composeRule
            .onNodeWithText(label)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }
}
