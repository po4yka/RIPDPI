package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindTrojan
import javax.inject.Inject

internal class TrojanRelayKindResolver
    @Inject
    constructor() : RelayKindResolver {
        override fun supports(kind: String): Boolean = kind == RelayKindTrojan

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
            validateTrojanRelayCredentials(request.profileId, request.credentials)
            validateSharedRelayTransportFeatures(request.mergedConfig)
            validateTrojanRelayFeatures(request.mergedConfig)
            validateFinalmaskFeature(request.mergedConfig, request.featureFlags)

            return RelayResolverResult(
                effectiveConfig = request.mergedConfig,
                effectiveTlsProfile = request.requestedTlsProfile,
            )
        }
    }
