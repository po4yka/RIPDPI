package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import javax.inject.Inject

internal class CloudflareTunnelRelayKindResolver
    @Inject
    constructor() : RelayKindResolver {
        override fun supports(kind: String): Boolean = kind == RelayKindCloudflareTunnel

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
            val effectiveConfig =
                request.mergedConfig.copy(
                    vlessTransport = RelayVlessTransportXhttp,
                    udpEnabled = false,
                )

            validateCloudflareTunnelCredentials(request.profileId, request.credentials)
            validateSharedRelayTransportFeatures(effectiveConfig)
            validateCloudflareTunnelFeatures(
                config = effectiveConfig,
                credentials = request.credentials,
                tlsFingerprintProfile = request.requestedTlsProfile,
                featureFlags = request.featureFlags,
            )
            validateFinalmaskFeature(effectiveConfig, request.featureFlags)

            return RelayResolverResult(
                effectiveConfig = effectiveConfig,
                effectiveTlsProfile = TlsFingerprintProfileChromeStable,
            )
        }
    }
