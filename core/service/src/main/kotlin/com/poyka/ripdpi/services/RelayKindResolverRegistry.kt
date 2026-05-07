package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileStore
import javax.inject.Inject
import javax.inject.Singleton

internal class RelayKindResolverDispatcher(
    private val relayKindResolvers: List<RelayKindResolver>,
) {
    suspend fun resolve(request: RelayResolverRequest): RelayResolverResult =
        relayKindResolvers.firstOrNull { it.supports(request.mergedConfig.kind) }?.resolve(request)
            ?: error("Missing relay resolver for kind ${request.mergedConfig.kind}")
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
        naiveRelayKindResolver: NaiveRelayKindResolver,
        localPathRelayKindResolver: LocalPathRelayKindResolver,
        defaultRelayKindResolver: DefaultRelayKindResolver,
    ) {
        private val dispatcher =
            RelayKindResolverDispatcher(
                relayKindResolvers =
                    listOf(
                        masqueRelayKindResolver,
                        cloudflareTunnelRelayKindResolver,
                        snowflakeRelayKindResolver,
                        chainRelayKindResolver,
                        shadowTlsRelayKindResolver,
                        naiveRelayKindResolver,
                        localPathRelayKindResolver,
                        defaultRelayKindResolver,
                    ),
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
        naiveRelayKindResolver = NaiveRelayKindResolver(),
        localPathRelayKindResolver = LocalPathRelayKindResolver(),
        defaultRelayKindResolver = DefaultRelayKindResolver(),
    )
