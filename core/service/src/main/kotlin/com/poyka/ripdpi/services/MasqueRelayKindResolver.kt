package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import javax.inject.Inject

internal class MasqueRelayKindResolver
    @Inject
    constructor(
        private val cloudflareMasqueGeohashResolver: CloudflareMasqueGeohashResolver,
        private val masquePrivacyPassProvider: MasquePrivacyPassProvider,
    ) : RelayKindResolver {
        override fun supports(kind: String): Boolean = kind == RelayKindMasque

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
            val effectiveConfig = request.mergedConfig
            validateMasqueTcpProtocolSupport(effectiveConfig)
            val masqueAuthMode = resolveMasqueAuthModeSupport(request.credentials)
            val privacyPassReadiness =
                if (masqueAuthMode == RelayMasqueAuthModePrivacyPass) {
                    masquePrivacyPassProvider.readinessFor(effectiveConfig, request.credentials)
                } else {
                    null
                }
            val privacyPassRuntime =
                if (masqueAuthMode == RelayMasqueAuthModePrivacyPass) {
                    masquePrivacyPassProvider.resolve(request.profileId, effectiveConfig, request.credentials)
                } else {
                    null
                }

            validateMasqueRelayCredentials(request.profileId, masqueAuthMode, request.credentials)
            validateSharedRelayTransportFeatures(effectiveConfig)
            validateMasqueRelayFeatures(
                profileId = request.profileId,
                config = effectiveConfig,
                masqueAuthMode = masqueAuthMode,
                privacyPassRuntime = privacyPassRuntime,
                privacyPassReadiness = privacyPassReadiness,
                featureFlags = request.featureFlags,
            )
            validateFinalmaskFeature(effectiveConfig, request.featureFlags)

            val masqueCloudflareGeohashHeader =
                if (masqueAuthMode == RelayMasqueAuthModeCloudflareMtls &&
                    effectiveConfig.masqueCloudflareGeohashEnabled
                ) {
                    cloudflareMasqueGeohashResolver.resolveHeaderValue()
                } else {
                    null
                }
            return RelayResolverResult(
                effectiveConfig = effectiveConfig,
                effectiveTlsProfile = request.requestedTlsProfile,
                masqueAuthMode = masqueAuthMode,
                privacyPassRuntime = privacyPassRuntime,
                masqueCloudflareGeohashHeader = masqueCloudflareGeohashHeader,
            )
        }
    }
