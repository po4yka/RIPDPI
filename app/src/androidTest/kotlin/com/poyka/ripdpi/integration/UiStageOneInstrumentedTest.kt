package com.poyka.ripdpi.integration

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.poyka.ripdpi.activities.ConfigRuntimeOperation
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.indicators.RipDpiProgressBar
import com.poyka.ripdpi.ui.screens.config.ConfigModeSection
import com.poyka.ripdpi.ui.screens.config.ConfigRuntimeCallbacks
import com.poyka.ripdpi.ui.screens.config.ConfigScreen
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingScreen
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingScreenActions
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.DefaultRipDpiMotion
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiStageOneInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pendingConnectionCannotDispatchAnotherStart() {
        var toggles = 0
        renderConfig(configState().copy(runtimeOperation = ConfigRuntimeOperation.Starting)) { toggles++ }
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, toggles) }
        saveScreenshot("starting")
    }

    @Test
    fun rejectedStartExposesPermissionRecovery() {
        var repairs = 0
        val state = mutableStateOf(configState())
        composeRule.setContent {
            RipDpiTheme {
                ConfigScreen(
                    uiState = state.value,
                    onModeSelected = {},
                    onRuntimeModeToggle = { _, _ ->
                        state.value =
                            state.value.copy(
                                runtimeError = "VPN permission required",
                                requiresVpnPermission = true,
                            )
                    },
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                    initialModeSection = ConfigModeSection.Vpn,
                    runtimeCallbacks = ConfigRuntimeCallbacks(requestVpnPermission = { repairs++ }),
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigRuntimeError).assertIsDisplayed()
        composeRule.onNodeWithText("VPN permission required").assertIsDisplayed()
        saveScreenshot("rejected-start")
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigVpnPermissionRecovery)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, repairs) }
    }

    @Test
    fun dnsSettingExposesItsValueAndAction() {
        val state = configState().copy(uiPersona = "advanced")
        renderConfig(state)
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigDnsSettings)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state.draft.dnsSummary))
        saveScreenshot("dns-setting")
    }

    @Test
    fun onboardingBackPreservesChoice() {
        val state = mutableStateOf(OnboardingUiState(currentPage = 1, selectedMode = Mode.Proxy))
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState = state.value,
                    actions =
                        OnboardingScreenActions(
                            onPageChanged = { state.value = state.value.copy(currentPage = it) },
                        ),
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingBack).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(0, state.value.currentPage)
            assertEquals(Mode.Proxy, state.value.selectedMode)
        }
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingBack).assertDoesNotExist()
        composeRule.runOnIdle { state.value = state.value.copy(currentPage = 1) }
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingBack).assertIsDisplayed()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle {
            assertEquals(0, state.value.currentPage)
            assertEquals(Mode.Proxy, state.value.selectedMode)
        }
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingBack).assertDoesNotExist()
        saveScreenshot("onboarding-return")
    }

    @Test
    fun staticUnknownProgressKeepsMeaningAndVisibleWork() {
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(
                    LocalRipDpiMotion provides DefaultRipDpiMotion.copy(animationsEnabled = false),
                ) {
                    RipDpiProgressBar(progress = null, modifier = Modifier.ripDpiTestTag("static-progress"))
                }
            }
        }
        val node = composeRule.onNodeWithTag("static-progress")
        node.assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        )
        val before = node.captureToImage().toPixelMap()
        assertTrue(
            "Static work must remain visible within the track",
            before[before.width / 2, before.height / 2] != before[before.width / 4, before.height / 2],
        )
        composeRule.mainClock.advanceTimeBy(1_000L)
        val after = node.captureToImage().toPixelMap()
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        for (x in 0 until before.width) {
            assertEquals("Disabled motion must stay static", before[x, before.height / 2], after[x, after.height / 2])
        }
        saveScreenshot("static-progress")
    }

    private fun configState(): ConfigUiState {
        val draft = AppSettingsSerializer.defaultValue.toConfigDraft()
        return ConfigUiState(draft = draft, presets = buildConfigPresets(draft))
    }

    private fun renderConfig(
        state: ConfigUiState,
        onToggle: () -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                ConfigScreen(
                    uiState = state,
                    onModeSelected = {},
                    onRuntimeModeToggle = { _, _ -> onToggle() },
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                    initialModeSection = ConfigModeSection.Vpn,
                )
            }
        }
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("ui-first-stage"))
        check(directory.isDirectory || directory.mkdirs())
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "$name.png")))
    }
}
