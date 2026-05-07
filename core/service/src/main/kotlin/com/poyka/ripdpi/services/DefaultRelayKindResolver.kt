package com.poyka.ripdpi.services

import javax.inject.Inject

internal class DefaultRelayKindResolver
    @Inject
    constructor() : RelayKindResolver {
        override fun supports(kind: String): Boolean = true

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
            validateDefaultRelayCredentials(
                profileId = request.profileId,
                relayKind = request.mergedConfig.kind,
                credentials = request.credentials,
            )
            validateSharedRelayTransportFeatures(request.mergedConfig)
            validateDefaultRelayFeatures(
                config = request.mergedConfig,
                tlsFingerprintProfile = request.requestedTlsProfile,
            )
            validateFinalmaskFeature(request.mergedConfig, request.featureFlags)

            return RelayResolverResult(
                effectiveConfig = request.mergedConfig,
                effectiveTlsProfile = request.requestedTlsProfile,
            )
        }
    }
