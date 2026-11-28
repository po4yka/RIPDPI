package com.poyka.ripdpi.ui.screens.onboarding

import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PayloadProbeControl
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.runningConfiguration
import com.poyka.ripdpi.services.CandidateHttpPayloadEvidence
import com.poyka.ripdpi.services.CandidateHttpPayloadProbe
import com.poyka.ripdpi.services.LocalSocksRoute
import com.poyka.ripdpi.services.RelayChainStallLocator
import com.poyka.ripdpi.services.RelayChainVolumeStop
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
        val hop: Int? = null,
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

fun interface OnboardingChainStallLocator {
    suspend fun locate(
        chainProfileId: String,
        control: PayloadProbeControl,
        timeoutMillis: Long,
    ): RelayChainVolumeStop
}

class DefaultOnboardingChainStallLocator
    @Inject
    constructor(
        private val locator: RelayChainStallLocator,
    ) : OnboardingChainStallLocator {
        override suspend fun locate(
            chainProfileId: String,
            control: PayloadProbeControl,
            timeoutMillis: Long,
        ): RelayChainVolumeStop = locator.locate(chainProfileId, control, timeoutMillis)
    }

class OnboardingVolumeCheck
    @Inject
    constructor(
        private val groups: ProxyGroupRepository,
        private val applied: AppliedRuntimeConfigurationSource,
        private val transfer: OnboardingVolumeTransfer,
        private val chainLocator: OnboardingChainStallLocator,
    ) {
        suspend fun run(
            mode: Mode,
            route: LocalSocksRoute,
        ): OnboardingVolumeOutcome {
            val selection =
                applied.applications.value[mode]
                    ?.runningConfiguration()
                    ?.effectiveSelection
                    ?.takeUnless { route.resolveNamesLocally }
            return selection?.let { measure(it, route) } ?: OnboardingVolumeOutcome.NotTested
        }

        private suspend fun measure(
            selection: RuntimeConfigurationSelection,
            route: LocalSocksRoute,
        ): OnboardingVolumeOutcome? =
            volumePlan(selection)?.let { (controls, timeoutMillis) ->
                val endpoint =
                    RelayProbeEndpoint(
                        route.host,
                        route.port,
                        route.credentials?.let { DiagnosticsProxyCredentials(it.username, it.password) },
                        route.unixPath,
                    )
                val outcome = transferAll(endpoint, controls, timeoutMillis)
                val chainId = selection.profileId?.takeIf { selection.relayKind == RelayKindChainRelay }
                if (outcome is OnboardingVolumeOutcome.Stalled && chainId != null) {
                    locateStall(outcome, chainId, controls.first(), timeoutMillis)
                } else {
                    outcome
                }
            }

        private suspend fun locateStall(
            outcome: OnboardingVolumeOutcome.Stalled,
            chainId: String,
            control: PayloadProbeControl,
            timeoutMillis: Long,
        ): OnboardingVolumeOutcome.Stalled {
            val stop = chainLocator.locate(chainId, control, timeoutMillis)
            return if (stop is RelayChainVolumeStop.Stopped) {
                OnboardingVolumeOutcome.Stalled(stop.receivedBytes, stop.hop)
            } else {
                outcome
            }
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

        private suspend fun volumePlan(
            selection: RuntimeConfigurationSelection,
        ): Pair<List<PayloadProbeControl>, Long>? {
            val available = groups.list()
            val policy =
                available.firstOrNull { it.id == selection.selectorGroupId }?.failover?.payloadPolicy
                    ?: available
                        .firstNotNullOfOrNull { it.failover?.payloadPolicy }
                        ?.takeIf { selection.relayKind == RelayKindChainRelay }
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

    @Binds
    abstract fun bindOnboardingChainStallLocator(
        locator: DefaultOnboardingChainStallLocator,
    ): OnboardingChainStallLocator
}
