package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.espresso.Espresso
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.waitForSettings
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class SettingsSecondaryScreensKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Settings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Test
    fun darkThemeSelectionIsPersisted() =
        run {
            step("Open the theme dropdown and pick dark") {
                with(SettingsPage(composeRule)) {
                    click(themeDropdown)
                    click(themeOption("dark"))
                }
            }
            step("The stored theme is dark and settings stay open") {
                composeRule.waitForSettings(appSettingsRepository) { it.appTheme == "dark" }
                with(SettingsPage(composeRule)) { node(screenTag).assertIsDisplayed() }
            }
        }

    @Test
    fun customizationThemedIconToggleIsPersistedAndShapeInfoOpens() =
        run {
            var before = ""
            step("Open customization") {
                before = runBlocking { appSettingsRepository.snapshot() }.appIconStyle
                with(SettingsPage(composeRule)) { click(customization) }
                AppCustomizationPage(composeRule).awaitScreen()
            }
            step("Toggling the themed icon flips the stored icon style") {
                with(AppCustomizationPage(composeRule)) {
                    click(themedIcon)
                    composeRule.waitForSettings(appSettingsRepository) { it.appIconStyle != before }
                    assertNotEquals(before, runBlocking { appSettingsRepository.snapshot() }.appIconStyle)
                    click(themedIcon)
                    composeRule.waitForSettings(appSettingsRepository) { it.appIconStyle == before }
                }
            }
            step("The shape info row opens its sheet and confirm closes it") {
                with(AppCustomizationPage(composeRule)) {
                    click(shapeInfo)
                    waitFor(shapeInfoSheet)
                    click(shapeInfoSheetConfirm)
                    waitGone(shapeInfoSheet)
                }
            }
        }

    @Test
    fun dataTransparencyOpensAndBackReturnsToSettings() =
        run {
            step("Open data transparency") {
                with(SettingsPage(composeRule)) { click(dataTransparency) }
                DataTransparencyPage(composeRule).awaitScreen()
            }
            step("Back returns to settings") {
                Espresso.pressBack()
                SettingsPage(composeRule).awaitScreen()
            }
        }

    @Test
    fun aboutScreenShowsSourceAndReadmeLinks() =
        run {
            step("Open about") {
                with(SettingsPage(composeRule)) { click(about) }
                AboutPage(composeRule).awaitScreen()
            }
            step("The source code and readme links are present") {
                with(AboutPage(composeRule)) {
                    awaitDisplayed(sourceCode)
                    awaitDisplayed(readme)
                }
            }
        }
}
