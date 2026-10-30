package com.poyka.ripdpi.ui.navigation

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.components.navigation.LocalShownBesideList
import com.poyka.ripdpi.ui.components.navigation.RipDpiListDetailLayout
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.screens.settings.LocalSelectedSettingsDetail
import com.poyka.ripdpi.ui.screens.settings.SettingsDetailPlaceholder
import com.poyka.ripdpi.ui.screens.settings.SettingsScreen
import com.poyka.ripdpi.ui.screens.settings.SettingsScreenActions
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SettingsListDetailNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var navController: NavHostController
    private lateinit var backDispatcher: OnBackPressedDispatcher

    private val settingsScreen = RipDpiTestTags.screen(Route.Settings)

    @Test
    @Config(qualifiers = "w1300dp-h900dp")
    fun expandedWindowShowsTheListBesideTheSelectedDetail() {
        showSettingsGraph()

        composeRule.onNodeWithTag(settingsScreen).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsDetailPlaceholder).assertExists()

        composeRule.onNodeWithTag(RipDpiTestTags.SettingsDnsSettings).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(settingsScreen).assertExists()
        composeRule.onNodeWithTag(DNS_TAG).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsDnsSettings).assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsSubscriptionStatus).assert(!isSelected())
        composeRule.onNode(hasContentDescription(BACK_LABEL)).assertDoesNotExist()

        // Another item replaces the detail; back then leaves the detail, not the previous item.
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsSubscriptionStatus).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(STATUS_TAG).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsSubscriptionStatus).assertIsSelected()

        composeRule.runOnIdle { backDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertTrue(currentIs<Route.Settings>()) }
        composeRule.onNodeWithTag(settingsScreen).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsDetailPlaceholder).assertExists()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun compactWindowShowsOnePaneAndSelectingNavigates() {
        showSettingsGraph()

        composeRule.onNodeWithTag(settingsScreen).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsDetailPlaceholder).assertDoesNotExist()

        composeRule.onNodeWithTag(RipDpiTestTags.SettingsDnsSettings).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DNS_TAG).assertExists()
        composeRule.onNodeWithTag(settingsScreen).assertDoesNotExist()
        composeRule.onNode(hasContentDescription(BACK_LABEL)).assertExists()

        composeRule.runOnIdle { backDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertTrue(currentIs<Route.Settings>()) }
        composeRule.onNodeWithTag(settingsScreen).assertExists()
        composeRule.onNodeWithTag(DNS_TAG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1300dp-h900dp")
    fun detailOpenedDirectlyKeepsOnePaneOnExpandedWindows() {
        showSettingsGraph()

        // A launch route or deep link has no Settings entry below the detail.
        composeRule.runOnIdle {
            navController.navigate(Route.DnsSettings) { popUpTo<Route.Settings> { inclusive = true } }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DNS_TAG).assertExists()
        composeRule.onNodeWithTag(settingsScreen).assertDoesNotExist()
        composeRule.onNode(hasContentDescription(BACK_LABEL)).assertExists()
    }

    private inline fun <reified T : Any> currentIs(): Boolean =
        requireNotNull(navController.currentDestination).hasRoute<T>()

    private fun showSettingsGraph() {
        composeRule.setContent {
            RipDpiTheme {
                backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                navController = rememberNavController()
                val entry by navController.currentBackStackEntryAsState()
                val listDetail = navController.settingsListDetailFor(entry)
                // Same wiring as RipDpiNavHost, with the Settings list and details as plain screens.
                RipDpiListDetailLayout(
                    showList = listDetail != null,
                    listPane = {
                        CompositionLocalProvider(LocalSelectedSettingsDetail provides listDetail?.selected) {
                            SettingsList(navController)
                        }
                    },
                    detailPane = {
                        NavHost(navController = navController, startDestination = SettingsGraph) {
                            navigation<SettingsGraph>(startDestination = Route.Settings) {
                                composable<Route.Settings> {
                                    if (LocalShownBesideList.current) {
                                        SettingsDetailPlaceholder()
                                    } else {
                                        SettingsList(navController)
                                    }
                                }
                                composable<Route.DnsSettings> { DetailScreen(DNS_TAG, navController) }
                                composable<Route.SubscriptionStatus> { DetailScreen(STATUS_TAG, navController) }
                            }
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun SettingsList(navController: NavHostController) {
        SettingsScreen(
            uiState = SettingsUiState(),
            actions = listActions(navController),
            permissionSummary = PermissionSummaryUiState(),
        )
    }

    @Composable
    private fun DetailScreen(
        tag: String,
        navController: NavHostController,
    ) {
        Column(Modifier.fillMaxSize().testTag(tag)) {
            RipDpiTopAppBar(
                title = tag,
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = { navController.popBackStack() },
                navigationContentDescription = BACK_LABEL,
            )
        }
    }

    private fun listActions(navController: NavHostController): SettingsScreenActions =
        SettingsScreenActions(
            onOpenProfileUtility = {},
            onOpenDnsSettings = { navController.navigateSettingsDetail(Route.DnsSettings) },
            onOpenSubscriptionStatus = { navController.navigateSettingsDetail(Route.SubscriptionStatus) },
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

    private companion object {
        const val DNS_TAG = "settings-detail-dns"
        const val STATUS_TAG = "settings-detail-status"
        const val BACK_LABEL = "test-back"
    }
}
