package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionCheckRunner
import com.poyka.ripdpi.core.detection.DetectionProgress
import com.poyka.ripdpi.core.detection.DetectionRunnerConfig
import com.poyka.ripdpi.core.detection.DetectionStage
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.RecommendationDestination
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.SocketTimeoutException

private class FakeProbeRunner(
    private val result: DetectionCheckResult? = null,
    private val error: Throwable? = null,
    private val progress: List<DetectionProgress> = emptyList(),
    private val gate: CompletableDeferred<Unit>? = null,
    private val nonCancellable: Boolean = false,
) : DetectionProbeRunner {
    override val packageName: String = "com.example"
    var runCount = 0

    override suspend fun runDetectionCheck(
        runner: DetectionCheckRunner,
        config: DetectionRunnerConfig,
        onProgress: (DetectionProgress) -> Unit,
    ): DetectionCheckResult {
        runCount += 1
        progress.forEach(onProgress)
        if (nonCancellable) withContext(NonCancellable) { gate?.await() } else gate?.await()
        error?.let { throw it }
        return result ?: detectionResult(Verdict.NOT_DETECTED)
    }
}

private class NoopDetectionCheckRunner : DetectionCheckRunner {
    override suspend fun run(
        context: android.content.Context,
        config: DetectionRunnerConfig,
        onProgress: (suspend (DetectionProgress) -> Unit)?,
    ): DetectionCheckResult = detectionResult(Verdict.NOT_DETECTED)
}

private class NoopRoutingCatalog : RoutingProtectionCatalogService {
    override fun snapshot(): RoutingProtectionCatalogSnapshot = RoutingProtectionCatalogSnapshot()
}

