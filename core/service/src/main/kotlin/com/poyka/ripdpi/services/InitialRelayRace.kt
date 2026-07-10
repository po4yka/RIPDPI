package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.InitialTransportRaceSnapshot
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

enum class InitialRelayTransportClass(
    val wireValue: String,
) {
    TlsMimicry("tls_mimicry"),
    UdpObfuscation("udp_obfuscation"),
}

data class InitialRelayCandidate(
    val transportClass: InitialRelayTransportClass,
    val profileId: String,
    val relayKind: String,
)

data class InitialRelayRacePlan(
    val probeUrl: String,
    val candidates: List<InitialRelayCandidate>,
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
)

interface InitialRelayRacePolicy {
    suspend fun plan(
        configuredRelayProfileId: String?,
        configuredRelayKind: String?,
        networkScopeKey: String?,
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
