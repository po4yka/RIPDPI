package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.service.awg.AmneziaWgLocalSocksPort
import com.poyka.ripdpi.service.awg.AmneziaWgRuntimeConfigResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

enum class AwgMigrationMeasurement { Succeeded, ConnectTargetFirst, Failed, EnvironmentChanged }

/** Tests only the exact configuration already consumed by the owned AWG runtime. Never starts a tunnel. */
@Singleton
class ActiveAwgMigrationProbe internal constructor(
    private val resolver: AmneziaWgRuntimeConfigResolver,
    private val runtime: ActiveAwgRuntimeRegistry,
    private val http: CandidateHttpPayloadProbe,
    private val environment: suspend () -> CandidateRelayProbeEnvironment,
    private val networkEpoch: () -> CandidatePhysicalNetworkToken?,
) {
    @Inject
    internal constructor(
        resolver: AmneziaWgRuntimeConfigResolver,
        runtime: ActiveAwgRuntimeRegistry,
        http: CandidateHttpPayloadProbe,
        configuration: CandidateRelayProbeConfiguration,
        network: CandidateRelayNetworkEpoch,
    ) : this(resolver, runtime, http, configuration::capture, network::capture)

    suspend fun measure(
        request: AwgActivationRequest,
        url: String,
    ): AwgMigrationMeasurement {
        val expected =
            try {
                resolver.resolve(request)
            } catch (_: IllegalArgumentException) {
                null
            }
        val captured = expected?.let(runtime::capture)
        val epoch = captured?.let { networkEpoch() }
        return when {
            captured == null -> AwgMigrationMeasurement.ConnectTargetFirst
            epoch == null -> AwgMigrationMeasurement.EnvironmentChanged
            else -> probe(captured, epoch, url)
        }
    }

    private suspend fun probe(
        captured: ActiveAwgRuntimeRegistry.Registration,
        epoch: CandidatePhysicalNetworkToken,
        url: String,
    ): AwgMigrationMeasurement {
        val before = environment()
        if (!current(captured, epoch)) return AwgMigrationMeasurement.EnvironmentChanged
        return try {
            val result = http.probe(RelayProbeEndpoint("127.0.0.1", AmneziaWgLocalSocksPort), url)
            currentCoroutineContext().ensureActive()
            when {
                !current(captured, epoch) || before != environment() -> AwgMigrationMeasurement.EnvironmentChanged
                result.succeeded -> AwgMigrationMeasurement.Succeeded
                else -> AwgMigrationMeasurement.Failed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AwgMigrationMeasurement.Failed
        }
    }

    private fun current(
        captured: ActiveAwgRuntimeRegistry.Registration,
        epoch: CandidatePhysicalNetworkToken,
    ): Boolean = runtime.isCurrent(captured) && epoch == networkEpoch()
}
