package com.poyka.ripdpi.ui.screens.onboarding

import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PayloadProbeControl
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.TcpFreezeThresholdBytes
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
            val selected = if (route.resolveNamesLocally) null else volumeControl(mode)
            val evidence =
                selected?.let { (control, timeoutMillis) ->
                    val endpoint =
                        RelayProbeEndpoint(
                            route.host,
                            route.port,
                            route.credentials?.let { DiagnosticsProxyCredentials(it.username, it.password) },
                        )
                    transfer.transfer(endpoint, control, timeoutMillis)
                }
            return when {
                evidence == null -> OnboardingVolumeOutcome.NotTested
                evidence.succeeded -> OnboardingVolumeOutcome.Verified
                else -> OnboardingVolumeOutcome.Stalled(evidence.receivedBytes ?: 0L)
            }
        }

        private suspend fun volumeControl(mode: Mode): Pair<PayloadProbeControl, Long>? {
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
            return policy
                ?.probes
                ?.filter { it.expectedBytes > TcpFreezeThresholdBytes }
                ?.maxByOrNull { it.expectedBytes }
                ?.let { it to policy.timeoutSeconds * MillisecondsPerSecond }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class OnboardingVolumeCheckModule {
    @Binds
    abstract fun bindOnboardingVolumeTransfer(transfer: DefaultOnboardingVolumeTransfer): OnboardingVolumeTransfer
}
