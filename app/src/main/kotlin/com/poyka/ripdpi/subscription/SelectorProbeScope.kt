package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.services.CandidateRelayNetworkEpoch
import com.poyka.ripdpi.services.CandidateRelayPayloadProbe
import com.poyka.ripdpi.services.CandidateRelayProbeEnvironment
import javax.inject.Inject
import javax.inject.Singleton

internal data class SelectorProbeScope(
    val members: List<ProxyProfile>,
    val failover: SelectorFailover?,
    val cloudflareMemberIds: Set<String>,
    val networkScope: String,
    val underlayGeneration: Long?,
    val networkEpoch: com.poyka.ripdpi.services.CandidatePhysicalNetworkToken,
    val environment: CandidateRelayProbeEnvironment,
)

internal fun interface SelectorProbeScopeProvider {
    suspend fun capture(group: ProxyGroup): SelectorProbeScope?
}

/** Compares repository inputs and transient network/runtime context without logging network identities. */
@Singleton
internal class CurrentSelectorProbeScopeProvider(
    private val groups: ProxyGroupRepository,
    private val network: NetworkFingerprintProvider,
    private val environment: suspend () -> CandidateRelayProbeEnvironment,
    private val networkEpoch: () -> com.poyka.ripdpi.services.CandidatePhysicalNetworkToken?,
) : SelectorProbeScopeProvider {
    @Inject
    constructor(
        groups: ProxyGroupRepository,
        network: NetworkFingerprintProvider,
        payloadProbe: CandidateRelayPayloadProbe,
        networkEpoch: CandidateRelayNetworkEpoch,
    ) : this(groups, network, payloadProbe::captureEnvironment, networkEpoch::capture)

    override suspend fun capture(group: ProxyGroup): SelectorProbeScope? {
        val current = groups.list().firstOrNull { it.id == group.id } ?: return null
        return if (current.matchesProbePolicy(group)) {
            val epoch = networkEpoch()
            val fingerprint = network.capture()
            if (epoch != null && fingerprint != null) {
                val runtimeEnvironment = environment()
                val latest = groups.list().firstOrNull { it.id == group.id }
                if (latest?.matchesProbePolicy(current) == true && epoch == networkEpoch()) {
                    SelectorProbeScope(
                        members = current.members,
                        failover = current.failover,
                        cloudflareMemberIds = current.cloudflareMemberIds,
                        networkScope = fingerprint.scopeKey(),
                        underlayGeneration = fingerprint.directDnsUnderlayGeneration,
                        environment = runtimeEnvironment,
                        networkEpoch = epoch,
                    )
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            null
        }
    }

    private fun ProxyGroup.matchesProbePolicy(other: ProxyGroup): Boolean =
        isSelector && other.isSelector && members == other.members && failover == other.failover &&
            cloudflareMemberIds == other.cloudflareMemberIds
}
