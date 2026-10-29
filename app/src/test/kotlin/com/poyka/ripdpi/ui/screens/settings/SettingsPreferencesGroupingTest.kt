package com.poyka.ripdpi.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class SettingsPreferencesGroupingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `interface exposes the haptic feedback preference`() {
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsAppearanceSection(uiState = SettingsUiState(), actions = noOpActions())
                }
            }
        }

        composeRule.onNodeWithTag("settings-haptic-feedback").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(sdk = [31])
    fun `dynamic color switch is offered on Android 12 and updates`() {
        val state = androidx.compose.runtime.mutableStateOf(SettingsUiState())
        val choices = mutableListOf<Boolean>()
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsAppearanceSection(
                        uiState = state.value,
                        actions =
                            noOpActions().copy(onDynamicColorChanged = {
                                choices += it
                                state.value = state.value.copy(dynamicColorEnabled = it)
                            }),
                    )
                }
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsDynamicColor).performScrollTo().performClick()
        assertEquals(listOf(true), choices)
    }

    @Test
    @Config(sdk = [30])
    fun `dynamic color switch is hidden before Android 12`() {
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsAppearanceSection(uiState = SettingsUiState(), actions = noOpActions())
                }
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsDynamicColor).assertDoesNotExist()
    }

    @Test
    fun `haptic choices expose and update the selected value`() {
        val state = androidx.compose.runtime.mutableStateOf(SettingsUiState())
        val choices = mutableListOf<Boolean>()
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsAppearanceSection(
                        uiState = state.value,
                        actions =
                            noOpActions().copy(onHapticFeedbackDisabledChanged = {
                                choices += it
                                state.value = state.value.copy(hapticFeedbackDisabled = it)
                            }),
                    )
                }
            }
        }
        val tag = RipDpiTestTags.SettingsHapticFeedback
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.dropdownOption(tag, "off")).performClick()
        composeRule.onNodeWithText(resource(R.string.haptic_feedback_off)).assertIsDisplayed()
        composeRule.onNodeWithTag(tag).assertContentDescriptionEquals(
            RuntimeEnvironment.getApplication().getString(
                R.string.haptic_feedback_accessibility,
                resource(R.string.haptic_feedback_title),
                resource(R.string.haptic_feedback_off),
            ),
        )
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.dropdownOption(tag, "system")).performClick()
        assertEquals(listOf(true, false), choices)
    }

    @After
    fun restoreSystemLanguage() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    @Config(sdk = [32])
    fun interfaceContainsSelectedNativeLanguage() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        composeRule.setContent {
            RipDpiTheme {
                SettingsAppearanceSection(uiState = SettingsUiState(), actions = noOpActions())
            }
        }

        composeRule.onNodeWithText("Русский").assertIsDisplayed()
    }

    @Test
    fun primaryGroupsFollowConnectionInterfacePrivacySupportOrder() {
        composeRule.setContent {
            RipDpiTheme {
                SettingsScreen(
                    uiState = SettingsUiState(),
                    actions = noOpActions(),
                    permissionSummary = PermissionSummaryUiState(),
                )
            }
        }
        listOf(
            R.string.settings_connectivity_section,
            R.string.settings_appearance_section,
            R.string.settings_security_section,
            R.string.settings_support_section,
        ).forEachIndexed { index, title ->
            composeRule.onNode(hasScrollToNodeAction()).performScrollToIndex(index)
            val locale =
                RuntimeEnvironment
                    .getApplication()
                    .resources.configuration.locales[0]
            composeRule.onNodeWithText(resource(title).uppercase(locale)).assertIsDisplayed()
        }
    }

    @Test
    fun connectionContainsBootControlWithoutLanguage() {
        var enabled: Boolean? = null
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsConnectivitySection(
                        uiState = SettingsUiState(startOnBootEnabled = true),
                        actions = noOpActions().copy(onStartOnBootChanged = { enabled = it }),
                        batteryOptimizationIgnored = false,
                    )
                }
            }
        }
        composeRule.onNodeWithText(resource(R.string.settings_language_title)).assertDoesNotExist()
        composeRule.onNodeWithText(resource(R.string.settings_start_on_boot_battery_warning)).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsStartOnBoot).performScrollTo().performClick()
        assertEquals(false, enabled)
    }

    @Test
    fun interfaceShowsSystemLanguageAndActualIconWithoutManage() {
        composeRule.setContent {
            RipDpiTheme {
                SettingsAppearanceSection(
                    uiState = SettingsUiState(appIconVariant = LauncherIconManager.GlitchIconKey),
                    actions = noOpActions(),
                )
            }
        }
        composeRule.onNodeWithText(resource(R.string.language_picker_system_language)).assertExists()
        composeRule.onNodeWithText(resource(R.string.customization_icon_glitch)).assertExists()
        composeRule.onNodeWithText(resource(R.string.settings_manage_action)).assertDoesNotExist()
        composeRule.onNodeWithTag("settings-language").assertHasClickAction().performClick()
        composeRule.onNodeWithText(resource(R.string.language_picker_sheet_title)).assertIsDisplayed()
    }

    @Test
    fun privacyContainsDataTransparencyAction() {
        var opened = false
        composeRule.setContent {
            RipDpiTheme {
                val state = SettingsUiState(isVpn = false)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsSecuritySection(
                        uiState = state,
                        localState = rememberSettingsScreenLocalState(state),
                        actions = noOpActions().copy(onOpenDataTransparency = { opened = true }),
                    )
                }
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsDataTransparency).performScrollTo().performClick()
        assertTrue(opened)
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsStartOnBoot).assertDoesNotExist()
    }

    @Test
    fun privacyRetainsVpnAndBackupPinWarnings() {
        composeRule.setContent {
            RipDpiTheme {
                val state = SettingsUiState(isVpn = true, biometricEnabled = true)
                SettingsSecuritySection(
                    uiState = state,
                    localState = rememberSettingsScreenLocalState(state),
                    actions = noOpActions(),
                )
            }
        }
        listOf(
            R.string.settings_vpn_flag_warning_body,
            R.string.settings_tethering_dns_warning_body,
            R.string.settings_warning_backup_pin_body,
        ).forEach { message -> composeRule.onNodeWithText(resource(message)).assertExists() }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun largeFontKeepsInterfaceAndMaintenanceActionsReachable() {
        var openedCustomization = false
        var openedBackup = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(360.dp).height(640.dp)) {
                        SettingsScreen(
                            uiState = SettingsUiState(),
                            actions =
                                noOpActions().copy(
                                    onOpenCustomization = { openedCustomization = true },
                                    onOpenBackupRestore = { openedBackup = true },
                                ),
                            permissionSummary = PermissionSummaryUiState(),
                        )
                    }
                }
            }
        }
        listOf(RipDpiTestTags.SettingsCustomization, RipDpiTestTags.SettingsBackupRestore).forEach { tag ->
            composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
            composeRule
                .onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
        }
        assertTrue(openedCustomization)
        assertTrue(openedBackup)
    }

    private fun resource(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun noOpActions(): SettingsScreenActions =
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
            onPersonaSelected = {},
            onWebRtcProtectionChanged = {},
            onExcludeRussianAppsChanged = {},
            onFullTunnelModeChanged = {},
            onStartOnBootChanged = {},
            onBiometricChanged = {},
            onSaveBackupPin = {},
        )
}
