package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.NativeRuntimeSnapshot
import com.poyka.ripdpi.core.TunnelStats
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppStateManagerStateMachineTest {
    @Test
    fun generatedSequencesPreserveStatusAndTelemetryInvariants() =
        runTest {
            val sequences =
                deterministicSequences(
                    commands = StateStoreCommand.entries.toTypedArray(),
                    seedCount = 128,
                    lengthRange = 5..20,
                ) +
                    listOf(
                        listOf(
                            StateStoreCommand.SET_RUNNING_PROXY,
                            StateStoreCommand.SET_RUNNING_PROXY,
                            StateStoreCommand.EMIT_FAILED_PROXY,
                            StateStoreCommand.UPDATE_PROXY_TELEMETRY,
                            StateStoreCommand.SET_HALTED_PROXY,
                        ),
                        listOf(
                            StateStoreCommand.SET_RUNNING_VPN,
                            StateStoreCommand.UPDATE_RESTART_BOOST,
                            StateStoreCommand.UPDATE_FAILURE_OVERRIDE,
                            StateStoreCommand.SET_HALTED_VPN,
                            StateStoreCommand.SET_RUNNING_VPN,
                        ),
                    )

            sequences.forEachIndexed { index, commands ->
                val store = DefaultServiceStateStore()
                var model = ServiceModel()

                try {
                    commands.forEach { command ->
                        model = applyCommand(store, model, command)
                        model = assertState(store, model)
                    }
                } catch (error: Throwable) {
                    throw AssertionError("Service state machine failed on sequence #$index: $commands", error)
                }
            }
        }

    private fun applyCommand(
        store: DefaultServiceStateStore,
        model: ServiceModel,
        command: StateStoreCommand,
    ): ServiceModel =
        when (command) {
            StateStoreCommand.SET_RUNNING_PROXY -> {
                store.setStatus(AppStatus.Running, Mode.Proxy)
                model.afterSetStatus(AppStatus.Running, Mode.Proxy)
            }

            StateStoreCommand.SET_RUNNING_VPN -> {
                store.setStatus(AppStatus.Running, Mode.VPN)
                model.afterSetStatus(AppStatus.Running, Mode.VPN)
            }

            StateStoreCommand.SET_HALTED_PROXY -> {
                store.setStatus(AppStatus.Halted, Mode.Proxy)
                model.afterSetStatus(AppStatus.Halted, Mode.Proxy)
            }

            StateStoreCommand.SET_HALTED_VPN -> {
                store.setStatus(AppStatus.Halted, Mode.VPN)
                model.afterSetStatus(AppStatus.Halted, Mode.VPN)
            }

            StateStoreCommand.EMIT_FAILED_PROXY -> {
                store.emitFailed(Sender.Proxy)
                model.copy(lastFailureSender = Sender.Proxy, lastFailureAt = TimestampExpectation.AnyNonNull)
            }

            StateStoreCommand.EMIT_FAILED_VPN -> {
                store.emitFailed(Sender.VPN)
                model.copy(lastFailureSender = Sender.VPN, lastFailureAt = TimestampExpectation.AnyNonNull)
            }

            StateStoreCommand.UPDATE_PROXY_TELEMETRY -> {
                val snapshot =
                    ServiceTelemetrySnapshot(
                        mode = Mode.Proxy,
                        status = AppStatus.Running,
                        tunnelStats = TunnelStats(txPackets = 3, txBytes = 4, rxPackets = 5, rxBytes = 6),
                        proxyTelemetry =
                            NativeRuntimeSnapshot(
                                source = "proxy",
                                state = "running",
                                activeSessions = 2,
                                totalSessions = 5,
                                capturedAt = 10L,
                            ),
                        tunnelTelemetry = NativeRuntimeSnapshot.idle(source = "tunnel"),
                        updatedAt = 100L,
                    )
                store.updateTelemetry(snapshot)
                model.afterTelemetry(snapshot)
            }

            StateStoreCommand.UPDATE_VPN_TELEMETRY -> {
                val snapshot =
                    ServiceTelemetrySnapshot(
                        mode = Mode.VPN,
                        status = AppStatus.Running,
                        tunnelStats = TunnelStats(txPackets = 7, txBytes = 8, rxPackets = 9, rxBytes = 10),
                        proxyTelemetry =
                            NativeRuntimeSnapshot(
                                source = "proxy",
                                state = "running",
                                totalSessions = 9,
                                capturedAt = 20L,
                            ),
                        tunnelTelemetry =
                            NativeRuntimeSnapshot(
                                source = "tunnel",
                                state = "running",
                                activeSessions = 1,
                                tunnelStats = TunnelStats(txPackets = 7, txBytes = 8, rxPackets = 9, rxBytes = 10),
                                capturedAt = 21L,
                            ),
                        updatedAt = 200L,
                    )
                store.updateTelemetry(snapshot)
                model.afterTelemetry(snapshot)
            }

            StateStoreCommand.UPDATE_FAILURE_OVERRIDE -> {
                val snapshot =
                    ServiceTelemetrySnapshot(
                        mode = Mode.Proxy,
                        status = AppStatus.Halted,
                        lastFailureSender = Sender.VPN,
                        lastFailureAt = 404L,
                        updatedAt = 300L,
                    )
                store.updateTelemetry(snapshot)
                model.afterTelemetry(snapshot)
            }

            StateStoreCommand.UPDATE_RESTART_BOOST -> {
                val snapshot =
                    ServiceTelemetrySnapshot(
                        mode = Mode.VPN,
                        status = AppStatus.Running,
                        restartCount = 5,
                        serviceStartedAt = 505L,
                        updatedAt = 400L,
                    )
                store.updateTelemetry(snapshot)
                model.afterTelemetry(snapshot)
            }
        }

    private fun assertState(
        store: DefaultServiceStateStore,
        model: ServiceModel,
    ): ServiceModel {
        assertEquals(model.flowStatus to model.flowMode, store.status.value)

        val telemetry = store.telemetry.value
        assertEquals(model.telemetryMode, telemetry.mode)
        assertEquals(model.telemetryStatus, telemetry.status)
        assertEquals(model.tunnelStats, telemetry.tunnelStats)
        assertEquals(model.proxyTelemetry, telemetry.proxyTelemetry)
        assertEquals(model.tunnelTelemetry, telemetry.tunnelTelemetry)
        assertEquals(model.restartCount, telemetry.restartCount)
        assertEquals(model.lastFailureSender, telemetry.lastFailureSender)
        assertEquals(model.updatedAt, telemetry.updatedAt)

        val startedAtExpectation = captureTimestamp(model.serviceStartedAt, telemetry.serviceStartedAt)
        val failureAtExpectation = captureTimestamp(model.lastFailureAt, telemetry.lastFailureAt)

        return model.copy(
            serviceStartedAt = startedAtExpectation,
            lastFailureAt = failureAtExpectation,
        )
    }

    private fun captureTimestamp(
        expectation: TimestampExpectation,
        actual: Long?,
    ): TimestampExpectation =
        when (expectation) {
            TimestampExpectation.Null -> {
                assertNull(actual)
                TimestampExpectation.Null
            }

            TimestampExpectation.AnyNonNull -> {
                assertNotNull(actual)
                TimestampExpectation.Exact(actual!!)
            }

            is TimestampExpectation.Exact -> {
                assertEquals(expectation.value, actual)
                expectation
            }
        }

    private fun deterministicSequences(
        commands: Array<StateStoreCommand>,
        seedCount: Int,
        lengthRange: IntRange,
    ): List<List<StateStoreCommand>> =
        List(seedCount) { seed ->
            val random = Random(seed)
            val size = random.nextInt(lengthRange.first, lengthRange.last + 1)
            List(size) { commands[random.nextInt(commands.size)] }
        }

    private enum class StateStoreCommand {
        SET_RUNNING_PROXY,
        SET_RUNNING_VPN,
        SET_HALTED_PROXY,
        SET_HALTED_VPN,
        EMIT_FAILED_PROXY,
        EMIT_FAILED_VPN,
        UPDATE_PROXY_TELEMETRY,
        UPDATE_VPN_TELEMETRY,
        UPDATE_FAILURE_OVERRIDE,
        UPDATE_RESTART_BOOST,
    }

    private sealed interface TimestampExpectation {
        data object Null : TimestampExpectation

        data object AnyNonNull : TimestampExpectation

        data class Exact(
            val value: Long,
        ) : TimestampExpectation
    }

    private data class ServiceModel(
        val flowStatus: AppStatus = AppStatus.Halted,
        val flowMode: Mode = Mode.VPN,
        val telemetryMode: Mode? = null,
        val telemetryStatus: AppStatus = AppStatus.Halted,
        val tunnelStats: TunnelStats = TunnelStats(),
        val proxyTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "proxy"),
        val tunnelTelemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "tunnel"),
        val serviceStartedAt: TimestampExpectation = TimestampExpectation.Null,
        val restartCount: Int = 0,
        val lastFailureSender: Sender? = null,
        val lastFailureAt: TimestampExpectation = TimestampExpectation.Null,
        val updatedAt: Long = 0L,
    ) {
        fun afterSetStatus(
            status: AppStatus,
            mode: Mode,
        ): ServiceModel {
            val enteringRunning = status == AppStatus.Running && flowStatus != AppStatus.Running
            return copy(
                flowStatus = status,
                flowMode = mode,
                telemetryMode = mode,
                telemetryStatus = status,
                serviceStartedAt =
                    when {
                        enteringRunning -> TimestampExpectation.AnyNonNull
                        status == AppStatus.Running -> serviceStartedAt
                        else -> TimestampExpectation.Null
                    },
                restartCount = if (enteringRunning) restartCount + 1 else restartCount,
            )
        }

        fun afterTelemetry(snapshot: ServiceTelemetrySnapshot): ServiceModel =
            copy(
                telemetryMode = snapshot.mode,
                telemetryStatus = snapshot.status,
                tunnelStats = snapshot.tunnelStats,
                proxyTelemetry = snapshot.proxyTelemetry,
                tunnelTelemetry = snapshot.tunnelTelemetry,
                serviceStartedAt =
                    snapshot.serviceStartedAt?.let(TimestampExpectation::Exact) ?: serviceStartedAt,
                restartCount = maxOf(snapshot.restartCount, restartCount),
                lastFailureSender = snapshot.lastFailureSender ?: lastFailureSender,
                lastFailureAt =
                    snapshot.lastFailureAt?.let(TimestampExpectation::Exact) ?: lastFailureAt,
                updatedAt = snapshot.updatedAt,
            )
    }
}
