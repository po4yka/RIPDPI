package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindWebTunnel
import javax.inject.Inject

internal class LocalPathRelayKindResolver
    @Inject
    constructor() : RelayKindResolver {
        override fun supports(kind: String): Boolean = kind == RelayKindWebTunnel || kind == RelayKindObfs4

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
            validateSharedRelayTransportFeatures(request.mergedConfig)
            validatePluggableTransportLoopbackFeatures(request.mergedConfig)
            when (request.mergedConfig.kind) {
                RelayKindWebTunnel -> validateWebTunnelRelayFeatures(request.mergedConfig)
                RelayKindObfs4 -> validateObfs4RelayFeatures(request.mergedConfig)
            }
            validateFinalmaskFeature(request.mergedConfig, request.featureFlags)

            return RelayResolverResult(
                effectiveConfig = request.mergedConfig,
                effectiveTlsProfile = request.requestedTlsProfile,
            )
        }
    }
