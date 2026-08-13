package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.ServiceStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class ImportedRelayProfilePreflightRequest(
    val profile: RelayProfileRecord,
    val credentials: RelayCredentialRecord,
)

enum class ImportedRelayProfilePreflightResult {
    ReachedTarget,
    Unsupported,
    ServiceBusy,
    StartupFailure,
    ProbeFailure,
    TimedOut,
    Cancelled,
    CleanupFailure,
}

fun interface ImportedRelayProfilePreflight {
    suspend fun check(request: ImportedRelayProfilePreflightRequest): ImportedRelayProfilePreflightResult
}

@Singleton
internal class DefaultImportedRelayProfilePreflight
    @Inject
    constructor(
        private val serviceStateStore: ServiceStateStore,
        private val interlock: ServiceRuntimeAdmissionInterlock,
        private val resolver: UpstreamRelayRuntimeConfigResolver,
        private val relayFactory: RipDpiRelayFactory,
        private val capabilityProbe: RelayCapabilityProbe,
        private val dispatchers: AppCoroutineDispatchers,
    ) : ImportedRelayProfilePreflight {
        override suspend fun check(request: ImportedRelayProfilePreflightRequest): ImportedRelayProfilePreflightResult =
            try {
                withTimeout(TotalDeadlineMillis) {
                    interlock.runPreflight {
                        if (serviceStateStore.status.value.first != AppStatus.Halted) {
                            return@runPreflight ImportedRelayProfilePreflightResult.ServiceBusy
                        }
                        runTransientSession(request)
                    }
                }
            } catch (_: RelayPreflightUnavailableException) {
                ImportedRelayProfilePreflightResult.ServiceBusy
            } catch (_: TimeoutCancellationException) {
                ImportedRelayProfilePreflightResult.TimedOut
            } catch (_: RelayPreflightPreemptedException) {
                ImportedRelayProfilePreflightResult.Cancelled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                ImportedRelayProfilePreflightResult.StartupFailure
            }

        private suspend fun runTransientSession(
            request: ImportedRelayProfilePreflightRequest,
        ): ImportedRelayProfilePreflightResult =
            supervisorScope {
                val runtime = relayFactory.create()
                val resolved =
                    try {
                        resolver
                            .resolveTransient(request.profile, request.credentials)
                            .copy(
                                localSocksHost = LoopbackHost,
                                localSocksPort = EphemeralPort,
                                udpEnabled = false,
                            )
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        return@supervisorScope ImportedRelayProfilePreflightResult.Unsupported
                    }
                var startJob: Job? = null
                var result = ImportedRelayProfilePreflightResult.StartupFailure
                try {
                    startJob = launchRuntime(runtime, resolved)
                    runtime.awaitReady()
                    val endpoint = resolveLocalProxyEndpoint(runtime.pollTelemetry(), authToken = null)
                    val probe =
                        capabilityProbe.probe(
                            endpoint = RelayProbeEndpoint(endpoint.host, endpoint.port),
                            url = ProbeUrl,
                            requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
                        )
                    result =
                        if (probe.succeeded) {
                            ImportedRelayProfilePreflightResult.ReachedTarget
                        } else {
                            ImportedRelayProfilePreflightResult.ProbeFailure
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Exception,
                ) {
                    result = ImportedRelayProfilePreflightResult.StartupFailure
                } finally {
                    if (!cleanup(runtime, startJob)) {
                        result = ImportedRelayProfilePreflightResult.CleanupFailure
                    }
                }
                result
            }

        private fun CoroutineScope.launchRuntime(
            runtime: RipDpiRelayRuntime,
            config: com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig,
        ): Job =
            launch(dispatchers.io, start = CoroutineStart.UNDISPATCHED) {
                runCatching { runtime.start(config) }
            }

        private suspend fun cleanup(
            runtime: RipDpiRelayRuntime,
            job: Job?,
        ): Boolean =
            withContext(NonCancellable) {
                val stopped = runCatching { runtime.stop() }.isSuccess
                val joined = job == null || withTimeoutOrNull(CleanupDeadlineMillis) { job.join() } != null
                if (!joined) requireNotNull(job).cancelAndJoin()
                stopped && joined
            }

        private companion object {
            const val TotalDeadlineMillis = 12_000L
            const val CleanupDeadlineMillis = 5_000L
            const val LoopbackHost = "127.0.0.1"
            const val EphemeralPort = 0
            const val ProbeUrl = "https://connectivitycheck.gstatic.com/generate_204"
        }
    }

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class ImportedRelayProfilePreflightModule {
    @dagger.Binds
    @Singleton
    abstract fun bindImportedRelayProfilePreflight(
        implementation: DefaultImportedRelayProfilePreflight,
    ): ImportedRelayProfilePreflight
}
