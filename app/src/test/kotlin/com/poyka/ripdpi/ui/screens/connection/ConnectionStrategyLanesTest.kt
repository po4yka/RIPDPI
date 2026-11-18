package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.StubDiagnosticsScanController
import com.poyka.ripdpi.activities.StubDiagnosticsTimelineSource
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsHistorySource
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.StrategyCandidateApplier
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeLiveProgress
import com.poyka.ripdpi.diagnostics.StrategyProbeProgressLane
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            lanes?.lanes?.map { it.copy(id = "") },
        )
        assertEquals(listOf("fake", "oob", "disorder", "sni", "quic"), lanes?.lanes?.map { it.id })
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
    fun `the strategy in use follows the winner and survives the lane cap`() {
        val report =
            StrategyProbeReport(
                suiteId = "s",
                tcpCandidates =
                    (1..MaxStrategyLanes + 2).map { index ->
                        candidate("c$index", "Candidate $index", succeeded = 6, latency = index.toLong())
                    } +
                        candidate("baseline_current", "Current strategy", succeeded = 0) +
                        candidate("winner", "Winner", succeeded = 6, latency = 99),
                recommendation = recommendation(tcpCandidateId = "winner"),
            )

        val lanes = report.toStrategyLanes(0)!!.lanes

        assertEquals(listOf("Winner", "Current strategy", "Candidate 1"), lanes.take(3).map { it.label })
        assertEquals(listOf(false, true, false), lanes.take(3).map { it.current })
    }

    @Test
    fun `a family names a lane only when it is the only one of its family`() {
        val report =
            StrategyProbeReport(
                suiteId = "s",
                tcpCandidates =
                    listOf(
                        candidate("baseline_current", "Current strategy", succeeded = 6).copy(family = "baseline"),
                        candidate("baseline_plain_direct", "Plain direct", succeeded = 6).copy(family = "baseline"),
                        candidate("split_host", "Split Host", succeeded = 6).copy(family = "split"),
                        candidate("parser_only", "Parser-only", succeeded = 6).copy(family = "parser"),
                        candidate("parser_hostpad", "Parser + Host Pad", succeeded = 6).copy(family = "parser"),
                    ),
            )

        val families = report.toStrategyLanes(0)!!.lanes.associate { it.label to it.family }

        assertEquals(
            mapOf(
                // The strategy in use has a name of its own, whatever its family.
                "Current strategy" to "baseline_current",
                "Plain direct" to null,
                "Split Host" to "split",
                "Parser-only" to null,
                "Parser + Host Pad" to null,
            ),
            families,
        )
    }

    @Test
    fun `a lane shows the translated family name and falls back to the engine label`() {
        val lanes =
            report().toStrategyLanes(0)!!.let { state ->
                state.copy(
                    lanes =
                        persistentListOf(
                            state.lanes[0].copy(label = "Split Host", family = "split"),
                            state.lanes[1].copy(label = "Engine label", family = "family-the-app-does-not-know"),
                        ),
                )
            }
        composeRule.setContent { RipDpiTheme { ConnectionStrategyLanes(lanes) } }

        composeRule.onNodeWithText(resource(R.string.diagnostics_strategy_family_host_split), true).assertIsDisplayed()
        composeRule.onNodeWithText("Engine label", useUnmergedTree = true).assertIsDisplayed()
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
                    strategyLanesSource(
                        history,
                        network = home,
                        handovers = handovers,
                        dispatcher = UnconfinedTestDispatcher(testScheduler),
                    ).observe()
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

    @Test
    fun `a blocked lane stops at the stage most of its sites stopped at`() {
        // The engine's own details: the attempt it reports, and the stage that attempt failed at.
        fun tls(
            host: String,
            stage: String,
            label: String = "Split at SNI",
        ) = ProbeResult(
            probeType = "strategy_https",
            target = "$label · $host",
            outcome = "tls_handshake_failed",
            details =
                listOf(
                    ProbeDetail("tlsReportedAttempt", "tls12"),
                    ProbeDetail("tls13FailureStage", "tcp_connect"),
                    ProbeDetail("tls12FailureStage", stage),
                ),
        )
        val blockpage =
            ProbeResult(
                "strategy_http",
                "Split at SNI · e.example",
                "http_blockpage",
                listOf(ProbeDetail("failureStage", "http")),
            )
        val results =
            listOf(
                tls("a.example", "tls_handshake"),
                tls("b.example", "tls_handshake"),
                tls("c.example", "tcp_connect"),
                tls("d.example", "none"),
                blockpage,
                // A candidate whose label only starts the same, and a passing candidate, name no gate here.
                tls("a.example", "tcp_connect", label = "Split at SNI · v2"),
                tls("b.example", "tcp_connect", label = "Split at SNI · v2"),
                tls("c.example", "tcp_connect", label = "Split at SNI · v2"),
                tls("a.example", "tcp_connect", label = "Disorder"),
            )

        val stages = report().toStrategyLanes(0, results)!!.lanes.associate { it.label to it.stage }

        assertEquals(StrategyLaneStage.Tls, stages["Split at SNI"])
        assertNull(stages["Disorder"])
        assertNull(
            report()
                .toStrategyLanes(0)!!
                .lanes
                .first { it.label == "Split at SNI" }
                .stage,
        )
    }

    @Test
    fun `a running test shows the candidates it has reached and gives way to the stored result`() =
        runTest {
            val timeline = StubDiagnosticsTimelineSource()
            val stored = session("stored", report(), startedAt = 10L)
            val states = mutableListOf<StrategyLanesUiState?>()
            val collector =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    strategyLanesSource(
                        FakeDiagnosticsHistorySource(stored),
                        timeline = timeline,
                        dispatcher = UnconfinedTestDispatcher(testScheduler),
                    ).observe().collect(states::add)
                }

            // The engine's three reports: started (no sites, no outcome), not run (no sites, an outcome),
            // and finished (its site count).
            fun step(
                id: String,
                completed: Int,
                succeeded: Int = 0,
                total: Int = 0,
                outcome: String? = null,
            ) = ScanProgress(
                sessionId = "run",
                phase = "strategy",
                completedSteps = completed,
                totalSteps = 4,
                message = "",
                latestProbeOutcome = outcome,
                strategyProbeProgress =
                    StrategyProbeLiveProgress(StrategyProbeProgressLane.TCP, 1, 2, id, id, succeeded, total),
            )
            timeline.activeScanProgress.value = step("first", completed = 0)
            timeline.activeScanProgress.value =
                step("first", completed = 1, succeeded = 6, total = 6, outcome = "success")
            timeline.activeScanProgress.value = step("unfit", completed = 2, outcome = "not_applicable")
            timeline.activeScanProgress.value = step("second", completed = 2)
            // The next test follows with no gap; it must not inherit the lanes of this one.
            timeline.activeScanProgress.value =
                step("third", completed = 1, succeeded = 1, total = 6, outcome = "partial").copy(sessionId = "next")
            timeline.activeScanProgress.value = null
            runCurrent()

            assertEquals(5, states.first()?.lanes?.size)
            assertEquals(
                listOf("first" to StrategyLaneOutcome.Running),
                states[1]?.lanes?.map { it.label to it.outcome },
            )
            // A candidate that did not run adds no lane.
            assertEquals(listOf("first"), states[3]?.lanes?.map { it.label })
            val reached = states[4]!!
            assertEquals(
                listOf("first" to StrategyLaneOutcome.Passed, "second" to StrategyLaneOutcome.Running),
                reached.lanes.map { it.label to it.outcome },
            )
            assertEquals(StrategyTestProgress(2, 4), reached.progress)
            assertEquals(
                listOf("third" to StrategyLaneOutcome.Partial),
                states[5]?.lanes?.map { it.label to it.outcome },
            )
            assertNull(states.last()?.progress)
            assertEquals(5, states.last()?.lanes?.size)
            collector.cancel()
        }

    @Test
    fun `the test action starts the bundled strategy test and stops only that test`() =
        runTest {
            val scans = StubDiagnosticsScanController()
            val timeline = StubDiagnosticsTimelineSource()
            val source = strategyLanesSource(scans = scans, timeline = timeline)

            assertTrue(source.toggleTest())
            assertEquals(listOf(ScanPathMode.RAW_PATH to "automatic-probing"), scans.startedRequests)

            // The stub starts every scan as "session", so this progress is the test this screen started.
            timeline.activeScanProgress.value = ScanProgress("session", "dns", 0, 4, "")
            assertTrue(source.toggleTest())
            assertEquals(1, scans.cancelCount)

            // A scan of another kind, started elsewhere, is left alone and handed to Diagnostics.
            timeline.activeScanProgress.value = ScanProgress("other", "dns", 0, 4, "")
            assertFalse(source.toggleTest())
            assertEquals(1, scans.cancelCount)
            assertEquals(1, scans.startedRequests.size)
        }

    @Test
    fun `a scan of another kind does not replace the stored lanes`() =
        runTest {
            val timeline = StubDiagnosticsTimelineSource()
            timeline.activeScanProgress.value = ScanProgress("other", "dns", 1, 4, "")
            val stored = session("stored", report(), startedAt = 10L)

            val lanes =
                strategyLanesSource(
                    FakeDiagnosticsHistorySource(stored),
                    timeline = timeline,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                ).observe().first()

            assertNull(lanes?.progress)
            assertEquals(5, lanes?.lanes?.size)
        }

    @Test
    fun `a start that the controller refuses is left to the diagnostics screen`() =
        runTest {
            val scans = StubDiagnosticsScanController().apply { startFailure = IllegalStateException("consent") }
            val effects = mutableListOf<ConnectionEffect>()
            val actions =
                ConnectionStrategyLaneActions(
                    strategyLanesSource(scans = scans),
                    this,
                    { effects.add(it) },
                ) { "failed" }

            actions.toggleTest()
            actions.apply("missing")
            runCurrent()

            assertEquals(listOf(ConnectionEffect.OpenStrategyTest, ConnectionEffect.Message("failed")), effects)
        }

    @Test
    fun `a tapped lane shows its sites and offers its strategy only when it can be applied`() {
        val applied = mutableListOf<String>()
        composeRule.setContent {
            val lanes = report().toStrategyLanes(0)!!
            val marked = lanes.lanes.map { it.copy(applicable = it.id == "oob") }.toImmutableList()
            RipDpiTheme { ConnectionStrategyLanes(lanes.copy(lanes = marked), onApplyLane = applied::add) }
        }
        val apply = resource(R.string.strategy_tuner_apply)

        composeRule.onNodeWithText("OOB byte", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionStrategyLaneDetail).assertIsDisplayed()
        composeRule.onNodeWithText(apply).performClick()
        assertEquals(listOf("oob"), applied)

        composeRule.onNodeWithText("Split at SNI", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText(apply).assertDoesNotExist()
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
    scans: StubDiagnosticsScanController = StubDiagnosticsScanController(),
    timeline: StubDiagnosticsTimelineSource = StubDiagnosticsTimelineSource(),
    settings: AppSettingsRepository = FakeAppSettingsRepository(),
    // A test that collects the lanes passes a dispatcher on its own scheduler.
    dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
): ConnectionStrategyLanesSource =
    ConnectionStrategyLanesSource(
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
        scans = scans,
        timeline = timeline,
        applier = StrategyCandidateApplier(settings),
    )

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
