package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeActivationReceipt
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartRejectionReason
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.services.TestSynchronousServiceController
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainConnectionResultFeedbackTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `explicit start emits a result only when the service is running`() =
        runTest {
            val fixture = ConnectionFeedbackFixture(backgroundScope)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            assertTrue(fixture.effects.replayCache.isEmpty())

            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()

            assertEquals(ConnectionState.Connected, fixture.state.value.connectionState)
            assertEquals(listOf(MainEffect.ConnectionEstablished), fixture.effects.replayCache)
        }

    @Test
    fun `optional authority failure consumes feedback and preserves the status collector`() =
        runTest {
            val controller = FakeServiceController()
            val fixture = ConnectionFeedbackFixture(backgroundScope, controller = controller)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            controller.nextCaptureFailure = IOException("Authority unavailable")
            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(ConnectionState.Connected, fixture.state.value.connectionState)
            fixture.store.setStatus(AppStatus.Reconnecting, Mode.Proxy)
            runCurrent()
            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertTrue(fixture.effects.replayCache.isEmpty())
            fixture.store.setStatus(AppStatus.Halted, Mode.Proxy)
            runCurrent()
            assertEquals(ConnectionState.Disconnected, fixture.state.value.connectionState)
            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(ConnectionState.Connected, fixture.state.value.connectionState)
            assertTrue(fixture.effects.replayCache.isEmpty())
            assertEquals(null, controller.nextCaptureFailure)

            fixture.store.setStatus(AppStatus.Halted, Mode.Proxy)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(listOf(MainEffect.ConnectionEstablished), fixture.effects.replayCache)
        }

    @Test
    fun `an explicit start can connect the inactive mode while the other mode runs`() =
        runTest {
            Mode.entries.forEach { target ->
                val previous = Mode.entries.first { it != target }
                val fixture =
                    ConnectionFeedbackFixture(
                        backgroundScope,
                        FakeServiceStateStore(AppStatus.Running to previous),
                    )
                runCurrent()
                fixture.actions.startMode(target)
                runCurrent()
                assertTrue(fixture.effects.replayCache.isEmpty())
                fixture.store.setStatus(AppStatus.Running, target)
                runCurrent()
                assertEquals(
                    "Starting $target while $previous runs",
                    listOf(MainEffect.ConnectionEstablished),
                    fixture.effects.replayCache,
                )
            }
        }

    @Test
    fun `initial running and reconnect snapshots do not synthesize results`() =
        runTest {
            val fixture =
                ConnectionFeedbackFixture(backgroundScope, FakeServiceStateStore(AppStatus.Running to Mode.VPN))
            runCurrent()
            fixture.store.setStatus(AppStatus.Reconnecting, Mode.VPN)
            runCurrent()
            fixture.store.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            assertTrue(fixture.effects.replayCache.isEmpty())
        }

    @Test
    fun `one actual result survives repeated running and reconnect snapshots`() =
        runTest {
            val fixture = ConnectionFeedbackFixture(backgroundScope)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            repeat(3) {
                fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
                runCurrent()
                fixture.store.setStatus(AppStatus.Reconnecting, Mode.Proxy)
                runCurrent()
            }
            assertEquals(listOf(MainEffect.ConnectionEstablished), fixture.effects.replayCache)
        }

    @Test
    fun `stop and late running never report success`() =
        runTest {
            val fixture = ConnectionFeedbackFixture(backgroundScope)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            fixture.actions.stop()
            runCurrent()
            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertTrue(fixture.effects.replayCache.isEmpty())
        }

    @Test
    fun `timeout and late running retain only the error outcome`() =
        runTest {
            val fixture = ConnectionFeedbackFixture(backgroundScope)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(1, fixture.effects.replayCache.size)
            assertTrue(fixture.effects.replayCache.single() is MainEffect.ShowError)
        }

    @Test
    fun `superseded authority cannot report success for an older receipt`() =
        runTest {
            val controller = FakeServiceController()
            val fixture = ConnectionFeedbackFixture(backgroundScope, controller = controller)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            controller.prepareStop()
            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertTrue(fixture.effects.replayCache.isEmpty())
        }

    @Test
    fun `running while start is suspended waits for its accepted receipt`() =
        runTest {
            val store = FakeServiceStateStore()
            val gate = CompletableDeferred<Unit>()
            val controller =
                FakeFeedbackServiceController { mode ->
                    store.setStatus(AppStatus.Running, mode)
                    gate.await()
                }
            val fixture = ConnectionFeedbackFixture(backgroundScope, store, controller)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            assertTrue(fixture.effects.replayCache.isEmpty())
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(MainEffect.ConnectionEstablished), fixture.effects.replayCache)
        }

    @Test
    fun `cancelled dispatch and a late status remain quiet`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val controller = FakeFeedbackServiceController { gate.await() }
            val fixture = ConnectionFeedbackFixture(backgroundScope, controller = controller)
            runCurrent()
            val job = fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            job.cancel()
            runCurrent()
            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertTrue(fixture.effects.replayCache.isEmpty())
        }

    @Test
    fun `rejected start and late status retain one error`() =
        runTest {
            val controller =
                FakeServiceController().apply {
                    nextStartResult = ServiceStartResult.Rejected(Mode.Proxy, ServiceStartRejectionReason.Superseded)
                }
            val fixture = ConnectionFeedbackFixture(backgroundScope, controller = controller)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(1, fixture.effects.replayCache.size)
            assertTrue(fixture.effects.replayCache.single() is MainEffect.ShowError)
        }

    @Test
    fun `duplicate failure signals once and a new attempt can report the same failure`() =
        runTest {
            val fixture = ConnectionFeedbackFixture(backgroundScope)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            repeat(2) {
                fixture.store.emitFailed(Sender.Proxy, FailureReason.NativeError("Failed"))
                runCurrent()
            }
            assertEquals(1, fixture.effects.replayCache.size)
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            fixture.store.emitFailed(Sender.Proxy, FailureReason.NativeError("Failed"))
            runCurrent()
            assertEquals(2, fixture.effects.replayCache.size)
            assertTrue(fixture.effects.replayCache.all { it is MainEffect.ShowError })
        }

    @Test
    fun `config retries report equal actual failures once per new start`() =
        runTest {
            val controller = FakeServiceController()
            val fixture = ConnectionFeedbackFixture(backgroundScope, controller = controller)
            val config =
                ConfigRuntimeActions(
                    scope = backgroundScope,
                    controller = controller,
                    service = fixture.store,
                    strings = FakeStringResolver(),
                )
            runCurrent()
            repeat(2) { attempt ->
                config.toggle(Mode.Proxy, true)
                runCurrent()
                repeat(2) {
                    fixture.store.emitFailed(Sender.Proxy, FailureReason.NativeError("Failed"))
                    runCurrent()
                    fixture.store.setStatus(AppStatus.Halted, Mode.Proxy)
                    runCurrent()
                }
                assertEquals(null, config.feedback.value.operation)
                assertEquals(attempt + 1, fixture.effects.replayCache.size)
                assertTrue(fixture.effects.replayCache.all { it is MainEffect.ShowError })
            }
            assertEquals(listOf(Mode.Proxy, Mode.Proxy), controller.startedModes)
        }

    @Test
    fun `terminated config starts retain distinct equal failure results`() =
        runTest {
            val controller =
                object : TestSynchronousServiceController() {
                    override fun recordStart(
                        mode: Mode,
                        receipt: RuntimeActivationReceipt?,
                    ): ServiceStartResult {
                        val accepted = checkNotNull(receipt)
                        testAuthority.cancelPendingActivation(accepted)
                        assertEquals(null, testAuthority.snapshotAuthority().desiredMode)
                        return ServiceStartResult.Accepted(accepted)
                    }

                    override fun recordStop() = Unit
                }
            val fixture = ConnectionFeedbackFixture(backgroundScope, controller = controller)
            val config = ConfigRuntimeActions(backgroundScope, controller, fixture.store, FakeStringResolver())
            runCurrent()
            repeat(2) { attempt ->
                config.toggle(Mode.Proxy, true)
                runCurrent()
                repeat(2) {
                    fixture.store.emitFailed(Sender.Proxy, FailureReason.NativeError("Failed"))
                    runCurrent()
                    fixture.store.setStatus(AppStatus.Halted, Mode.Proxy)
                    runCurrent()
                }
                assertEquals(attempt + 1, fixture.effects.replayCache.size)
            }
        }

    @Test
    fun `unavailable failure identity preserves messages and never replays on read recovery`() =
        runTest {
            val controller = FakeServiceController()
            val fixture = ConnectionFeedbackFixture(backgroundScope, controller = controller)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            controller.nextCaptureFailure = IOException("Authority unavailable")
            fixture.store.emitFailed(Sender.Proxy, FailureReason.NativeError("Failed"))
            runCurrent()
            assertEquals(null, controller.nextCaptureFailure)
            assertEquals(1, fixture.effects.replayCache.size)
            fixture.store.emitFailed(Sender.Proxy, FailureReason.NativeError("Failed"))
            runCurrent()
            assertEquals(1, fixture.effects.replayCache.size)
            fixture.store.emitFailed(Sender.Proxy, FailureReason.NativeError("Changed"))
            runCurrent()
            assertEquals(2, fixture.effects.replayCache.size)
            assertTrue(fixture.effects.replayCache.all { it is MainEffect.ShowError })
        }

    @Test
    fun `slow old failure identity cannot replace a later successful attempt`() =
        runTest {
            val controller =
                object : TestSynchronousServiceController() {
                    var nextCaptureGate: CompletableDeferred<Unit>? = null

                    override suspend fun captureRuntimeSnapshot(): com.poyka.ripdpi.data.RuntimeAuthoritySnapshot {
                        val snapshot = super.captureRuntimeSnapshot()
                        val gate = nextCaptureGate
                        nextCaptureGate = null
                        gate?.await()
                        return snapshot
                    }

                    override fun recordStart(
                        mode: Mode,
                        receipt: RuntimeActivationReceipt?,
                    ): ServiceStartResult = ServiceStartResult.Accepted(checkNotNull(receipt))

                    override fun recordStop() = Unit
                }
            val fixture = ConnectionFeedbackFixture(backgroundScope, controller = controller)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            val oldCapture = CompletableDeferred<Unit>()
            controller.nextCaptureGate = oldCapture
            fixture.store.emitFailed(Sender.Proxy, FailureReason.NativeError("Old failure"))
            runCurrent()
            assertEquals(null, controller.nextCaptureGate)
            fixture.store.setStatus(AppStatus.Halted, Mode.Proxy)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(ConnectionState.Connected, fixture.state.value.connectionState)
            assertEquals(listOf(MainEffect.ConnectionEstablished), fixture.effects.replayCache)
            oldCapture.complete(Unit)
            runCurrent()
            assertEquals(ConnectionState.Connected, fixture.state.value.connectionState)
            assertEquals(listOf(MainEffect.ConnectionEstablished), fixture.effects.replayCache)
        }

    @Test
    fun `changed non-user runtime failure remains visible`() =
        runTest {
            val fixture = ConnectionFeedbackFixture(backgroundScope)
            runCurrent()
            listOf("First", "First", "Changed").forEach { message ->
                fixture.store.emitFailed(Sender.Proxy, FailureReason.NativeError(message))
                runCurrent()
            }
            assertEquals(2, fixture.effects.replayCache.size)
        }

    @Test
    fun `disabled completion is consumed before feedback can be reenabled`() =
        runTest {
            val fixture = ConnectionFeedbackFixture(backgroundScope)
            runCurrent()
            fixture.actions.startMode(Mode.Proxy)
            runCurrent()
            fixture.settings =
                fixture.settings
                    .toBuilder()
                    .setHapticFeedbackDisabled(true)
                    .build()
            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            fixture.settings = AppSettingsSerializer.defaultValue
            fixture.store.setStatus(AppStatus.Reconnecting, Mode.Proxy)
            runCurrent()
            fixture.store.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertTrue(fixture.effects.replayCache.isEmpty())
        }

    @Test
    fun `disabled failure preserves text without queuing an eligible haptic`() =
        runTest {
            val fixture = ConnectionFeedbackFixture(backgroundScope)
            runCurrent()
            fixture.settings =
                fixture.settings
                    .toBuilder()
                    .setHapticFeedbackDisabled(true)
                    .build()
            fixture.store.emitFailed(Sender.Proxy, FailureReason.NativeError("Failed"))
            runCurrent()
            val error = fixture.effects.replayCache.single() as MainEffect.ShowError
            assertTrue(error.message.contains("Failed"))
            assertEquals(false, error.hapticFeedbackEnabled)
        }
}

