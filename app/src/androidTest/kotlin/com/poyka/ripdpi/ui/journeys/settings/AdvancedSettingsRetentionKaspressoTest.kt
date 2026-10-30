package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.waitForSettings
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import javax.inject.Inject

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
                with(AdvancedSettingsPage(composeRule)) {
                    assertIsDisplayed()
                    node(retentionInput).performTextReplacement("21")
                    click(retentionSave)
                }
            }
            step("The saved value replaces the default 14 in app settings") {
                composeRule.waitForSettings(appSettingsRepository, timeoutMillis = 5_000) {
                    it.diagnosticsHistoryRetentionDays == 21
                }
            }
        }
}
