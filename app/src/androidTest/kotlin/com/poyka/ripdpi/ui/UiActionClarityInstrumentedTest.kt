package com.poyka.ripdpi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsEventFiltersUiModel
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsEventsUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.GroupedEventUiModel
import com.poyka.ripdpi.activities.HistoryEventIdentityUiModel
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.screens.history.EventsSection
import com.poyka.ripdpi.ui.screens.history.FilterCard
import com.poyka.ripdpi.ui.screens.history.HistoryFilterChipsConfig
import com.poyka.ripdpi.ui.screens.home.HomeAdvisory
import com.poyka.ripdpi.ui.screens.home.HomeAdvisoryPresentation
import com.poyka.ripdpi.ui.screens.home.HomeAdvisorySeverity
import com.poyka.ripdpi.ui.screens.home.HomeAdvisorySlot
import com.poyka.ripdpi.ui.screens.settings.SettingsAppearanceSection
import com.poyka.ripdpi.ui.screens.settings.SettingsScreenActions
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device checks for production UI components with controlled inputs. */
@RunWith(AndroidJUnit4::class)
class UiActionClarityInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun homeRecoveryRunsDirectlyAndDisclosureDoesNotDuplicateIt() {
        var repairs = 0
        compose.setContent {
            ClarityFrame {
                HomeAdvisorySlot(
                    listOf(
                        HomeAdvisory(
                            title = "VPN permission needed",
                            message = "Allow a local VPN before connecting.",
                            severity = HomeAdvisorySeverity.Error,
                            presentation = HomeAdvisoryPresentation.Row,
                            tone = WarningBannerTone.Error,
                            actionLabel = "Allow VPN",
                            onClick = { repairs++ },
                            testTag = "permission-advisory",
                        ),
                        HomeAdvisory(
                            title = "Battery optimization",
                            message = "Review background settings.",
                            severity = HomeAdvisorySeverity.Info,
                            presentation = HomeAdvisoryPresentation.CompactRow,
                            tone = WarningBannerTone.Info,
                            actionLabel = "Review",
                            onClick = {},
                            testTag = "battery-advisory",
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithTag(RipDpiTestTags.HomeAdvisoryPrimaryAction).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, repairs) }
        compose.onAllNodesWithTag("battery-advisory").assertCountEquals(0)
        capture("home-primary")
        compose.onNodeWithTag(RipDpiTestTags.HomeAdvisoryMore).performClick()
        compose.onNodeWithTag("battery-advisory").assertIsDisplayed()
        compose.onAllNodesWithTag(RipDpiTestTags.HomeAdvisoryPrimaryAction).assertCountEquals(1)
        capture("home-more")
        compose.onNodeWithTag(RipDpiTestTags.HomeAdvisoryMore).performClick()
        compose.onAllNodesWithTag("battery-advisory").assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, repairs) }
    }

