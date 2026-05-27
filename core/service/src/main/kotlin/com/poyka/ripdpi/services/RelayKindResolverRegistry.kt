package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayProfileStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One relay kind's place in the resolution platform: a [RelayKindDescriptor]
 * paired with the [RelayKindResolver] that owns its kind-specific resolution.
 */
internal data class RelayKindRegistration(
    val descriptor: RelayKindDescriptor,
    val resolver: RelayKindResolver,
)

/**
 * Pairs every [RelayKindDescriptor] in [RelayKindDescriptors] with the resolver
 * that owns it.
 *
 * Kinds with dedicated kind-specific logic register their own resolver; the
 * remaining descriptors (the native VLESS paths, Hysteria2, TUIC, Apps Script,
 * and the `off` state) register the catch-all [DefaultRelayKindResolver].
 * `RelayKindDescriptorDriftTest` pins that the table covers every descriptor and
 * that every resolver appears at least once.
 */
internal fun buildRelayKindRegistrations(
    masqueRelayKindResolver: MasqueRelayKindResolver,
    cloudflareTunnelRelayKindResolver: CloudflareTunnelRelayKindResolver,
    snowflakeRelayKindResolver: SnowflakeRelayKindResolver,
    chainRelayKindResolver: ChainRelayKindResolver,
    shadowTlsRelayKindResolver: ShadowTlsRelayKindResolver,
    trojanRelayKindResolver: TrojanRelayKindResolver,
    naiveRelayKindResolver: NaiveRelayKindResolver,
    localPathRelayKindResolver: LocalPathRelayKindResolver,
    defaultRelayKindResolver: DefaultRelayKindResolver,
): List<RelayKindRegistration> {
    val dedicatedResolvers: Map<String, RelayKindResolver> =
        buildMap {
            put(RelayKindMasque, masqueRelayKindResolver)
            put(RelayKindCloudflareTunnel, cloudflareTunnelRelayKindResolver)
            put(RelayKindSnowflake, snowflakeRelayKindResolver)
            put(RelayKindChainRelay, chainRelayKindResolver)
            put(RelayKindShadowTlsV3, shadowTlsRelayKindResolver)
            put(RelayKindTrojan, trojanRelayKindResolver)
            put(RelayKindNaiveProxy, naiveRelayKindResolver)
            put(RelayKindWebTunnel, localPathRelayKindResolver)
            put(RelayKindObfs4, localPathRelayKindResolver)
        }
    return RelayKindDescriptors.map { descriptor ->
        RelayKindRegistration(
            descriptor = descriptor,
            resolver = dedicatedResolvers[descriptor.kindId] ?: defaultRelayKindResolver,
        )
    }
}

internal class RelayKindResolverDispatcher(
    registrations: List<RelayKindRegistration>,
    private val fallbackResolver: RelayKindResolver,
) {
    private val resolversByKind: Map<String, RelayKindResolver> =
        registrations.associate { it.descriptor.kindId to it.resolver }

    // An unrecognised kind has no descriptor row and no registration; it falls
    // back to the catch-all resolver, matching the historical permissive
    // dispatch where the default resolver claimed every kind.
    suspend fun resolve(request: RelayResolverRequest): RelayResolverResult =
        (resolversByKind[request.mergedConfig.kind] ?: fallbackResolver).resolve(request)
}

@Singleton
internal class RelayKindResolverRegistry
    @Inject
    constructor(
        masqueRelayKindResolver: MasqueRelayKindResolver,
        cloudflareTunnelRelayKindResolver: CloudflareTunnelRelayKindResolver,
        snowflakeRelayKindResolver: SnowflakeRelayKindResolver,
        chainRelayKindResolver: ChainRelayKindResolver,
        shadowTlsRelayKindResolver: ShadowTlsRelayKindResolver,
        trojanRelayKindResolver: TrojanRelayKindResolver,
        naiveRelayKindResolver: NaiveRelayKindResolver,
        localPathRelayKindResolver: LocalPathRelayKindResolver,
        defaultRelayKindResolver: DefaultRelayKindResolver,
    ) {
        val registrations: List<RelayKindRegistration> =
            buildRelayKindRegistrations(
                masqueRelayKindResolver = masqueRelayKindResolver,
                cloudflareTunnelRelayKindResolver = cloudflareTunnelRelayKindResolver,
                snowflakeRelayKindResolver = snowflakeRelayKindResolver,
                chainRelayKindResolver = chainRelayKindResolver,
                shadowTlsRelayKindResolver = shadowTlsRelayKindResolver,
                trojanRelayKindResolver = trojanRelayKindResolver,
                naiveRelayKindResolver = naiveRelayKindResolver,
                localPathRelayKindResolver = localPathRelayKindResolver,
                defaultRelayKindResolver = defaultRelayKindResolver,
            )

        private val dispatcher =
            RelayKindResolverDispatcher(
                registrations = registrations,
                fallbackResolver = defaultRelayKindResolver,
            )

        suspend fun resolve(request: RelayResolverRequest): RelayResolverResult = dispatcher.resolve(request)
    }

internal fun createDefaultRelayKindResolverRegistry(
    relayProfileStore: RelayProfileStore,
    relayCredentialStore: RelayCredentialStore,
    cloudflareMasqueGeohashResolver: CloudflareMasqueGeohashResolver,
    masquePrivacyPassProvider: MasquePrivacyPassProvider,
): RelayKindResolverRegistry =
    RelayKindResolverRegistry(
        masqueRelayKindResolver =
            MasqueRelayKindResolver(
                cloudflareMasqueGeohashResolver = cloudflareMasqueGeohashResolver,
                masquePrivacyPassProvider = masquePrivacyPassProvider,
            ),
        cloudflareTunnelRelayKindResolver = CloudflareTunnelRelayKindResolver(),
        snowflakeRelayKindResolver = SnowflakeRelayKindResolver(),
        chainRelayKindResolver =
            ChainRelayKindResolver(
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
            ),
        shadowTlsRelayKindResolver =
            ShadowTlsRelayKindResolver(
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
            ),
        trojanRelayKindResolver = TrojanRelayKindResolver(),
        naiveRelayKindResolver = NaiveRelayKindResolver(),
        localPathRelayKindResolver = LocalPathRelayKindResolver(),
        defaultRelayKindResolver = DefaultRelayKindResolver(),
    )
