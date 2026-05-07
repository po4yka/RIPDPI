package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayProfileStore
import javax.inject.Inject

internal class ChainRelayKindResolver
    @Inject
    constructor(
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
    ) : RelayKindResolver {
        override fun supports(kind: String): Boolean = kind == RelayKindChainRelay

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
            val resolvedChainRelay =
                resolveChainRelayConfigSupport(
                    chainProfileId = request.profileId,
                    config = request.mergedConfig,
                    credentials = request.credentials,
                    relayProfileStore = relayProfileStore,
                    relayCredentialStore = relayCredentialStore,
                )

            validateSharedRelayTransportFeatures(request.mergedConfig)
            validateFinalmaskFeature(request.mergedConfig, request.featureFlags)

            return RelayResolverResult(
                effectiveConfig = request.mergedConfig,
                effectiveTlsProfile = request.requestedTlsProfile,
                resolvedChainRelay = resolvedChainRelay,
            )
        }
    }
