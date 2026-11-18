package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
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
        assertEquals(0, lanes?.hiddenCount)
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

        val lanes = report.toStrategyLanes(0)

        assertEquals(listOf("Candidate 12") + (1..7).map { "Candidate $it" }, lanes?.lanes?.map { it.label })
        assertEquals(4, lanes?.hiddenCount)
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
    fun `a strategy test without candidates gives no lanes`() {
        val cutShort = session("cut-short", report = StrategyProbeReport(suiteId = "cut-short"), startedAt = 20L)

        assertNull(cutShort.toStrategyLanes(currentNetworkScopeKey = null))
    }

    @Test
    fun `lanes are marked only when the test ran on another known network`() {
        val atHome = session("home", report = report(), startedAt = 20L).copy(networkScopeKey = "home")

        assertEquals(true, atHome.toStrategyLanes("mobile")?.otherNetwork)
        assertEquals(false, atHome.toStrategyLanes("home")?.otherNetwork)
        // No network now, or a row from before the network was recorded: not known, so not marked.
        assertEquals(false, atHome.toStrategyLanes(null)?.otherNetwork)
        assertEquals(false, atHome.copy(networkScopeKey = null).toStrategyLanes("mobile")?.otherNetwork)
    }

    @Test
    fun `a move to another network marks the lanes and the way back clears the mark`() =
        runTest {
            val home = fingerprint(dns = "192.0.2.1")
            val mobile = fingerprint(dns = "192.0.2.2")
            val handovers = MutableSharedFlow<NetworkHandoverEvent>()
            val testAtHome = strategyTestSession(finishedAt = 9L).copy(networkScopeKey = home.scopeKey())
            val history = FakeDiagnosticsHistorySource(testAtHome)
            val marks = mutableListOf<Boolean?>()
            val collector =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    strategyLanesSource(history, network = home, handovers = handovers)
                        .observe()
                        .collect { marks += it?.otherNetwork }
                }

            handovers.emit(NetworkHandoverEvent(home, mobile, "transport_switch", occurredAt = 1L))
            // A moment without a network keeps the mark of the last known one.
            handovers.emit(NetworkHandoverEvent(mobile, null, "connectivity_loss", occurredAt = 2L))
            handovers.emit(NetworkHandoverEvent(null, home, "transport_switch", occurredAt = 3L))
            runCurrent()

            assertEquals(listOf(false, true, false), marks)
            collector.cancel()
        }

    @Test
    fun `lanes from another network say so`() {
        composeRule.setContent {
            RipDpiTheme { ConnectionStrategyLanes(report().toStrategyLanes(0)!!.copy(otherNetwork = true)) }
        }

        composeRule
            .onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.config_strategy_lanes_other_network))
            .assertIsDisplayed()
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
                    hasContentDescription(resource(R.string.config_strategy_lanes_sites, 6, 6)) and
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
    latestStrategyTest: DiagnosticScanSession? = null,
    private val latestStrategyTestFlow: Flow<DiagnosticScanSession?> = flowOf(latestStrategyTest),
) : DiagnosticsHistorySource {
    override fun observeConnectionSessions(limit: Int): Flow<List<DiagnosticConnectionSession>> = flowOf(emptyList())

    override fun observeDiagnosticsSessions(limit: Int): Flow<List<DiagnosticScanSession>> = flowOf(emptyList())

    override fun observeLatestStrategyTest(): Flow<DiagnosticScanSession?> = latestStrategyTestFlow

    override fun observeNativeEvents(limit: Int): Flow<List<DiagnosticEvent>> = flowOf(emptyList())

    override suspend fun loadConnectionDetail(sessionId: String): DiagnosticConnectionDetail? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun strategyLanesSource(
    history: DiagnosticsHistorySource = FakeDiagnosticsHistorySource(),
    network: NetworkFingerprint? = null,
    handovers: MutableSharedFlow<NetworkHandoverEvent> = MutableSharedFlow(),
): ConnectionStrategyLanesSource {
    val dispatcher = UnconfinedTestDispatcher()
    return ConnectionStrategyLanesSource(
        history = history,
        fingerprints =
            object : NetworkFingerprintProvider {
                override fun capture(): NetworkFingerprint? = network
            },
        handovers =
            object : NetworkHandoverMonitor {
                override val events = handovers
            },
        dispatchers = AppCoroutineDispatchers(default = dispatcher, io = dispatcher, main = dispatcher),
    )
}

private fun fingerprint(dns: String) =
    NetworkFingerprint(
        transport = "wifi",
        networkValidated = true,
        captivePortalDetected = false,
        privateDnsMode = "off",
        dnsServers = listOf(dns),
    )

internal fun strategyTestSession(finishedAt: Long): DiagnosticScanSession =
    DiagnosticScanSession(
        id = "strategy-test",
        profileId = "automatic-probing",
        pathMode = "RAW_PATH",
        serviceMode = null,
        status = "completed",
        summary = "",
        report =
            DiagnosticsSessionProjection(
                strategyProbeReport =
                    StrategyProbeReport(
                        suiteId = "quick_v1",
                        tcpCandidates =
                            listOf(
                                StrategyProbeCandidateSummary(
                                    id = "split",
                                    label = "Split",
                                    family = "tcp",
                                    outcome = "success",
                                    rationale = "",
                                    succeededTargets = 3,
                                    totalTargets = 3,
                                    weightedSuccessScore = 3,
                                    totalWeight = 3,
                                    qualityScore = 3,
                                ),
                            ),
                    ),
            ),
        startedAt = finishedAt - 1,
        finishedAt = finishedAt,
    )
