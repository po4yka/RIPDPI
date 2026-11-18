package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsHistorySource
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-mdpi")
class ConnectionStrategyLanesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `lanes rank the recommended candidate first and then by sites opened`() {
        val lanes = report().toStrategyLanes(finishedAt = 7L)

        assertEquals(7L, lanes?.finishedAt)
        assertEquals(
            listOf(
                StrategyLaneUiState("Fake + split", StrategyLaneOutcome.Passed, 6, 6, 141, recommended = true),
                StrategyLaneUiState("OOB byte", StrategyLaneOutcome.Passed, 6, 6, 110, recommended = false),
                StrategyLaneUiState("Disorder", StrategyLaneOutcome.Partial, 5, 6, 132, recommended = false),
                StrategyLaneUiState("Split at SNI", StrategyLaneOutcome.Blocked, 0, 6, null, recommended = false),
                StrategyLaneUiState("QUIC drop", StrategyLaneOutcome.Skipped, 0, 0, null, recommended = false),
            ),
            lanes?.lanes,
        )
    }

    @Test
    fun `lanes keep the recommended candidate and the best seven of the rest`() {
        val many = (1..12).map { candidate("c$it", "Candidate $it", succeeded = 6, latency = it.toLong()) }
        val report =
            StrategyProbeReport(
                suiteId = "s",
                tcpCandidates = many,
                recommendation = recommendation(tcpCandidateId = "c12"),
            )

        assertEquals(
            listOf("Candidate 12") + (1..7).map { "Candidate $it" },
            report.toStrategyLanes(0)?.lanes?.map { it.label },
        )
    }

    @Test
    fun `a recommended candidate that did not run is not shown as the winner`() {
        val report =
            StrategyProbeReport(
                suiteId = "s",
                tcpCandidates = listOf(candidate("a", "A", succeeded = 0, skipped = true)),
                quicCandidates = listOf(candidate("a", "Same id in the other family", succeeded = 3, total = 3)),
                recommendation = recommendation(tcpCandidateId = "a"),
            )

        assertEquals(listOf(false, false), report.toStrategyLanes(0)?.lanes?.map { it.recommended })
    }

    @Test
    fun `the newest strategy test supplies the lanes`() {
        val sessions =
            listOf(
                session("no-test", report = null, startedAt = 30L),
                session("newest", report = report(), startedAt = 20L),
                session("older", report = report(), startedAt = 10L),
            )

        assertEquals(20L, sessions.latestStrategyLanes()?.finishedAt)
    }

    @Test
    fun `a newest strategy test without candidates does not fall back to an older test`() {
        val sessions =
            listOf(
                session("cut-short", report = StrategyProbeReport(suiteId = "cut-short"), startedAt = 20L),
                session("older", report = report(), startedAt = 10L),
            )

        assertNull(sessions.latestStrategyLanes())
    }

    @Test
    fun `direct section shows each lane with its sites and time`() {
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                ConnectionDirectSection(
                    uiState = ConnectionUiState(strategyLanes = report().toStrategyLanes(finishedAt = 0L)),
                    desyncSummary = "",
                    onOpenDesyncSettings = {},
                    onOpenDnsSettings = {},
                    onRetestStrategies = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionStrategyLanes).assertIsDisplayed()
        // TalkBack reads one row as a whole: name, sites, time, and that it is the winner.
        composeRule
            .onNode(
                hasText("Fake + split") and
                    hasContentDescription(resource(R.string.diagnostics_strategy_metric_targets) + " 6/6") and
                    hasText(resource(R.string.unit_milliseconds_format, 141L)) and
                    hasStateDescription(resource(R.string.diagnostics_strategy_selection_winner)),
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Fake + split", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("5/6", useUnmergedTree = true).assertIsDisplayed()
        composeRule
            .onNodeWithText(resource(R.string.unit_milliseconds_format, 141L), useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(resource(R.string.status_diagnostics_capability_blocked), useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(resource(R.string.diagnostics_probe_status_skipped), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private fun resource(
        id: Int,
        vararg args: Any,
    ): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun report(): StrategyProbeReport =
        StrategyProbeReport(
            suiteId = "suite",
            tcpCandidates =
                listOf(
                    candidate("sni", "Split at SNI", succeeded = 0),
                    candidate("disorder", "Disorder", succeeded = 5, latency = 132),
                    candidate("fake", "Fake + split", succeeded = 6, latency = 141),
                    candidate("oob", "OOB byte", succeeded = 6, latency = 110),
                ),
            quicCandidates = listOf(candidate("quic", "QUIC drop", succeeded = 0, total = 0, skipped = true)),
            recommendation = recommendation(tcpCandidateId = "fake"),
        )

    private fun recommendation(tcpCandidateId: String) =
        StrategyProbeRecommendation(
            tcpCandidateId = tcpCandidateId,
            tcpCandidateLabel = null,
            quicCandidateId = null,
            quicCandidateLabel = null,
            rationale = "",
            recommendedProxyConfigJson = "{}",
        )

    private fun candidate(
        id: String,
        label: String,
        succeeded: Int,
        total: Int = 6,
        latency: Long? = null,
        skipped: Boolean = false,
    ) = StrategyProbeCandidateSummary(
        id = id,
        label = label,
        family = "tcp",
        outcome =
            when {
                succeeded >= total -> "success"
                succeeded > 0 -> "partial"
                else -> "failed"
            },
        rationale = "",
        succeededTargets = succeeded,
        totalTargets = total,
        weightedSuccessScore = succeeded,
        totalWeight = total,
        qualityScore = succeeded,
        averageLatencyMs = latency,
        skipped = skipped,
    )

    private fun session(
        id: String,
        report: StrategyProbeReport?,
        startedAt: Long,
    ) = DiagnosticScanSession(
        id = id,
        profileId = "profile",
        pathMode = "RAW_PATH",
        serviceMode = null,
        status = "completed",
        summary = "",
        report = DiagnosticsSessionProjection(strategyProbeReport = report),
        startedAt = startedAt - 1,
        finishedAt = startedAt,
    )
}

internal class FakeDiagnosticsHistorySource(
    private val sessions: List<DiagnosticScanSession> = emptyList(),
) : DiagnosticsHistorySource {
    override fun observeConnectionSessions(limit: Int): Flow<List<DiagnosticConnectionSession>> = flowOf(emptyList())

    override fun observeDiagnosticsSessions(limit: Int): Flow<List<DiagnosticScanSession>> = flowOf(sessions)

    override fun observeNativeEvents(limit: Int): Flow<List<DiagnosticEvent>> = flowOf(emptyList())

    override suspend fun loadConnectionDetail(sessionId: String): DiagnosticConnectionDetail? = null
}
