package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindNaiveProxy
import javax.inject.Inject

internal class NaiveRelayKindResolver
    @Inject
    constructor() : RelayKindResolver {
        override fun supports(kind: String): Boolean = kind == RelayKindNaiveProxy

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
            validateNaiveRelayCredentials(request.profileId, request.credentials)
            validateSharedRelayTransportFeatures(request.mergedConfig)
            validateNaiveRelayFeatures(
                config = request.mergedConfig,
                featureFlags = request.featureFlags,
            )
            validateFinalmaskFeature(request.mergedConfig, request.featureFlags)

            return RelayResolverResult(
                effectiveConfig = request.mergedConfig,
                effectiveTlsProfile = request.requestedTlsProfile,
            )
        }
    }
