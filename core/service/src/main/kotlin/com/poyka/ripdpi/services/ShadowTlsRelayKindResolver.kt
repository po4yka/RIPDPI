package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayProfileStore
import javax.inject.Inject

internal class ShadowTlsRelayKindResolver
    @Inject
    constructor(
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
    ) : RelayKindResolver {
        override fun supports(kind: String): Boolean = kind == RelayKindShadowTlsV3

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
            validateShadowTlsRelayCredentials(request.profileId, request.credentials)
            validateSharedRelayTransportFeatures(request.mergedConfig)
            validateShadowTlsRelayFeatures(request.profileId, request.mergedConfig)
            validateFinalmaskFeature(request.mergedConfig, request.featureFlags)

            val shadowTlsInner =
                resolveShadowTlsInnerConfigSupport(
                    outerProfileId = request.profileId,
                    innerProfileId = request.mergedConfig.shadowTlsInnerProfileId,
                    relayProfileStore = relayProfileStore,
                    relayCredentialStore = relayCredentialStore,
                    fallbackTlsFingerprintProfile = request.requestedTlsProfile,
                )

            return RelayResolverResult(
                effectiveConfig = request.mergedConfig,
                effectiveTlsProfile = request.requestedTlsProfile,
                shadowTlsInner = shadowTlsInner,
            )
        }
    }
