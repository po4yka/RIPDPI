package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.espresso.Espresso
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
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
                composeRule.clickTag(RipDpiTestTags.SettingsThemeDropdown)
                composeRule.clickTag(RipDpiTestTags.dropdownOption(RipDpiTestTags.SettingsThemeDropdown, "dark"))
            }
            step("The stored theme is dark and settings stay open") {
                composeRule.awaitSettings(repository = appSettingsRepository) { it.appTheme == "dark" }
                composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.Settings)).assertIsDisplayed()
            }
        }

    @Test
    fun customizationThemedIconToggleIsPersistedAndShapeInfoOpens() =
        run {
            var before = ""
            step("Open customization") {
                before = runBlocking { appSettingsRepository.snapshot() }.appIconStyle
                composeRule.clickTag(RipDpiTestTags.SettingsCustomization)
                composeRule.awaitTag(RipDpiTestTags.screen(Route.AppCustomization))
            }
            step("Toggling the themed icon flips the stored icon style") {
                composeRule.clickTag(RipDpiTestTags.CustomizationThemedIcon)
                composeRule.awaitSettings(repository = appSettingsRepository) { it.appIconStyle != before }
                assertNotEquals(before, runBlocking { appSettingsRepository.snapshot() }.appIconStyle)
                composeRule.clickTag(RipDpiTestTags.CustomizationThemedIcon)
                composeRule.awaitSettings(repository = appSettingsRepository) { it.appIconStyle == before }
            }
            step("The shape info row opens its sheet and confirm closes it") {
                composeRule.clickTag(RipDpiTestTags.CustomizationShapeInfo)
                composeRule.awaitTag(RipDpiTestTags.CustomizationShapeInfoSheet)
                composeRule.clickTag(RipDpiTestTags.CustomizationShapeInfoSheetConfirm)
                composeRule.awaitNoTag(RipDpiTestTags.CustomizationShapeInfoSheet)
            }
        }

    @Test
    fun dataTransparencyOpensAndBackReturnsToSettings() =
        run {
            step("Open data transparency") {
                composeRule.clickTag(RipDpiTestTags.SettingsDataTransparency)
                composeRule.awaitTag(RipDpiTestTags.screen(Route.DataTransparency))
            }
            step("Back returns to settings") {
                Espresso.pressBack()
                composeRule.awaitTag(RipDpiTestTags.screen(Route.Settings))
            }
        }

    @Test
    fun aboutScreenShowsSourceAndReadmeLinks() =
        run {
            step("Open about") {
                composeRule.clickTag(RipDpiTestTags.SettingsAbout)
                composeRule.awaitTag(RipDpiTestTags.screen(Route.About))
            }
            step("The source code and readme links are present") {
                composeRule.scrollToTag(RipDpiTestTags.AboutSourceCode)
                composeRule.onNodeWithTag(RipDpiTestTags.AboutSourceCode).assertIsDisplayed()
                composeRule.scrollToTag(RipDpiTestTags.AboutReadme)
                composeRule.onNodeWithTag(RipDpiTestTags.AboutReadme).assertIsDisplayed()
            }
        }
}
