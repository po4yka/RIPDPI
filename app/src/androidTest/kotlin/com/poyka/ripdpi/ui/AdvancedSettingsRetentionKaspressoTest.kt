package com.poyka.ripdpi.ui

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.integration.scrollAndClick
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.settings.AdvancedTextSetting
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import javax.inject.Inject

private class AdvancedSettingsScreen(
    provider: SemanticsNodeInteractionsProvider,
) : ComposeScreen<AdvancedSettingsScreen>(provider, { hasTestTag(RipDpiTestTags.screen(Route.AdvancedSettings)) }) {
    val retentionInput: KNode =
        onNode { hasTestTag(RipDpiTestTags.advancedInput(AdvancedTextSetting.DiagnosticsHistoryRetentionDays)) }
    val retentionSave: KNode =
        onNode { hasTestTag(RipDpiTestTags.advancedSave(AdvancedTextSetting.DiagnosticsHistoryRetentionDays)) }
}

@HiltAndroidTest
class AdvancedSettingsRetentionKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.AdvancedSettings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Test
    fun editedRetentionDaysArePersistedOnSave() =
        run {
            step("Replace the diagnostics history retention with 21 days and save") {
                ComposeScreen.onComposeScreen<AdvancedSettingsScreen>(composeRule) {
                    assertIsDisplayed()
                    retentionInput.performTextReplacement("21")
                    retentionSave.scrollAndClick()
                }
            }
            step("The saved value replaces the default 14 in app settings") {
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    runBlocking { appSettingsRepository.snapshot().diagnosticsHistoryRetentionDays } == 21
                }
            }
        }
}
