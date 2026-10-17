package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
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
class SettingsPersonaChoiceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `opening interface choice preserves saved mode until explicit choice`() {
        val selectedPersonas = mutableListOf<String>()
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsAppearanceSection(
                        uiState = SettingsUiState(uiPersona = "simple"),
                        actions = actions(selectedPersonas::add),
                    )
                }
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersona).performScrollTo().performClick()

        assertEquals(emptyList<String>(), selectedPersonas)
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaSheet).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaGuided).assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaAdvanced).assertIsNotSelected()
        composeRule.onNodeWithText(string(R.string.onboarding_persona_simple_body)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onboarding_persona_advanced_body)).assertIsDisplayed()

        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaAdvanced).performClick()

        assertEquals(listOf("advanced"), selectedPersonas)
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaSheet).assertDoesNotExist()
    }

    @Test
    fun `cancel and dismiss preserve the saved interface mode`() {
        val selectedPersonas = mutableListOf<String>()
        setAppearance(onPersonaSelected = selectedPersonas::add)

        openChoice()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaCancel).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaSheet).assertDoesNotExist()
        assertEquals(emptyList<String>(), selectedPersonas)

        openChoice()
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { dismiss -> dismiss() }

        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaSheet).assertDoesNotExist()
        assertEquals(emptyList<String>(), selectedPersonas)
    }

    @Test
    fun `choosing the selected interface closes without a redundant write`() {
        val selectedPersonas = mutableListOf<String>()
        setAppearance(persona = "advanced", onPersonaSelected = selectedPersonas::add)

        openChoice()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaAdvanced).assertIsSelected().performClick()

        assertEquals(emptyList<String>(), selectedPersonas)
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaSheet).assertDoesNotExist()
    }

    @Test
    fun `repeated activation commits an explicit choice only once`() {
        val selectedPersonas = mutableListOf<String>()
        setAppearance(onPersonaSelected = selectedPersonas::add)
        openChoice()
        val activate =
            checkNotNull(
                composeRule
                    .onNodeWithTag(RipDpiTestTags.SettingsPersonaAdvanced)
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action,
            )

        composeRule.runOnIdle {
            activate()
            activate()
        }

        assertEquals(listOf("advanced"), selectedPersonas)
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaSheet).assertDoesNotExist()
    }

    @Test
    fun `reopened choice reflects the persisted selection`() {
        val selectedPersonas = mutableListOf<String>()
        val state = mutableStateOf(SettingsUiState(uiPersona = "advanced"))
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsAppearanceSection(
                        uiState = state.value,
                        actions =
                            actions { selection ->
                                selectedPersonas.add(selection)
                                state.value = state.value.copy(uiPersona = selection)
                            },
                    )
                }
            }
        }

        openChoice()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaGuided).performClick()
        openChoice()

        assertEquals(listOf("simple"), selectedPersonas)
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaGuided).assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersonaAdvanced).assertIsNotSelected()
    }

    private fun openChoice() {
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsPersona).performScrollTo().performClick()
    }

    private fun setAppearance(
        persona: String = "simple",
        onPersonaSelected: (String) -> Unit,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsAppearanceSection(
                        uiState = SettingsUiState(uiPersona = persona),
                        actions = actions(onPersonaSelected),
                    )
                }
            }
        }
    }

    private fun string(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)

    private fun actions(onPersonaSelected: (String) -> Unit) =
        SettingsScreenActions(
            onOpenProfileUtility = {},
            onOpenDnsSettings = {},
            onOpenAdvancedSettings = {},
            onOpenCustomization = {},
            onOpenAbout = {},
            onOpenDataTransparency = {},
            onShareDebugBundle = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
            onThemeSelected = {},
            onPersonaSelected = onPersonaSelected,
            onWebRtcProtectionChanged = {},
            onExcludeRussianAppsChanged = {},
            onFullTunnelModeChanged = {},
            onStartOnBootChanged = {},
            onBiometricChanged = {},
            onSaveBackupPin = {},
        )
}
