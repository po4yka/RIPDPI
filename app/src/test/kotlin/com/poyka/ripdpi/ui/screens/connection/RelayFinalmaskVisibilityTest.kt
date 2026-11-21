package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RelayFinalmaskTypeFragment
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
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
class RelayFinalmaskVisibilityTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `Finalmask is shown only for supported VLESS transport`() {
        val title = RuntimeEnvironment.getApplication().getString(R.string.config_relay_finalmask_title)
        val draft = ConnectionUiState().draft.copy(relayKind = RelayKindVlessReality, relayVlessTransport = "tcp")
        composeRule.setContent {
            RipDpiTheme { RelayFieldsWithAdvanced(draft = draft, uiState = ConnectionUiState(draft = draft)) }
        }
        composeRule.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun `Finalmask is shown for VLESS over xHTTP`() {
        val title = RuntimeEnvironment.getApplication().getString(R.string.config_relay_finalmask_title)
        val draft =
            ConnectionUiState().draft.copy(
                relayKind = RelayKindVlessReality,
                relayVlessTransport = RelayVlessTransportXhttp,
            )
        composeRule.setContent {
            RipDpiTheme { RelayFieldsWithAdvanced(draft = draft, uiState = ConnectionUiState(draft = draft)) }
        }
        composeRule.onNodeWithText(title).assertExists()
    }

    @Test
    fun `Finalmask is shown for plain VLESS over xHTTP`() {
        val title = RuntimeEnvironment.getApplication().getString(R.string.config_relay_finalmask_title)
        val draft =
            ConnectionUiState().draft.copy(
                relayKind = RelayKindVless,
                relayVlessTransport = RelayVlessTransportXhttp,
            )
        composeRule.setContent {
            RipDpiTheme { RelayFieldsWithAdvanced(draft = draft, uiState = ConnectionUiState(draft = draft)) }
        }
        composeRule.onNodeWithText(title).assertExists()
    }

    @Test
    fun `unsupported transport with a prior Finalmask choice can turn it off`() {
        val context = RuntimeEnvironment.getApplication()
        var selectedType = RelayFinalmaskTypeFragment
        val draft =
            ConnectionUiState().draft.copy(
                relayKind = RelayKindVlessReality,
                relayVlessTransport = "tcp",
                relayFinalmaskType = RelayFinalmaskTypeFragment,
            )
        composeRule.setContent {
            RipDpiTheme {
                RelayFieldsWithAdvanced(
                    draft = draft,
                    uiState = ConnectionUiState(draft = draft),
                    actions =
                        RelayKindFieldActions(
                            finalmask = RelayFinalmaskActions(onRelayFinalmaskTypeChanged = { selectedType = it }),
                        ),
                )
            }
        }
        composeRule
            .onNodeWithText(context.getString(R.string.config_relay_finalmask_off))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.config_relay_finalmask_fragment)).assertDoesNotExist()
        assertEquals(RelayFinalmaskTypeOff, selectedType)
    }
}

/** The kind's fields with its Advanced group open, as the editor shows them once Advanced is expanded. */
@Composable
internal fun RelayFieldsWithAdvanced(
    draft: ConnectionDraft,
    uiState: ConnectionUiState,
    actions: RelayKindFieldActions = RelayKindFieldActions(),
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        RelayFieldsContent(draft = draft, uiState = uiState, actions = actions)
        RelayKindAdvancedFields(draft = draft, uiState = uiState, actions = actions)
    }
}