@OptIn(ExperimentalCoroutinesApi::class)
class DetectionRunCoordinatorTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Initialized in @Before (after MainDispatcherRule swaps Main): Dispatchers.Main is then the
    // rule's UnconfinedTestDispatcher, so launches run eagerly to first suspension on a single
    // shared scheduler, mirroring viewModelScope under test.
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.Main)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    private fun coordinator(
        flow: MutableStateFlow<DetectionCheckUiState>,
        probe: DetectionProbeRunner,
        scope: CoroutineScope = testScope,
        settingsRepository: FakeDetectionAppSettingsRepository = FakeDetectionAppSettingsRepository(),
        onSaveHistory: suspend (DetectionCheckResult, Int) -> Unit = { _, _ -> },
        onRefreshCommunity: () -> Unit = {},
        onTerminalResult: suspend (DetectionCheckEffect) -> Unit = {},
    ): DetectionRunCoordinator =
        DetectionRunCoordinator(
            scope = scope,
            reducer = DetectionCheckStateReducer(flow),
            appSettingsRepository = settingsRepository,
            detectionCheckRunner = NoopDetectionCheckRunner(),
            probeRunner = probe,
            routingProtectionCatalogService = NoopRoutingCatalog(),
            stringResolver = RecordingStringResolver(),
            onSaveHistory = onSaveHistory,
            onRefreshCommunity = onRefreshCommunity,
            onTerminalResult = onTerminalResult,
        )

    @Test
    fun `equal completed runs each deliver one result and retained state delivers none`() {
        val effects = mutableListOf<DetectionCheckEffect>()
        val flow = MutableStateFlow(DetectionCheckUiState(result = detectionResult()))
        val coordinator = coordinator(flow, FakeProbeRunner(), onTerminalResult = { effects += it })
        assertTrue(effects.isEmpty())
        repeat(2) { coordinator.startCheck() }
        assertEquals(
            listOf(
                DetectionCheckEffect.CheckCompleted(Verdict.NOT_DETECTED),
                DetectionCheckEffect.CheckCompleted(Verdict.NOT_DETECTED),
            ),
            effects,
        )
    }

    @Test
    fun `each failed run emits one terminal error while cancellation stays quiet`() {
        val effects = mutableListOf<DetectionCheckEffect>()
        val flow = MutableStateFlow(DetectionCheckUiState())
        val failedCoordinator =
            coordinator(
                flow,
                FakeProbeRunner(error = RuntimeException("Failed")),
                onTerminalResult = { effects += it },
            )
        repeat(2) { failedCoordinator.startCheck() }
        assertEquals(listOf(DetectionCheckEffect.CheckFailed, DetectionCheckEffect.CheckFailed), effects)
        coordinator(
            flow,
            FakeProbeRunner(error = CancellationException("Cancelled")),
            onTerminalResult = { effects += it },
        ).startCheck()
        assertEquals(2, effects.size)
    }

    @Test
    fun `cancelled non-cooperative probe cannot deliver or save a late result`() {
        val effects = mutableListOf<DetectionCheckEffect>()
        val flow = MutableStateFlow(DetectionCheckUiState())
        val gate = CompletableDeferred<Unit>()
        var saves = 0
        val coordinator =
            coordinator(
                flow,
                FakeProbeRunner(gate = gate, nonCancellable = true),
                onSaveHistory = { _, _ -> saves += 1 },
                onTerminalResult = { effects += it },
            )
        coordinator.startCheck()
        coordinator.stopCheck()
        gate.complete(Unit)
        assertFalse(flow.value.isRunning)
        assertNull(flow.value.result)
        assertTrue(effects.isEmpty())
        assertEquals(0, saves)
    }

    @Test
    fun `feedback disabled during a run cannot replay after reenabling`() =
        runTest {
            val effects = mutableListOf<DetectionCheckEffect>()
            val flow = MutableStateFlow(DetectionCheckUiState())
            val settings = FakeDetectionAppSettingsRepository()
            val gate = CompletableDeferred<Unit>()
            val coordinator =
                coordinator(
                    flow,
                    FakeProbeRunner(gate = gate),
                    settingsRepository = settings,
                    onTerminalResult = { effects += it },
                )
            coordinator.startCheck()
            settings.update { setHapticFeedbackDisabled(true) }
            gate.complete(Unit)
            assertTrue(effects.isEmpty())
            assertTrue(flow.value.result != null)
            settings.update { setHapticFeedbackDisabled(false) }
            assertTrue(effects.isEmpty())
            coordinator.startCheck()
            assertEquals(listOf(DetectionCheckEffect.CheckCompleted(Verdict.NOT_DETECTED)), effects)
        }

    @Test
    fun `recommendation generation preserves settings without automatic fixes`() =
        runTest {
            val initial =
                AppSettings
                    .newBuilder()
                    .setDnsMode("plain")
                    .setFullTunnelMode(false)
                    .setEntropyMode(0)
                    .setStrategyEvolution(false)
                    .build()
            val settings = FakeDetectionAppSettingsRepository(initial)
            val result =
                detectionResult(Verdict.DETECTED).copy(
                    directSigns =
                        CategoryResult(
                            name = "Direct",
                            detected = true,
                            findings = emptyList(),
                            evidence =
                                listOf(
                                    EvidenceItem(
                                        source = EvidenceSource.NETWORK_CAPABILITIES,
                                        detected = true,
                                        confidence = EvidenceConfidence.HIGH,
                                        description = "TRANSPORT_VPN",
                                    ),
                                ),
                        ),
                )
            val flow = MutableStateFlow(DetectionCheckUiState())

            coordinator(flow, FakeProbeRunner(result), settingsRepository = settings).startCheck()
            advanceUntilIdle()

            assertEquals(initial, settings.snapshot())
            assertEquals(0, settings.updateCount)
            assertTrue(
                flow.value.recommendations.any {
                    it.destination == RecommendationDestination.MODE_SETTINGS
                },
            )
        }

    @Test
    fun `startCheck is no-op while already running`() {
        val flow = MutableStateFlow(DetectionCheckUiState(isRunning = true))
        val probe = FakeProbeRunner()
        coordinator(flow, probe).startCheck()
        assertEquals(0, probe.runCount)
    }

    @Test
    fun `startCheck resets state before probing and forwards progress to terminal`() {
        val flow =
            MutableStateFlow(
                DetectionCheckUiState(
                    result = detectionResult(),
                    error = "stale",
                    stealthScore = 50,
                ),
            )
        val probe =
            FakeProbeRunner(
                result = detectionResult(Verdict.NOT_DETECTED),
                progress =
                    listOf(
                        DetectionProgress(stage = DetectionStage.GEO_IP, label = "Geo", detail = "checking"),
                    ),
            )
        coordinator(flow, probe).startCheck()

        assertFalse(flow.value.isRunning)
        assertNull(flow.value.error)
        assertNull(flow.value.progress)
        assertTrue(flow.value.result != null)
    }

    @Test
    fun `successful run invokes side effects once before the terminal emission`() {
        val flow = MutableStateFlow(DetectionCheckUiState())
        var saveCount = 0
        var refreshCount = 0
        var savedBeforeTerminal = false
        coordinator(
            flow,
            FakeProbeRunner(result = detectionResult(Verdict.NOT_DETECTED)),
            onSaveHistory = { _, _ ->
                saveCount += 1
                savedBeforeTerminal = flow.value.result == null
            },
            onRefreshCommunity = { refreshCount += 1 },
        ).startCheck()

        assertEquals(1, saveCount)
        assertEquals(1, refreshCount)
        assertTrue(savedBeforeTerminal)
        assertTrue(flow.value.result != null)
        assertTrue(flow.value.stealthScore != null)
    }

    @Test
    fun `non-cancellation failure with null message uses generic fallback`() {
        val flow = MutableStateFlow(DetectionCheckUiState())
        coordinator(flow, FakeProbeRunner(error = RuntimeException())).startCheck()

        assertFalse(flow.value.isRunning)
        assertNull(flow.value.progress)
        assertEquals("string:${R.string.error_user_generic}", flow.value.error)
    }

    @Test
    fun `non-cancellation failure shows a localized message instead of raw text`() {
        val flow = MutableStateFlow(DetectionCheckUiState())
        coordinator(flow, FakeProbeRunner(error = SocketTimeoutException("raw socket text"))).startCheck()

        assertEquals("string:${R.string.error_user_timeout}", flow.value.error)
    }

    @Test
    fun `cancellation is not converted to an error state`() {
        val flow = MutableStateFlow(DetectionCheckUiState())
        coordinator(flow, FakeProbeRunner(error = CancellationException("cancel"))).startCheck()

        assertNull(flow.value.error)
    }

    @Test
    fun `stopCheck cancels an in-flight run and clears running and progress`() {
        val flow = MutableStateFlow(DetectionCheckUiState())
        val gate = CompletableDeferred<Unit>()
        val coordinator = coordinator(flow, FakeProbeRunner(gate = gate))

        coordinator.startCheck()
        assertTrue(flow.value.isRunning)

        coordinator.stopCheck()

        assertFalse(flow.value.isRunning)
        assertNull(flow.value.progress)
    }

    @Test
    fun `stopCheck leaves an existing result untouched`() {
        // stopCheck must not mutate result; with no active run the prior result survives.
        val existing = detectionResult(Verdict.NOT_DETECTED)
        val flow = MutableStateFlow(DetectionCheckUiState(result = existing))
        val coordinator = coordinator(flow, FakeProbeRunner())

        coordinator.stopCheck()

        assertEquals(existing, flow.value.result)
        assertFalse(flow.value.isRunning)
        assertNull(flow.value.progress)
    }
}
