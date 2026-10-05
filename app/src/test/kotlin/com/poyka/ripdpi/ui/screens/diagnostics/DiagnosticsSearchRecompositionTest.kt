package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsEventsUiModel
import com.poyka.ripdpi.activities.DiagnosticsPerformanceUiModel
import com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiState
import com.poyka.ripdpi.activities.toScreenUiState
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DiagnosticsSearchRecompositionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `publisher updates during profile search never compare historical event payloads in Compose`() {
        val comparisons = AtomicInteger()
        val profile =
            DiagnosticsProfileOptionUiModel(
                "resolver",
                "Resolver check",
                "bundled",
                family = DiagnosticProfileFamily.GENERAL,
            )
        val state =
            mutableStateOf(
                DiagnosticsUiState(
                    selectedSection = DiagnosticsSection.Scan,
                    scan =
                        DiagnosticsScanUiModel(
                            profiles = listOf(profile).toImmutableList(),
                            selectedProfileId = profile.id,
                            selectedProfile = profile,
                        ),
                    events = events(comparisons),
                    performance = performance(0),
                ),
                neverEqualPolicy(),
            )
        var selections = 0
        var probes = 0
        composeRule.setContent {
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState = state.value.toScreenUiState(),
                    pagerState = rememberPagerState(initialPage = DiagnosticsSection.Scan.ordinal) { 3 },
                    actions =
                        DiagnosticsScreenActions(
                            onSelectProfile = { selections++ },
                            onRunRawScan = { probes++ },
                            onRunInPathScan = { probes++ },
                        ),
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchOpen).performScrollTo().performClick()
        comparisons.set(0)
        "Resolver".indices.forEach { index ->
            composeRule.runOnIdle {
                state.value =
                    state.value.copy(events = events(comparisons), performance = performance(index + 1L))
            }
            composeRule.onNodeWithTag(RipDpiTestTags.profileSearchQuery(scope)).performTextReplacement(
                "Resolver".take(index + 1),
            )
        }
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchQuery(scope)).assertTextContains("Resolver")
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchClear(scope)).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchFilter(scope, "GENERAL")).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchCancel(scope)).performScrollTo().performClick()
        assertEquals(
            "Historical event payloads must not reach a rendered-state equality boundary",
            0,
            comparisons.get(),
        )
        assertEquals(0, selections)
        assertEquals(0, probes)
    }

    private fun events(comparisons: AtomicInteger) =
        DiagnosticsEventsUiModel(
            events =
                CountedEvents(
                    (0 until 250)
                        .map { index ->
                            DiagnosticsEventUiModel(
                                id = "event-$index",
                                source = "test",
                                severity = "INFO",
                                message = String(("message-$index-" + "x".repeat(8192)).toCharArray()),
                                createdAtLabel = "12:00",
                                tone = DiagnosticsTone.Info,
                            )
                        }.toImmutableList(),
                    comparisons,
                ),
        )

    private fun performance(sequence: Long) =
        DiagnosticsPerformanceUiModel(
            buildSequence = sequence,
            totalDurationMillis = 0.0,
            eventMappingDurationMillis = 0.0,
            resolveDurationMillis = 0.0,
            overviewDurationMillis = 0.0,
            scanDurationMillis = 0.0,
            liveDurationMillis = 0.0,
            sessionsDurationMillis = 0.0,
            approachesDurationMillis = 0.0,
            eventsDurationMillis = 0.0,
            shareDurationMillis = 0.0,
            telemetryCount = 1,
            nativeEventCount = 250,
            sessionCount = 0,
        )

    private val scope = RipDpiTestTags.DiagnosticProfileSearch
}

private class CountedEvents(
    private val values: ImmutableList<DiagnosticsEventUiModel>,
    private val comparisons: AtomicInteger,
) : ImmutableList<DiagnosticsEventUiModel> by values {
    override fun equals(other: Any?): Boolean {
        comparisons.incrementAndGet()
        return values == other
    }

    override fun hashCode(): Int = values.hashCode()
}
