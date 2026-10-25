package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RetiredSnowflakeBrokerUrl
import com.poyka.ripdpi.data.RetiredSnowflakeFrontDomain
import javax.inject.Inject

internal class SnowflakeRelayKindResolver
    @Inject
    constructor() : RelayKindResolver {
        override fun supports(kind: String): Boolean = kind == RelayKindSnowflake

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
            val merged = request.mergedConfig
            // Profiles saved with the retired built-in pair get the current built-in pair.
            val retiredDefaults =
                merged.ptSnowflakeBrokerUrl == RetiredSnowflakeBrokerUrl &&
                    merged.ptSnowflakeFrontDomain == RetiredSnowflakeFrontDomain
            val effectiveConfig =
                merged.copy(
                    udpEnabled = false,
                    ptSnowflakeBrokerUrl =
                        if (retiredDefaults) {
                            DefaultSnowflakeBrokerUrl
                        } else {
                            merged.ptSnowflakeBrokerUrl.ifBlank { DefaultSnowflakeBrokerUrl }
                        },
                    ptSnowflakeFrontDomain =
                        if (retiredDefaults) {
                            DefaultSnowflakeFrontDomain
                        } else {
                            merged.ptSnowflakeFrontDomain.ifBlank { DefaultSnowflakeFrontDomain }
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
