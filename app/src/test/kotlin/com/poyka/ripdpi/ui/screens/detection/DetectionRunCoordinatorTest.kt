package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionCheckRunner
import com.poyka.ripdpi.core.detection.DetectionProgress
import com.poyka.ripdpi.core.detection.DetectionRunnerConfig
import com.poyka.ripdpi.core.detection.DetectionStage
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private class FakeProbeRunner(
    private val result: DetectionCheckResult? = null,
    private val error: Throwable? = null,
    private val progress: List<DetectionProgress> = emptyList(),
    private val gate: CompletableDeferred<Unit>? = null,
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
        gate?.await()
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
        onSaveHistory: suspend (DetectionCheckResult, Int) -> Unit = { _, _ -> },
        onRefreshCommunity: () -> Unit = {},
    ): DetectionRunCoordinator =
        DetectionRunCoordinator(
            scope = scope,
            reducer = DetectionCheckStateReducer(flow),
            appSettingsRepository = FakeDetectionAppSettingsRepository(),
            detectionCheckRunner = NoopDetectionCheckRunner(),
            probeRunner = probe,
            routingProtectionCatalogService = NoopRoutingCatalog(),
            stringResolver = RecordingStringResolver(),
            onSaveHistory = onSaveHistory,
            onRefreshCommunity = onRefreshCommunity,
        )

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
    fun `non-cancellation failure with null message uses unknown fallback`() {
        val flow = MutableStateFlow(DetectionCheckUiState())
        coordinator(flow, FakeProbeRunner(error = RuntimeException())).startCheck()

        assertFalse(flow.value.isRunning)
        assertNull(flow.value.progress)
        assertTrue(flow.value.error?.startsWith("string:") == true)
    }

    @Test
    fun `non-cancellation failure surfaces error message`() {
        val flow = MutableStateFlow(DetectionCheckUiState())
        coordinator(flow, FakeProbeRunner(error = RuntimeException("explicit"))).startCheck()

        assertEquals("explicit", flow.value.error)
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
