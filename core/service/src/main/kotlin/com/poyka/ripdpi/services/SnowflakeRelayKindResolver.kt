package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.RelayKindSnowflake
import javax.inject.Inject

internal class SnowflakeRelayKindResolver
    @Inject
    constructor() : RelayKindResolver {
        override fun supports(kind: String): Boolean = kind == RelayKindSnowflake

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
            val effectiveConfig =
                request.mergedConfig.copy(
                    udpEnabled = false,
                    ptSnowflakeBrokerUrl =
                        request.mergedConfig.ptSnowflakeBrokerUrl.ifBlank {
                            DefaultSnowflakeBrokerUrl
                        },
                    ptSnowflakeFrontDomain =
                        request.mergedConfig.ptSnowflakeFrontDomain.ifBlank {
                            DefaultSnowflakeFrontDomain
                        },
                )

            validateSharedRelayTransportFeatures(effectiveConfig)
            validatePluggableTransportLoopbackFeatures(effectiveConfig)
            validateSnowflakeRelayFeatures(effectiveConfig)
            validateFinalmaskFeature(effectiveConfig, request.featureFlags)

            return RelayResolverResult(
                effectiveConfig = effectiveConfig,
                effectiveTlsProfile = request.requestedTlsProfile,
            )
        }
    }
