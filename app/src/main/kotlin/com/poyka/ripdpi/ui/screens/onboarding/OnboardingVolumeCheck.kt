package com.poyka.ripdpi.ui.screens.onboarding

import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PayloadProbeControl
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.runningConfiguration
import com.poyka.ripdpi.services.CandidateHttpPayloadEvidence
import com.poyka.ripdpi.services.CandidateHttpPayloadProbe
import com.poyka.ripdpi.services.LocalSocksRoute
import com.poyka.ripdpi.services.RelayProbeEndpoint
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

private const val MillisecondsPerSecond = 1_000L

sealed interface OnboardingVolumeOutcome {
    data object NotTested : OnboardingVolumeOutcome

    data object Verified : OnboardingVolumeOutcome

    data object Rejected : OnboardingVolumeOutcome

    data class Stalled(
        val receivedBytes: Long,
    ) : OnboardingVolumeOutcome
}

fun interface OnboardingVolumeTransfer {
    suspend fun transfer(
        endpoint: RelayProbeEndpoint,
        control: PayloadProbeControl,
        timeoutMillis: Long,
    ): CandidateHttpPayloadEvidence
}

class DefaultOnboardingVolumeTransfer
    @Inject
    constructor(
        private val probe: CandidateHttpPayloadProbe,
    ) : OnboardingVolumeTransfer {
        override suspend fun transfer(
            endpoint: RelayProbeEndpoint,
            control: PayloadProbeControl,
            timeoutMillis: Long,
        ): CandidateHttpPayloadEvidence = probe.probeControl(endpoint, control, timeoutMillis)
    }

class OnboardingVolumeCheck
    @Inject
    constructor(
        private val groups: ProxyGroupRepository,
        private val applied: AppliedRuntimeConfigurationSource,
        private val transfer: OnboardingVolumeTransfer,
    ) {
        suspend fun run(
            mode: Mode,
            route: LocalSocksRoute,
        ): OnboardingVolumeOutcome {
            val plan = if (route.resolveNamesLocally) null else volumePlan(mode)
            return plan?.let { (controls, timeoutMillis) ->
                val endpoint =
                    RelayProbeEndpoint(
                        route.host,
                        route.port,
                        route.credentials?.let { DiagnosticsProxyCredentials(it.username, it.password) },
                    )
                transferAll(endpoint, controls, timeoutMillis)
            } ?: OnboardingVolumeOutcome.NotTested
        }

        private suspend fun transferAll(
            endpoint: RelayProbeEndpoint,
            controls: List<PayloadProbeControl>,
            timeoutMillis: Long,
        ): OnboardingVolumeOutcome {
            var stalledBytes: Long? = null
            for (control in controls) {
                val evidence = transfer.transfer(endpoint, control, timeoutMillis)
                if (evidence.succeeded) return OnboardingVolumeOutcome.Verified
                val received = evidence.receivedBytes ?: 0L
                if (evidence.timedOut || received in 1 until control.expectedBytes) {
                    stalledBytes = maxOf(stalledBytes ?: 0L, received)
                }
            }
            return stalledBytes?.let { OnboardingVolumeOutcome.Stalled(it) } ?: OnboardingVolumeOutcome.Rejected
        }

        private suspend fun volumePlan(mode: Mode): Pair<List<PayloadProbeControl>, Long>? {
            val groupId =
                applied.applications.value[mode]
                    ?.runningConfiguration()
                    ?.effectiveSelection
                    ?.selectorGroupId
            val policy =
                groupId?.let { id ->
                    groups
                        .list()
                        .firstOrNull { it.id == id }
                        ?.failover
                        ?.payloadPolicy
                }
            return policy?.let { found ->
                found.probes.sortedByDescending { it.expectedBytes } to found.timeoutSeconds * MillisecondsPerSecond
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class OnboardingVolumeCheckModule {
    @Binds
    abstract fun bindOnboardingVolumeTransfer(transfer: DefaultOnboardingVolumeTransfer): OnboardingVolumeTransfer
}
