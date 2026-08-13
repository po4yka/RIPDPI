package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportedRelayProfilePreflightTest {
    @Test
    fun `busy service rejects preflight before creating a runtime`() =
        runTest {
            val store = DefaultServiceStateStore().apply { setStatus(AppStatus.Running, Mode.VPN) }
            var created = 0
            val runner =
                pendingRunner(
                    store = store,
                    runtimeFactory = {
                        created += 1
                        PendingRelayRuntime()
                    },
                )

            val result = runner.check(preflightRequest())

            assertEquals(
                ImportedRelayProfilePreflightResult.ServiceBusy to 0,
                result to created,
            )
        }

    @Test
    fun `deadline stops and joins a pending transient runtime without retry`() =
        runTest {
            val runtime = PendingRelayRuntime()
            var created = 0
            val runner =
                pendingRunner {
                    created += 1
                    runtime
                }

            val result = runner.check(preflightRequest())

            assertEquals(
                listOf(ImportedRelayProfilePreflightResult.TimedOut, 1, 1),
                listOf(result, created, runtime.stopCount),
            )
        }

    @Test
    fun `caller cancellation cleans up and remains cancellation to its owner`() =
        runTest {
            val runtime = PendingRelayRuntime()
            val check = async { pendingRunner { runtime }.check(preflightRequest()) }
            runCurrent()

            check.cancelAndJoin()

            assertEquals(1, runtime.stopCount)
        }

    @Test
    fun `successful preflight uses one tcp only ephemeral relay and cleans it up`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime =
                TestRelayRuntime().apply {
                    telemetry =
                        NativeRuntimeSnapshot(
                            source = "relay",
                            state = "running",
                            health = "healthy",
                            listenerAddress = "127.0.0.1:41234",
                        )
                }
            var tcpCalls = 0
            var udpCalls = 0
            val probe =
                RelayCapabilityProbe(
                    tcpProbe =
                        RelayTcpProbe { endpoint, _ ->
                            tcpCalls += 1
                            assertEquals(RelayProbeEndpoint("127.0.0.1", 41234), endpoint)
                            RelayTcpProbeResult(succeeded = true, statusCode = 204)
                        },
                    udpProbe =
                        RelayUdpAssociateProbe { _, _ ->
                            udpCalls += 1
                            RelayUdpProbeResult.success()
                        },
                )
            val runner =
                DefaultImportedRelayProfilePreflight(
                    serviceStateStore = DefaultServiceStateStore(),
                    interlock = RelayPreflightInterlock(),
                    resolver =
                        object : UpstreamRelayRuntimeConfigResolver {
                            override suspend fun resolve(
                                config: RipDpiRelayConfig,
                                quicMigrationConfig: OwnedRelayQuicMigrationConfig,
                            ): ResolvedRipDpiRelayConfig = sampleResolvedRelayConfig()

                            override suspend fun resolveTransient(
                                profile: RelayProfileRecord,
                                credentials: RelayCredentialRecord,
                            ): ResolvedRipDpiRelayConfig = sampleResolvedRelayConfig(profileId = profile.id)
                        },
                    relayFactory = TestRipDpiRelayFactory { runtime },
                    capabilityProbe = probe,
                    dispatchers = AppCoroutineDispatchers(dispatcher, dispatcher, dispatcher),
                )

            val result =
                runner.check(
                    ImportedRelayProfilePreflightRequest(
                        profile = RelayProfileRecord(id = "transient", kind = RelayKindVlessReality),
                        credentials = RelayCredentialRecord(profileId = "transient"),
                    ),
                )

            assertEquals(
                listOf(
                    ImportedRelayProfilePreflightResult.ReachedTarget,
                    "127.0.0.1",
                    0,
                    false,
                    1,
                    0,
                    1,
                    listOf("relay:start", "relay:stop"),
                ),
                listOf(
                    result,
                    runtime.lastConfig?.localSocksHost,
                    runtime.lastConfig?.localSocksPort,
                    runtime.lastConfig?.udpEnabled,
                    tcpCalls,
                    udpCalls,
                    runtime.stopCount,
                    runtime.events,
                ),
            )
        }

    private fun preflightRequest() =
        ImportedRelayProfilePreflightRequest(
            profile = RelayProfileRecord(id = "transient", kind = RelayKindVlessReality),
            credentials = RelayCredentialRecord(profileId = "transient"),
        )

    private fun kotlinx.coroutines.test.TestScope.pendingRunner(
        store: DefaultServiceStateStore = DefaultServiceStateStore(),
        runtimeFactory: () -> RipDpiRelayRuntime,
    ): DefaultImportedRelayProfilePreflight {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DefaultImportedRelayProfilePreflight(
            serviceStateStore = store,
            interlock = RelayPreflightInterlock(),
            resolver = TransientTestResolver,
            relayFactory =
                object : RipDpiRelayFactory {
                    override fun create(): RipDpiRelayRuntime = runtimeFactory()
                },
            capabilityProbe =
                RelayCapabilityProbe(
                    tcpProbe = RelayTcpProbe { _, _ -> RelayTcpProbeResult(succeeded = true, statusCode = 204) },
                    udpProbe = RelayUdpAssociateProbe { _, _ -> RelayUdpProbeResult.success() },
                ),
            dispatchers = AppCoroutineDispatchers(dispatcher, dispatcher, dispatcher),
        )
    }
}

private object TransientTestResolver : UpstreamRelayRuntimeConfigResolver {
    override suspend fun resolve(
        config: RipDpiRelayConfig,
        quicMigrationConfig: OwnedRelayQuicMigrationConfig,
    ): ResolvedRipDpiRelayConfig = sampleResolvedRelayConfig()

    override suspend fun resolveTransient(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
    ): ResolvedRipDpiRelayConfig = sampleResolvedRelayConfig(profileId = profile.id)
}

private class PendingRelayRuntime : RipDpiRelayRuntime {
    var stopCount: Int = 0
        private set

    override suspend fun start(config: ResolvedRipDpiRelayConfig): Int = awaitCancellation()

    override suspend fun awaitReady(timeoutMillis: Long) = awaitCancellation()

    override suspend fun stop() {
        stopCount += 1
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot = error("pending runtime has no telemetry")
}
