package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.InitialTransportRaceSnapshot
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.net.InetSocketAddress

enum class InitialRelayTransportClass(
    val wireValue: String,
) {
    TlsMimicry("tls_mimicry"),
    UdpObfuscation("udp_obfuscation"),
}

data class EgressRequirements(
    val tcpConnect: Boolean = true,
    val udpAssociate: Boolean = true,
    val udpAssociateTarget: InetSocketAddress? = null,
)

data class InitialRelayCandidate(
    val transportClass: InitialRelayTransportClass,
    val profileId: String,
    val relayKind: String,
)

data class InitialRelayRacePlan(
    val probeUrl: String,
    val candidates: List<InitialRelayCandidate>,
    val requirements: EgressRequirements,
    val readinessProbeRequirements: EgressRequirements = requirements,
    val cachedFallbackProfileId: String? = null,
)

data class InitialRelayRaceResult(
    val selectedCandidate: InitialRelayCandidate,
    val usedCachedFallback: Boolean,
    val latencyMs: Long?,
)

internal data class PromotedRelayRuntime(
    val endpoint: LocalProxyEndpoint,
    val result: InitialRelayRaceResult,
    val udpEnabled: Boolean,
)

interface InitialRelayRacePolicy {
    suspend fun plan(
        configuredRelayProfileId: String?,
        configuredRelayKind: String?,
        networkScopeKey: String?,
        requirements: EgressRequirements,
    ): InitialRelayRacePlan?

    fun onStateChanged(state: InitialTransportRaceSnapshot)

    fun onSelected(result: InitialRelayRaceResult)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class InitialRelayRacePolicyModule {
    @BindsOptionalOf
    abstract fun bindOptionalInitialRelayRacePolicy(): InitialRelayRacePolicy
}
