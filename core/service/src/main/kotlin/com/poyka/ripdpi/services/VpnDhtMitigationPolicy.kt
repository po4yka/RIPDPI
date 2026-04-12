@file:Suppress("ReturnCount", "MaxLineLength")

package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DhtMitigationModeBypass
import com.poyka.ripdpi.data.DhtMitigationModeDropWarn
import com.poyka.ripdpi.data.DhtTriggerCidrsCatalog
import com.poyka.ripdpi.data.dhtTriggerCidrsCatalogFromJson
import com.poyka.ripdpi.data.normalizeDhtMitigationMode
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class VpnExcludedRoute(
    val address: String,
    val prefixLength: Int,
)

data class VpnDhtMitigationPlan(
    val excludedRoutes: List<VpnExcludedRoute> = emptyList(),
    val warningMessage: String? = null,
)

interface DhtTriggerCidrsCatalogProvider {
    fun load(): DhtTriggerCidrsCatalog
}

interface VpnDhtMitigationPolicy {
    fun buildPlan(supportsRouteExclusion: Boolean): VpnDhtMitigationPlan
}

@Singleton
class AssetDhtTriggerCidrsCatalogProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : DhtTriggerCidrsCatalogProvider {
        private val cache = AtomicReference<DhtTriggerCidrsCatalog?>()

        override fun load(): DhtTriggerCidrsCatalog =
            cache.get()
                ?: context.assets
                    .open(DhtTriggerCidrsAssetPath)
                    .bufferedReader()
                    .use { reader -> dhtTriggerCidrsCatalogFromJson(reader.readText()) }
                    .also(cache::set)

        private companion object {
            const val DhtTriggerCidrsAssetPath = "integrations/dht-trigger-cidrs.json"
        }
    }

@Singleton
class DefaultVpnDhtMitigationPolicy
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val catalogProvider: DhtTriggerCidrsCatalogProvider,
    ) : VpnDhtMitigationPolicy {
        override fun buildPlan(supportsRouteExclusion: Boolean): VpnDhtMitigationPlan {
            val settings = runBlocking { appSettingsRepository.snapshot() }
            if (settings.fullTunnelMode) {
                return VpnDhtMitigationPlan()
            }

            return when (normalizeDhtMitigationMode(settings.dhtMitigationMode)) {
                DhtMitigationModeBypass -> {
                    val routes = catalogProvider.load().cidrs.mapNotNull(::parseRoute)
                    if (routes.isEmpty()) {
                        VpnDhtMitigationPlan()
                    } else if (supportsRouteExclusion) {
                        VpnDhtMitigationPlan(excludedRoutes = routes)
                    } else {
                        VpnDhtMitigationPlan(
                            warningMessage =
                                "DHT mitigation bypass is enabled, but this Android version does not support route exclusion.",
                        )
                    }
                }

                DhtMitigationModeDropWarn -> {
                    VpnDhtMitigationPlan(
                        warningMessage = "DHT mitigation is configured for drop+warn; route exclusion is not applied.",
                    )
                }

                else -> {
                    VpnDhtMitigationPlan()
                }
            }
        }

        private fun parseRoute(value: String): VpnExcludedRoute? {
            val separator = value.indexOf('/')
            if (separator <= 0 || separator == value.lastIndex) {
                return null
            }
            val address = value.substring(0, separator).trim()
            val prefixLength = value.substring(separator + 1).trim().toIntOrNull() ?: return null
            if (address.isEmpty()) {
                return null
            }
            return VpnExcludedRoute(address = address, prefixLength = prefixLength)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class VpnDhtMitigationPolicyModule {
    @Binds
    @Singleton
    abstract fun bindDhtTriggerCidrsCatalogProvider(
        provider: AssetDhtTriggerCidrsCatalogProvider,
    ): DhtTriggerCidrsCatalogProvider

    @Binds
    @Singleton
    abstract fun bindVpnDhtMitigationPolicy(policy: DefaultVpnDhtMitigationPolicy): VpnDhtMitigationPolicy
}