    @Test
    fun systemBackPreservesPersonaAndExplicitSelectionSavesOnce() {
        var persona by mutableStateOf("simple")
        val choices = mutableListOf<String>()
        compose.setContent {
            ClarityFrame {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsAppearanceSection(
                        uiState = SettingsUiState(uiPersona = persona),
                        actions =
                            personaActions {
                                choices += it
                                persona = it
                            },
                    )
                }
            }
        }
        openPersona()
        compose.onNodeWithTag(RipDpiTestTags.SettingsPersonaGuided).assertIsSelected()
        compose.runOnIdle { assertEquals(emptyList<String>(), choices) }
        capture("persona-choice")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
        compose.onAllNodesWithTag(RipDpiTestTags.SettingsPersonaSheet).assertCountEquals(0)
        compose.runOnIdle { assertEquals("simple", persona) }
        openPersona()
        compose.onNodeWithTag(RipDpiTestTags.SettingsPersonaAdvanced).performScrollTo().performClick()
        compose.onAllNodesWithTag(RipDpiTestTags.SettingsPersonaSheet).assertCountEquals(0)
        compose.runOnIdle { assertEquals(listOf("advanced"), choices) }
        openPersona()
        compose.onNodeWithTag(RipDpiTestTags.SettingsPersonaAdvanced).assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(listOf("advanced"), choices) }
    }

    @Test
    fun compactFiltersRemoveOnlyTheChosenRestrictionAtFont2Rtl() {
        var query by mutableStateOf("route")
        var source by mutableStateOf<String?>("service")
        var severity by mutableStateOf<String?>("warn")
        compose.setContent {
            ClarityFrame(dark = true, largeRtl = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FilterCard(
                        title = "Events",
                        searchValue = query,
                        searchPlaceholder = "Search",
                        onSearch = { query = it },
                        searchTestTag = "clarity-search",
                        primaryFilter =
                            HistoryFilterChipsConfig(
                                options = listOf("service", "native"),
                                selected = source,
                                onSelect = { source = it.takeUnless { selected -> selected == source } },
                                tagForOption = { "source-$it" },
                            ),
                        secondaryFilter =
                            HistoryFilterChipsConfig(
                                options = listOf("info", "warn"),
                                selected = severity,
                                onSelect = { severity = it.takeUnless { selected -> selected == severity } },
                                tagForOption = { "severity-$it" },
                            ),
                        onClearFilters = {
                            query = ""
                            source = null
                            severity = null
                        },
                    )
                }
            }
        }
        val disclosure = compose.onNodeWithTag(RipDpiTestTags.historyFilterDisclosure("clarity-search"))
        disclosure.assertIsNotSelected().assertTextEquals(string(R.string.history_filter_disclosure, 3))
        compose.onAllNodesWithTag("severity-info").assertCountEquals(0)
        capture("filters-compact-font2-rtl")
        disclosure.performScrollTo().performClick()
        disclosure.assertIsSelected()
        compose.onNodeWithTag("severity-info").performScrollTo().assertIsDisplayed()
        disclosure.performScrollTo().performClick()
        compose
            .onNodeWithTag(RipDpiTestTags.historyFilterRemove("clarity-search", "primary"))
            .performScrollTo()
            .performClick()
        compose.runOnIdle {
            assertEquals(null, source)
            assertEquals("warn", severity)
            assertEquals("route", query)
        }
        compose
            .onNodeWithTag(RipDpiTestTags.historyFilterRemove("clarity-search", "secondary"))
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithTag(RipDpiTestTags.historyFilterRemove("clarity-search", "query"))
            .performScrollTo()
            .performClick()
        disclosure.assertTextEquals(string(R.string.history_filter_disclosure, 0))
        compose.runOnIdle {
            assertEquals(null, severity)
            assertEquals("", query)
        }
    }

    @Test
    fun rawArrivalCountsAccumulateInOneGroupAndJumpClearsThem() {
        checkEventCounter(largeRtl = false)
    }

    @Test
    fun rawArrivalCountsKeepTheReaderPositionAtFont2Rtl() {
        checkEventCounter(largeRtl = true)
    }

    private fun checkEventCounter(largeRtl: Boolean) {
        var state by mutableStateOf(clarityEventHistory(0))
        var filterChanges = 0
        compose.setContent {
            ClarityFrame(dark = true, largeRtl = largeRtl) {
                EventsSection(
                    uiState = state,
                    onToggleFilter = { _, _ -> filterChanges++ },
                    onSearch = { filterChanges++ },
                    onClearFilters = { filterChanges++ },
                    onAutoScroll = { filterChanges++ },
                    onSelectEvent = {},
                    onOpenConnection = {},
                )
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("event-15")
        val row = compose.onNodeWithTag(RipDpiTestTags.historyEvent("event-15"))
        val before =
            row
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot.top
        val suffix = if (largeRtl) "font2-rtl" else "normal"
        capture("events-before-$suffix")
        compose.runOnIdle { state = clarityEventHistory(1) }
        val latest = compose.onNodeWithTag(RipDpiTestTags.HistoryEventsJumpToLatest)
        latest.assertIsDisplayed().assertTextEquals(string(R.string.history_events_jump_to_latest_count, 1))
        capture("events-count-one-$suffix")
        assertEquals(
            before,
            row
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot.top,
            1f,
        )
        compose.runOnIdle { state = clarityEventHistory(2) }
        latest.assertTextEquals(string(R.string.history_events_jump_to_latest_count, 2))
        capture("events-count-two-$suffix")
        assertEquals(
            before,
            row
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot.top,
            1f,
        )
        latest.performClick()
        compose.onNodeWithTag(RipDpiTestTags.historyEvent("arrival-0")).assertIsDisplayed()
        compose.onAllNodesWithTag(RipDpiTestTags.HistoryEventsJumpToLatest).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(0, filterChanges)
            assertEquals("info", state.events.filters.severity)
        }
    }

    private fun openPersona() {
        compose.onNodeWithTag(RipDpiTestTags.SettingsPersona).performScrollTo().performClick()
        compose.onNodeWithTag(RipDpiTestTags.SettingsPersonaSheet).assertIsDisplayed()
    }

    private fun string(
        resource: Int,
        vararg args: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *args)

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-clarity-evidence")
        assertTrue(directory.exists() || directory.mkdirs())
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "$name.png")))
    }
}

@Composable
private fun ClarityFrame(
    dark: Boolean = false,
    largeRtl: Boolean = false,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides if (largeRtl) Density(density.density, 2f) else density,
        LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        RipDpiTheme(themePreference = if (dark) "dark" else "light") {
            CompositionLocalProvider(LocalRipDpiMotion provides RipDpiMotion(animationsEnabled = false)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(RipDpiThemeTokens.colors.background)
                        .safeDrawingPadding()
                        .padding(16.dp),
                ) {
                    content()
                }
            }
        }
    }
}

private fun personaActions(onPersonaSelected: (String) -> Unit) =
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

private fun clarityEventHistory(arrivals: Int): HistoryUiState {
    fun event(id: String) =
        DiagnosticsEventUiModel(
            id = id,
            source = "service",
            severity = "info",
            message = "Local event $id",
            createdAtLabel = "12:00",
            tone = DiagnosticsTone.Neutral,
        )
    val previous = (0 until 40).map { event("event-$it") }
    val incoming = (0 until arrivals).map { event("arrival-$it") }
    val groups =
        (if (incoming.isEmpty()) emptyList() else listOf(GroupedEventUiModel(incoming.first(), arrivals, null))) +
            previous.map { GroupedEventUiModel(it, 1, null) }
    val timeline =
        incoming.map { HistoryEventIdentityUiModel(it.id, 2_000L) } +
            previous.mapIndexed { index, item -> HistoryEventIdentityUiModel(item.id, 1_000L - index) }
    return HistoryUiState(
        events =
            DiagnosticsEventsUiModel(
                filters = DiagnosticsEventFiltersUiModel(severity = "info", autoScroll = false),
                events = (incoming + previous).toImmutableList(),
            ),
        groupedEvents = groups.toImmutableList(),
        eventTimeline = timeline.toImmutableList(),
    )
}
