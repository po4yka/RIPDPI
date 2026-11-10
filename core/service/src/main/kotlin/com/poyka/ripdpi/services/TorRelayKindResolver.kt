package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.torBridgeValidationError
import javax.inject.Inject

internal class TorRelayKindResolver
    @Inject
    constructor() : RelayKindResolver {
        override fun supports(kind: String): Boolean = kind == RelayKindTor

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
            val effectiveConfig = request.mergedConfig.copy(udpEnabled = false)
            require(torBridgeValidationError(effectiveConfig.ptBridgeLine) == null) {
                "Tor relay backend requires a valid managed-transport bridge line"
            }
            validateSharedRelayTransportFeatures(effectiveConfig)
            validateFinalmaskFeature(effectiveConfig, request.featureFlags)

            return RelayResolverResult(
                effectiveConfig = effectiveConfig,
                effectiveTlsProfile = request.requestedTlsProfile,
            )
        }
    }
