package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.state.ToggleableState
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.services.SplitTunnelMode
import com.poyka.ripdpi.ui.journeys.waitForSettings
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.settings.SplitTunnelSystemUiGate
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.inject.Inject

// Present on every Android system image, so the picker always lists it.
private const val PickedPackage = "com.android.settings"
private const val ExcludeSegment = 1
private const val IncludeSegment = 2
private const val OffSegment = 0

@HiltAndroidTest
class SplitTunnelKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Settings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    /**
     * Android 17+ hands per-app exclusion to the system screen when it resolves. Then the in-app editor must stay
     * hidden behind the system hand-off, and the in-app steps do not apply.
     */
    private fun systemScreenOwnsSplitTunnel(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!SplitTunnelSystemUiGate.systemScreenAvailable(context)) return false
        run {
            step("The system owns per-app exclusion: the hand-off shows and the in-app editor does not") {
                with(SettingsRoutingEntryPage(composeRule)) { openAdvancedRow(splitTunnel) }
                with(SplitTunnelPage(composeRule)) {
                    awaitScreen()
                    awaitDisplayed(openSystemSettings)
                    assertFalse(exists(modeSelector))
                }
            }
        }
        return true
    }

    @Test
    fun excludedAppSelectionSurvivesReopen() {
        if (systemScreenOwnsSplitTunnel()) return
        run {
            step("Open split tunneling from settings and choose exclude") {
                with(SettingsRoutingEntryPage(composeRule)) { openAdvancedRow(splitTunnel) }
                with(SplitTunnelPage(composeRule)) {
                    awaitScreen()
                    selectMode(ExcludeSegment)
                    awaitModeSelected(ExcludeSegment)
                }
                composeRule.waitForSettings(appSettingsRepository) { it.splitTunnelMode == SplitTunnelMode.Exclude }
            }
            step("Pick $PickedPackage in the app picker and confirm") {
                with(SplitTunnelPage(composeRule)) {
                    click(editApps)
                    waitFor(appPicker)
                    searchApp(PickedPackage)
                    click(app(PickedPackage))
                    activate(appPickerDone)
                    waitGone(appPicker)
                }
                composeRule.waitForSettings(appSettingsRepository) {
                    it.splitTunnelPackagesList == listOf(PickedPackage)
                }
            }
            step("Leave and reopen: exclude and the picked app are still selected") {
                composeRule.backTo(SettingsRoutingEntryPage(composeRule).screenTag)
                with(SettingsRoutingEntryPage(composeRule)) {
                    openAdvancedRow(splitTunnel)
                }
                with(SplitTunnelPage(composeRule)) {
                    awaitScreen()
                    awaitModeSelected(ExcludeSegment)
                    click(editApps)
                    waitFor(appPicker)
                    searchApp(PickedPackage)
                    require(app(PickedPackage))
                    assertEquals(ToggleableState.On, toggleState(app(PickedPackage)))
                }
            }
        }
    }

    @Test
    fun includeModePersistsAndOffHidesTheAppEditor() {
        if (systemScreenOwnsSplitTunnel()) return
        run {
            step("Choose include") {
                with(SettingsRoutingEntryPage(composeRule)) { openAdvancedRow(splitTunnel) }
                with(SplitTunnelPage(composeRule)) {
                    awaitScreen()
                    selectMode(IncludeSegment)
                    awaitModeSelected(IncludeSegment)
                    waitFor(editApps)
                }
                composeRule.waitForSettings(appSettingsRepository) { it.splitTunnelMode == SplitTunnelMode.Include }
            }
            step("Leave and reopen: include is still selected") {
                composeRule.backTo(SettingsRoutingEntryPage(composeRule).screenTag)
                with(SettingsRoutingEntryPage(composeRule)) {
                    openAdvancedRow(splitTunnel)
                }
                SplitTunnelPage(composeRule).awaitModeSelected(IncludeSegment)
            }
            step("Choosing off stores off and removes the app editor") {
                with(SplitTunnelPage(composeRule)) {
                    selectMode(OffSegment)
                    awaitModeSelected(OffSegment)
                    waitGone(editApps)
                }
                composeRule.waitForSettings(appSettingsRepository) { it.splitTunnelMode == SplitTunnelMode.Off }
            }
        }
    }
}
