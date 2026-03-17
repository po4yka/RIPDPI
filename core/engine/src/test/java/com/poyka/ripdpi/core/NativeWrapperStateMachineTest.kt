package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class NativeWrapperStateMachineTest {
    private val json = Json

    @Test
    fun proxyWrapperStateMachinePreservesHandleLifecycleAndLegality() =
        runTest {
            val sequences =
                deterministicSequences(
                    commands = ProxyCommand.entries.toTypedArray(),
                    seedCount = 96,
                    lengthRange = 4..12,
                ) +
                    listOf(
                        listOf(
                            ProxyCommand.START_BLOCKING_SUCCESS,
                            ProxyCommand.POLL_TELEMETRY,
                            ProxyCommand.STOP,
                            ProxyCommand.RELEASE,
                            ProxyCommand.POLL_TELEMETRY,
                        ),
                        listOf(
                            ProxyCommand.START_BLOCKING_FAILURE,
                            ProxyCommand.STOP,
                            ProxyCommand.RELEASE,
                            ProxyCommand.START_CREATE_FAILURE,
                        ),
                    )

            sequences.forEachIndexed { index, commands ->
                val bindings = FakeRipDpiProxyBindings()
                val proxy = RipDpiProxy(bindings)
                val harness = ProxyHarness(bindings, proxy, this)
                var state = ProxyState.IDLE

                try {
                    commands.forEach { command ->
                        state = executeProxyCommand(command, state, harness)
                        assertProxyState(state, proxy)
                    }
                } catch (error: Throwable) {
                    throw AssertionError("Proxy state machine failed on sequence #$index: $commands", error)
                } finally {
                    harness.cleanup()
                }
            }
        }

    @Test
    fun tunnelWrapperStateMachinePreservesHandleLifecycleAndLegality() =
        runTest {
            val sequences =
                deterministicSequences(
                    commands = TunnelCommand.entries.toTypedArray(),
                    seedCount = 96,
                    lengthRange = 4..12,
                ) +
                    listOf(
                        listOf(
                            TunnelCommand.START,
                            TunnelCommand.STATS,
                            TunnelCommand.TELEMETRY,
                            TunnelCommand.STOP,
                            TunnelCommand.STATS,
                        ),
                        listOf(
                            TunnelCommand.START_NATIVE_FAILURE,
                            TunnelCommand.START,
                            TunnelCommand.STOP_FAILURE,
                            TunnelCommand.TELEMETRY,
                        ),
                    )

            sequences.forEachIndexed { index, commands ->
                val bindings = FakeTun2SocksBindings()
                val tunnel = Tun2SocksTunnel(bindings)
                var state = TunnelState.IDLE
                var destroyCount = 0

                try {
                    commands.forEach { command ->
                        val result = executeTunnelCommand(command, state, bindings, tunnel)
                        state = result.state
                        destroyCount += result.destroyIncrements
                        assertTunnelState(state, tunnel)
                        assertEquals(destroyCount, bindings.destroyedHandles.size)
                    }
                } catch (error: Throwable) {
                    throw AssertionError("Tunnel state machine failed on sequence #$index: $commands", error)
                }
            }
        }

    @Test
    fun diagnosticsWrapperStateMachinePreservesHandleLifecycleAndLegality() =
        runTest {
            val sequences =
                deterministicSequences(
                    commands = DiagnosticsCommand.entries.toTypedArray(),
                    seedCount = 96,
                    lengthRange = 4..12,
                ) +
                    listOf(
                        listOf(
                            DiagnosticsCommand.POLL_PROGRESS,
                            DiagnosticsCommand.START_SCAN,
                            DiagnosticsCommand.CANCEL,
                            DiagnosticsCommand.DESTROY,
                        ),
                        listOf(
                            DiagnosticsCommand.START_SCAN_CREATE_FAILURE,
                            DiagnosticsCommand.POLL_PROGRESS,
                            DiagnosticsCommand.START_SCAN_NATIVE_FAILURE,
                            DiagnosticsCommand.DESTROY,
                        ),
                    )

            sequences.forEachIndexed { index, commands ->
                val bindings = FakeNetworkDiagnosticsBindings()
                val diagnostics = NetworkDiagnostics(bindings)
                var state = DiagnosticsState.UNINITIALIZED
                var destroyCount = 0

                try {
                    commands.forEach { command ->
                        val result = executeDiagnosticsCommand(command, state, bindings, diagnostics)
                        state = result.state
                        destroyCount += result.destroyIncrements
                        assertDiagnosticsState(state, diagnostics)
                        assertEquals(destroyCount, bindings.destroyedHandles.size)
                    }
                } catch (error: Throwable) {
                    throw AssertionError("Diagnostics state machine failed on sequence #$index: $commands", error)
                }
            }
        }

    private suspend fun executeProxyCommand(
        command: ProxyCommand,
        state: ProxyState,
        harness: ProxyHarness,
    ): ProxyState =
        when (command) {
            ProxyCommand.START_CREATE_FAILURE -> {
                harness.bindings.createFailure = IOException("create failure")
                try {
                    val error =
                        runCatching {
                            harness.proxy.startProxy(RipDpiProxyUIPreferences(port = 1100))
                        }.exceptionOrNull()
                    when (state) {
                        ProxyState.IDLE -> assertTrue(error is IOException)

                        ProxyState.RUNNING_FAILURE,
                        ProxyState.RUNNING_SUCCESS,
                        -> assertTrue(error is NativeError.AlreadyRunning)
                    }
                    state
                } finally {
                    harness.bindings.createFailure = null
                }
            }

            ProxyCommand.START_BLOCKING_SUCCESS -> {
                when (state) {
                    ProxyState.IDLE -> {
                        harness.beginStart(failAtStart = false)
                        ProxyState.RUNNING_SUCCESS
                    }

                    ProxyState.RUNNING_FAILURE,
                    ProxyState.RUNNING_SUCCESS,
                    -> {
                        val error =
                            runCatching {
                                harness.proxy.startProxy(RipDpiProxyUIPreferences(port = 1101))
                            }.exceptionOrNull()
                        assertTrue(error is NativeError.AlreadyRunning)
                        state
                    }
                }
            }

            ProxyCommand.START_BLOCKING_FAILURE -> {
                when (state) {
                    ProxyState.IDLE -> {
                        harness.beginStart(failAtStart = true)
                        ProxyState.RUNNING_FAILURE
                    }

                    ProxyState.RUNNING_FAILURE,
                    ProxyState.RUNNING_SUCCESS,
                    -> {
                        val error =
                            runCatching {
                                harness.proxy.startProxy(RipDpiProxyUIPreferences(port = 1102))
                            }.exceptionOrNull()
                        assertTrue(error is NativeError.AlreadyRunning)
                        state
                    }
                }
            }

            ProxyCommand.STOP -> {
                when (state) {
                    ProxyState.IDLE -> {
                        val error = runCatching { harness.proxy.stopProxy() }.exceptionOrNull()
                        assertTrue(error is NativeError.NotRunning)
                        state
                    }

                    ProxyState.RUNNING_FAILURE,
                    ProxyState.RUNNING_SUCCESS,
                    -> {
                        harness.proxy.stopProxy()
                        state
                    }
                }
            }

            ProxyCommand.STOP_FAILURE -> {
                when (state) {
                    ProxyState.IDLE -> {
                        harness.bindings.stopFailure = IOException("stop failure")
                        try {
                            val error = runCatching { harness.proxy.stopProxy() }.exceptionOrNull()
                            assertTrue(error is NativeError.NotRunning)
                        } finally {
                            harness.bindings.stopFailure = null
                        }
                        state
                    }

                    ProxyState.RUNNING_FAILURE,
                    ProxyState.RUNNING_SUCCESS,
                    -> {
                        harness.bindings.stopFailure = IOException("stop failure")
                        try {
                            val error = runCatching { harness.proxy.stopProxy() }.exceptionOrNull()
                            assertTrue(error is IOException)
                        } finally {
                            harness.bindings.stopFailure = null
                        }
                        state
                    }
                }
            }

            ProxyCommand.POLL_TELEMETRY -> {
                val previousTelemetryCalls = harness.bindings.telemetryHandles.size
                val telemetry = harness.proxy.pollTelemetry()
                when (state) {
                    ProxyState.IDLE -> {
                        assertEquals("idle", telemetry.state)
                        assertEquals(previousTelemetryCalls, harness.bindings.telemetryHandles.size)
                    }

                    ProxyState.RUNNING_FAILURE,
                    ProxyState.RUNNING_SUCCESS,
                    -> {
                        assertEquals("running", telemetry.state)
                        assertEquals(previousTelemetryCalls + 1, harness.bindings.telemetryHandles.size)
                    }
                }
                state
            }

            ProxyCommand.POLL_TELEMETRY_FAILURE -> {
                harness.bindings.telemetryFailure = IOException("telemetry failure")
                try {
                    val previousTelemetryCalls = harness.bindings.telemetryHandles.size
                    val error =
                        runCatching {
                            harness.proxy.pollTelemetry()
                        }.exceptionOrNull()
                    when (state) {
                        ProxyState.IDLE -> {
                            assertEquals(null, error)
                            assertEquals(previousTelemetryCalls, harness.bindings.telemetryHandles.size)
                        }

                        ProxyState.RUNNING_FAILURE,
                        ProxyState.RUNNING_SUCCESS,
                        -> {
                            assertTrue(error is IOException)
                            assertEquals(previousTelemetryCalls + 1, harness.bindings.telemetryHandles.size)
                        }
                    }
                    state
                } finally {
                    harness.bindings.telemetryFailure = null
                }
            }

            ProxyCommand.RELEASE -> {
                when (state) {
                    ProxyState.IDLE -> {
                        ProxyState.IDLE
                    }

                    ProxyState.RUNNING_SUCCESS -> {
                        harness.release()
                        ProxyState.IDLE
                    }

                    ProxyState.RUNNING_FAILURE -> {
                        harness.release()
                        ProxyState.IDLE
                    }
                }
            }
        }

    private fun assertProxyState(
        state: ProxyState,
        proxy: RipDpiProxy,
    ) {
        val handle = currentHandle(proxy)
        when (state) {
            ProxyState.IDLE -> assertEquals(0L, handle)

            ProxyState.RUNNING_FAILURE,
            ProxyState.RUNNING_SUCCESS,
            -> assertTrue(handle != 0L)
        }
    }

    private suspend fun executeTunnelCommand(
        command: TunnelCommand,
        state: TunnelState,
        bindings: FakeTun2SocksBindings,
        tunnel: Tun2SocksTunnel,
    ): TunnelCommandResult =
        when (command) {
            TunnelCommand.START -> {
                when (state) {
                    TunnelState.IDLE -> {
                        bindings.nativeStats = longArrayOf(1L, 2L, 3L, 4L)
                        bindings.telemetryJson = runningTunnelTelemetryJson()
                        tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 41)
                        TunnelCommandResult(TunnelState.RUNNING)
                    }

                    TunnelState.RUNNING -> {
                        val error =
                            runCatching {
                                tunnel.start(Tun2SocksConfig(socks5Port = 1081), tunFd = 42)
                            }.exceptionOrNull()
                        assertTrue(error is NativeError.AlreadyRunning)
                        TunnelCommandResult(state)
                    }
                }
            }

            TunnelCommand.START_CREATE_FAILURE -> {
                bindings.createFailure = IOException("create failure")
                try {
                    val error =
                        runCatching {
                            tunnel.start(Tun2SocksConfig(socks5Port = 1082), tunFd = 43)
                        }.exceptionOrNull()
                    when (state) {
                        TunnelState.IDLE -> assertTrue(error is IOException)
                        TunnelState.RUNNING -> assertTrue(error is NativeError.AlreadyRunning)
                    }
                    TunnelCommandResult(state)
                } finally {
                    bindings.createFailure = null
                }
            }

            TunnelCommand.START_NATIVE_FAILURE -> {
                when (state) {
                    TunnelState.IDLE -> {
                        bindings.startFailure = IOException("start failure")
                        try {
                            val error =
                                runCatching {
                                    tunnel.start(Tun2SocksConfig(socks5Port = 1083), tunFd = 44)
                                }.exceptionOrNull()
                            assertTrue(error is IOException)
                            TunnelCommandResult(TunnelState.IDLE, destroyIncrements = 1)
                        } finally {
                            bindings.startFailure = null
                        }
                    }

                    TunnelState.RUNNING -> {
                        val error =
                            runCatching {
                                tunnel.start(Tun2SocksConfig(socks5Port = 1084), tunFd = 45)
                            }.exceptionOrNull()
                        assertTrue(error is NativeError.AlreadyRunning)
                        TunnelCommandResult(state)
                    }
                }
            }

            TunnelCommand.STOP -> {
                when (state) {
                    TunnelState.IDLE -> {
                        val error = runCatching { tunnel.stop() }.exceptionOrNull()
                        assertTrue(error is NativeError.NotRunning)
                        TunnelCommandResult(state)
                    }

                    TunnelState.RUNNING -> {
                        tunnel.stop()
                        TunnelCommandResult(TunnelState.IDLE, destroyIncrements = 1)
                    }
                }
            }

            TunnelCommand.STOP_FAILURE -> {
                when (state) {
                    TunnelState.IDLE -> {
                        val error = runCatching { tunnel.stop() }.exceptionOrNull()
                        assertTrue(error is NativeError.NotRunning)
                        TunnelCommandResult(state)
                    }

                    TunnelState.RUNNING -> {
                        bindings.stopFailure = IOException("stop failure")
                        try {
                            val error = runCatching { tunnel.stop() }.exceptionOrNull()
                            assertTrue(error is IOException)
                            TunnelCommandResult(TunnelState.IDLE, destroyIncrements = 1)
                        } finally {
                            bindings.stopFailure = null
                        }
                    }
                }
            }

            TunnelCommand.STATS -> {
                val previousStatsCalls = bindings.statsHandles.size
                val stats = tunnel.stats()
                when (state) {
                    TunnelState.IDLE -> {
                        assertEquals(TunnelStats(), stats)
                        assertEquals(previousStatsCalls, bindings.statsHandles.size)
                    }

                    TunnelState.RUNNING -> {
                        assertEquals(TunnelStats(txPackets = 1, txBytes = 2, rxPackets = 3, rxBytes = 4), stats)
                        assertEquals(previousStatsCalls + 1, bindings.statsHandles.size)
                    }
                }
                TunnelCommandResult(state)
            }

            TunnelCommand.STATS_FAILURE -> {
                bindings.statsFailure = IOException("stats failure")
                try {
                    val previousStatsCalls = bindings.statsHandles.size
                    val error = runCatching { tunnel.stats() }.exceptionOrNull()
                    when (state) {
                        TunnelState.IDLE -> {
                            assertEquals(null, error)
                            assertEquals(previousStatsCalls, bindings.statsHandles.size)
                        }

                        TunnelState.RUNNING -> {
                            assertTrue(error is IOException)
                            assertEquals(previousStatsCalls + 1, bindings.statsHandles.size)
                        }
                    }
                    TunnelCommandResult(state)
                } finally {
                    bindings.statsFailure = null
                }
            }

            TunnelCommand.TELEMETRY -> {
                val previousTelemetryCalls = bindings.telemetryHandles.size
                val telemetry = tunnel.telemetry()
                when (state) {
                    TunnelState.IDLE -> {
                        assertEquals("idle", telemetry.state)
                        assertEquals(previousTelemetryCalls, bindings.telemetryHandles.size)
                    }

                    TunnelState.RUNNING -> {
                        assertEquals("running", telemetry.state)
                        assertEquals(previousTelemetryCalls + 1, bindings.telemetryHandles.size)
                    }
                }
                TunnelCommandResult(state)
            }

            TunnelCommand.TELEMETRY_FAILURE -> {
                bindings.telemetryFailure = IOException("telemetry failure")
                try {
                    val previousTelemetryCalls = bindings.telemetryHandles.size
                    val error = runCatching { tunnel.telemetry() }.exceptionOrNull()
                    when (state) {
                        TunnelState.IDLE -> {
                            assertEquals(null, error)
                            assertEquals(previousTelemetryCalls, bindings.telemetryHandles.size)
                        }

                        TunnelState.RUNNING -> {
                            assertTrue(error is IOException)
                            assertEquals(previousTelemetryCalls + 1, bindings.telemetryHandles.size)
                        }
                    }
                    TunnelCommandResult(state)
                } finally {
                    bindings.telemetryFailure = null
                }
            }
        }

    private fun assertTunnelState(
        state: TunnelState,
        tunnel: Tun2SocksTunnel,
    ) {
        val handle = currentHandle(tunnel)
        when (state) {
            TunnelState.IDLE -> assertEquals(0L, handle)
            TunnelState.RUNNING -> assertTrue(handle != 0L)
        }
    }

    private suspend fun executeDiagnosticsCommand(
        command: DiagnosticsCommand,
        state: DiagnosticsState,
        bindings: FakeNetworkDiagnosticsBindings,
        diagnostics: NetworkDiagnostics,
    ): DiagnosticsCommandResult =
        when (command) {
            DiagnosticsCommand.START_SCAN -> {
                when (state) {
                    DiagnosticsState.SCANNING -> {
                        val error =
                            runCatching {
                                diagnostics.startScan("""{"kind":"noop"}""", "session-running")
                            }.exceptionOrNull()
                        assertTrue(error is IllegalStateException)
                        DiagnosticsCommandResult(state)
                    }

                    DiagnosticsState.READY,
                    DiagnosticsState.UNINITIALIZED,
                    -> {
                        diagnostics.startScan("""{"kind":"noop"}""", "session-start")
                        DiagnosticsCommandResult(DiagnosticsState.SCANNING)
                    }
                }
            }

            DiagnosticsCommand.START_SCAN_CREATE_FAILURE -> {
                bindings.createdHandle = 0L
                try {
                    val error =
                        runCatching {
                            diagnostics.startScan("""{"kind":"noop"}""", "session-create-failure")
                        }.exceptionOrNull()
                    when (state) {
                        DiagnosticsState.UNINITIALIZED -> {
                            assertTrue(error is NativeError.SessionCreationFailed)
                            DiagnosticsCommandResult(DiagnosticsState.UNINITIALIZED)
                        }

                        DiagnosticsState.READY -> {
                            assertEquals(null, error)
                            DiagnosticsCommandResult(DiagnosticsState.SCANNING)
                        }

                        DiagnosticsState.SCANNING -> {
                            assertTrue(error is IllegalStateException)
                            DiagnosticsCommandResult(state)
                        }
                    }
                } finally {
                    bindings.createdHandle = 1L
                }
            }

            DiagnosticsCommand.START_SCAN_NATIVE_FAILURE -> {
                bindings.startFailure = IOException("start failure")
                try {
                    val error =
                        runCatching {
                            diagnostics.startScan("""{"kind":"noop"}""", "session-native-failure")
                        }.exceptionOrNull()
                    when (state) {
                        DiagnosticsState.SCANNING -> {
                            assertTrue(error is IllegalStateException)
                            DiagnosticsCommandResult(DiagnosticsState.SCANNING)
                        }

                        DiagnosticsState.READY,
                        DiagnosticsState.UNINITIALIZED,
                        -> {
                            assertTrue(error is IOException)
                            DiagnosticsCommandResult(DiagnosticsState.READY)
                        }
                    }
                } finally {
                    bindings.startFailure = null
                }
            }

            DiagnosticsCommand.CANCEL -> {
                val previousCancelCount = bindings.cancelledHandles.size
                diagnostics.cancelScan()
                when (state) {
                    DiagnosticsState.UNINITIALIZED -> {
                        assertEquals(previousCancelCount, bindings.cancelledHandles.size)
                        DiagnosticsCommandResult(DiagnosticsState.UNINITIALIZED)
                    }

                    DiagnosticsState.READY -> {
                        assertEquals(previousCancelCount + 1, bindings.cancelledHandles.size)
                        DiagnosticsCommandResult(DiagnosticsState.READY)
                    }

                    DiagnosticsState.SCANNING -> {
                        assertEquals(previousCancelCount + 1, bindings.cancelledHandles.size)
                        DiagnosticsCommandResult(DiagnosticsState.READY)
                    }
                }
            }

            DiagnosticsCommand.POLL_PROGRESS -> {
                val previousPolls = bindings.progressHandles.size
                val payload = diagnostics.pollProgressJson()
                assertEquals(bindings.progressJson, payload)
                when (state) {
                    DiagnosticsState.UNINITIALIZED -> {
                        assertEquals(previousPolls + 1, bindings.progressHandles.size)
                        DiagnosticsCommandResult(DiagnosticsState.READY)
                    }

                    DiagnosticsState.READY,
                    DiagnosticsState.SCANNING,
                    -> {
                        assertEquals(previousPolls + 1, bindings.progressHandles.size)
                        DiagnosticsCommandResult(state)
                    }
                }
            }

            DiagnosticsCommand.POLL_PROGRESS_FAILURE -> {
                bindings.pollProgressFailure = IOException("progress failure")
                try {
                    val previousPolls = bindings.progressHandles.size
                    val error = runCatching { diagnostics.pollProgressJson() }.exceptionOrNull()
                    assertTrue(error is IOException)
                    when (state) {
                        DiagnosticsState.UNINITIALIZED -> {
                            assertEquals(previousPolls + 1, bindings.progressHandles.size)
                            DiagnosticsCommandResult(DiagnosticsState.READY)
                        }

                        DiagnosticsState.READY,
                        DiagnosticsState.SCANNING,
                        -> {
                            assertEquals(previousPolls + 1, bindings.progressHandles.size)
                            DiagnosticsCommandResult(state)
                        }
                    }
                } finally {
                    bindings.pollProgressFailure = null
                }
            }

            DiagnosticsCommand.TAKE_REPORT -> {
                val previousReports = bindings.reportHandles.size
                val payload = diagnostics.takeReportJson()
                assertEquals(bindings.reportJson, payload)
                when (state) {
                    DiagnosticsState.UNINITIALIZED -> {
                        assertEquals(previousReports + 1, bindings.reportHandles.size)
                        DiagnosticsCommandResult(DiagnosticsState.READY)
                    }

                    DiagnosticsState.READY,
                    DiagnosticsState.SCANNING,
                    -> {
                        assertEquals(previousReports + 1, bindings.reportHandles.size)
                        DiagnosticsCommandResult(state)
                    }
                }
            }

            DiagnosticsCommand.PASSIVE_EVENTS -> {
                val previousPassiveEvents = bindings.passiveEventHandles.size
                val payload = diagnostics.pollPassiveEventsJson()
                assertEquals(bindings.passiveEventsJson, payload)
                when (state) {
                    DiagnosticsState.UNINITIALIZED -> {
                        assertEquals(previousPassiveEvents + 1, bindings.passiveEventHandles.size)
                        DiagnosticsCommandResult(DiagnosticsState.READY)
                    }

                    DiagnosticsState.READY,
                    DiagnosticsState.SCANNING,
                    -> {
                        assertEquals(previousPassiveEvents + 1, bindings.passiveEventHandles.size)
                        DiagnosticsCommandResult(state)
                    }
                }
            }

            DiagnosticsCommand.DESTROY -> {
                val previousDestroyCount = bindings.destroyedHandles.size
                diagnostics.destroy()
                when (state) {
                    DiagnosticsState.UNINITIALIZED -> {
                        assertEquals(previousDestroyCount, bindings.destroyedHandles.size)
                        DiagnosticsCommandResult(DiagnosticsState.UNINITIALIZED)
                    }

                    DiagnosticsState.READY,
                    DiagnosticsState.SCANNING,
                    -> {
                        assertEquals(previousDestroyCount + 1, bindings.destroyedHandles.size)
                        DiagnosticsCommandResult(DiagnosticsState.UNINITIALIZED, destroyIncrements = 1)
                    }
                }
            }
        }

    private fun assertDiagnosticsState(
        state: DiagnosticsState,
        diagnostics: NetworkDiagnostics,
    ) {
        val handle = currentHandle(diagnostics)
        when (state) {
            DiagnosticsState.UNINITIALIZED -> assertEquals(0L, handle)

            DiagnosticsState.READY,
            DiagnosticsState.SCANNING,
            -> assertTrue(handle != 0L)
        }
    }

    private fun currentHandle(target: Any): Long {
        val field = target.javaClass.getDeclaredField("handle")
        field.isAccessible = true
        return field.getLong(target)
    }

    private fun runningProxyTelemetryJson(): String =
        json.encodeToString(
            NativeRuntimeSnapshot.serializer(),
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                health = "healthy",
                activeSessions = 1,
                totalSessions = 2,
                listenerAddress = "127.0.0.1:1080",
                capturedAt = 1L,
            ),
        )

    private fun runningTunnelTelemetryJson(): String =
        json.encodeToString(
            NativeRuntimeSnapshot.serializer(),
            NativeRuntimeSnapshot(
                source = "tunnel",
                state = "running",
                health = "healthy",
                activeSessions = 1,
                tunnelStats = TunnelStats(txPackets = 1, txBytes = 2, rxPackets = 3, rxBytes = 4),
                capturedAt = 1L,
            ),
        )

    private fun <T> deterministicSequences(
        commands: Array<T>,
        seedCount: Int,
        lengthRange: IntRange,
    ): List<List<T>> =
        List(seedCount) { seed ->
            val random = Random(seed)
            val size = random.nextInt(lengthRange.first, lengthRange.last + 1)
            List(size) { commands[random.nextInt(commands.size)] }
        }

    private enum class ProxyCommand {
        START_CREATE_FAILURE,
        START_BLOCKING_SUCCESS,
        START_BLOCKING_FAILURE,
        STOP,
        STOP_FAILURE,
        POLL_TELEMETRY,
        POLL_TELEMETRY_FAILURE,
        RELEASE,
    }

    private enum class ProxyState {
        IDLE,
        RUNNING_SUCCESS,
        RUNNING_FAILURE,
    }

    private inner class ProxyHarness(
        val bindings: FakeRipDpiProxyBindings,
        val proxy: RipDpiProxy,
        private val scope: kotlinx.coroutines.CoroutineScope,
    ) {
        private var startJob: Deferred<Result<Int>>? = null
        private var startBlocker: CompletableDeferred<Unit>? = null

        suspend fun beginStart(failAtStart: Boolean) {
            bindings.startFailure = if (failAtStart) IOException("start failure") else null
            bindings.telemetryJson = runningProxyTelemetryJson()
            val blocker = CompletableDeferred<Unit>()
            val startedSignal = CompletableDeferred<Long>()
            bindings.startBlocker = blocker
            bindings.startedSignal = startedSignal
            startBlocker = blocker
            startJob =
                scope.async {
                    runCatching {
                        proxy.startProxy(RipDpiProxyUIPreferences(port = 1080))
                    }
                }
            assertEquals(1L, startedSignal.await())
        }

        suspend fun release() {
            startBlocker?.complete(Unit)
            val job = startJob
            if (job != null) {
                val result = job.await()
                if (bindings.startFailure == null) {
                    assertEquals(0, result.getOrThrow())
                } else {
                    assertTrue(result.exceptionOrNull() is IOException)
                }
            }
            bindings.startFailure = null
            bindings.startBlocker = null
            bindings.startedSignal = null
            startBlocker = null
            startJob = null
        }

        suspend fun cleanup() {
            release()
        }
    }

    private enum class TunnelCommand {
        START,
        START_CREATE_FAILURE,
        START_NATIVE_FAILURE,
        STOP,
        STOP_FAILURE,
        STATS,
        STATS_FAILURE,
        TELEMETRY,
        TELEMETRY_FAILURE,
    }

    private enum class TunnelState {
        IDLE,
        RUNNING,
    }

    private data class TunnelCommandResult(
        val state: TunnelState,
        val destroyIncrements: Int = 0,
    )

    private enum class DiagnosticsCommand {
        START_SCAN,
        START_SCAN_CREATE_FAILURE,
        START_SCAN_NATIVE_FAILURE,
        CANCEL,
        POLL_PROGRESS,
        POLL_PROGRESS_FAILURE,
        TAKE_REPORT,
        PASSIVE_EVENTS,
        DESTROY,
    }

    private enum class DiagnosticsState {
        UNINITIALIZED,
        READY,
        SCANNING,
    }

    private data class DiagnosticsCommandResult(
        val state: DiagnosticsState,
        val destroyIncrements: Int = 0,
    )
}