private class ConnectionFeedbackFixture(
    scope: CoroutineScope,
    val store: FakeServiceStateStore = FakeServiceStateStore(),
    controller: ServiceController = FakeServiceController(),
) {
    var settings = AppSettingsSerializer.defaultValue
    val effects = MutableSharedFlow<MainEffect>(replay = 20)
    val state = MutableStateFlow(ConnectionRuntimeState())
    val actions =
        MainConnectionActions(
            mutations = MainMutationRunner(scope, effects) { MainUiState() },
            serviceController = controller,
            serviceStateStore = store,
            trafficStatsReader = FakeTrafficStatsReader(),
            stringResolver = FakeStringResolver(),
            currentSettings = { settings },
            runtimeState = ConnectionRuntimeStateReducer(state),
            refreshPermissionSnapshot = {},
        ).also { it.initialize() }
}

private class FakeFeedbackServiceController(
    private val beforeAccepted: suspend (Mode) -> Unit,
) : TestSynchronousServiceController() {
    override suspend fun start(mode: Mode): ServiceStartResult {
        val receipt = prepareStart(mode)
        beforeAccepted(mode)
        yield()
        return startPrepared(mode, receipt)
    }

    override fun recordStart(
        mode: Mode,
        receipt: RuntimeActivationReceipt?,
    ): ServiceStartResult = ServiceStartResult.Accepted(checkNotNull(receipt))

    override fun recordStop() = Unit
}
