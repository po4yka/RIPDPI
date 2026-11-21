package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.runningConfiguration
import com.poyka.ripdpi.services.CandidateRelayPayloadProbe
import com.poyka.ripdpi.services.CandidateRelayProbeEnvironment
import com.poyka.ripdpi.services.PhysicalNetworkObserver
import javax.inject.Inject
import javax.inject.Singleton

internal data class SelectorProbeScope(
    val members: List<ProxyProfile>,
    val failover: SelectorFailover?,
    val cloudflareMemberIds: Set<String>,
    val memberHealth: Map<String, com.poyka.ripdpi.data.subscription.EndpointHealth>,
    val networkScope: String,
    val underlayGeneration: Long?,
    val networkEpoch: com.poyka.ripdpi.services.CandidatePhysicalNetworkToken,
    val environment: CandidateRelayProbeEnvironment,
    val vpnPath: SelectorVpnPath? = null,
) {
    /** Member health changes with every bundle refresh: each pass reads it, but it never resets failover state. */
    fun sameProbe(other: SelectorProbeScope?) = other != null && copy(memberHealth = other.memberHealth) == other
}

internal fun interface SelectorProbeScopeProvider {
    suspend fun capture(group: ProxyGroup): SelectorProbeScope?

    fun isCurrent(scope: SelectorProbeScope): Boolean = true
}

/** Compares repository inputs and transient network/runtime context without logging network identities. */
@Singleton
internal class CurrentSelectorProbeScopeProvider(
    private val groups: ProxyGroupRepository,
    private val network: NetworkFingerprintProvider,
    private val environment: suspend () -> CandidateRelayProbeEnvironment,
    private val networkEpoch: () -> com.poyka.ripdpi.services.CandidatePhysicalNetworkToken?,
    private val carriesTraffic: (String) -> Boolean,
    private val captureVpnPath: suspend (String) -> SelectorVpnPath? = { null },
    private val isVpnPathCurrent: (SelectorVpnPath) -> Boolean = { false },
) : SelectorProbeScopeProvider {
    @Inject
    constructor(
        groups: ProxyGroupRepository,
        network: NetworkFingerprintProvider,
        payloadProbe: CandidateRelayPayloadProbe,
        networkEpoch: PhysicalNetworkObserver,
        applied: AppliedRuntimeConfigurationSource,
        vpn: SelectorVpnPayloadProbe,
    ) : this(
        groups,
        network,
        payloadProbe::captureEnvironment,
        networkEpoch::capture,
        carriesTraffic = { groupId ->
            applied.applications.value.values.any {
                it.runningConfiguration()?.effectiveSelection?.selectorGroupId == groupId
            }
        },
        captureVpnPath = vpn::capture,
        isVpnPathCurrent = vpn::isCurrent,
    )

    override fun isCurrent(scope: SelectorProbeScope): Boolean {
        val fingerprint = network.capture() ?: return false
        val sameNetwork =
            fingerprint.scopeKey() == scope.networkScope &&
                fingerprint.directDnsUnderlayGeneration == scope.underlayGeneration
        return sameNetwork && networkEpoch() == scope.networkEpoch && (scope.vpnPath?.let(isVpnPathCurrent) ?: true)
    }

    override suspend fun capture(group: ProxyGroup): SelectorProbeScope? {
        // Only the group whose member carries the running session's traffic is probed; an inactive group, an
        // AmneziaWG or Xray session, or a stopped one gains nothing from ephemeral relays.
        if (!carriesTraffic(group.id)) return null
        val current = groups.list().firstOrNull { it.id == group.id }
        return if (current != null && current.matchesProbePolicy(group)) {
            val epoch = networkEpoch()
            val fingerprint = network.capture()
            if (epoch != null && fingerprint != null) {
                val runtimeEnvironment = environment()
                val vpnPath = if (group.failover?.payloadPolicy != null) captureVpnPath(group.id) else null
                val latest = groups.list().firstOrNull { it.id == group.id }
                if (latest?.matchesProbePolicy(current) == true && epoch == networkEpoch() &&
                    carriesTraffic(group.id)
                ) {
                    SelectorProbeScope(
                        members = current.members,
                        failover = current.failover,
                        cloudflareMemberIds = current.cloudflareMemberIds,
                        memberHealth = current.memberHealth,
                        networkScope = fingerprint.scopeKey(),
                        underlayGeneration = fingerprint.directDnsUnderlayGeneration,
                        environment = runtimeEnvironment,
                        networkEpoch = epoch,
                        vpnPath = vpnPath,
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
