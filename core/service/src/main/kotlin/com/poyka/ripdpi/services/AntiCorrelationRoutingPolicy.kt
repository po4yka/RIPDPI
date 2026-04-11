package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.data.AsnRoutingMapCatalog
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.asnRoutingMapCatalogFromJson
import com.poyka.ripdpi.data.normalizePreferredEdgeCandidates
import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val AntiCorrelationPromotionBonus = 1_000

interface AsnRoutingCatalogProvider {
    fun load(): AsnRoutingMapCatalog
}

interface AntiCorrelationRoutingPolicy {
    fun apply(
        settings: AppSettings,
        preferredEdges: Map<String, List<PreferredEdgeCandidate>>,
    ): Map<String, List<PreferredEdgeCandidate>>
}

@Singleton
class AssetAsnRoutingCatalogProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : AsnRoutingCatalogProvider {
        private val cache = AtomicReference<AsnRoutingMapCatalog?>()

        override fun load(): AsnRoutingMapCatalog =
            cache.get()
                ?: context.assets
                    .open(AsnRoutingMapAssetPath)
                    .bufferedReader()
                    .use { reader -> asnRoutingMapCatalogFromJson(reader.readText()) }
                    .also(cache::set)

        private companion object {
            const val AsnRoutingMapAssetPath = "integrations/asn-routing-map.json"
        }
    }

@Singleton
class DefaultAntiCorrelationRoutingPolicy
    @Inject
    constructor(
        private val asnRoutingCatalogProvider: AsnRoutingCatalogProvider,
    ) : AntiCorrelationRoutingPolicy {
        override fun apply(
            settings: AppSettings,
            preferredEdges: Map<String, List<PreferredEdgeCandidate>>,
        ): Map<String, List<PreferredEdgeCandidate>> {
            if (!settings.antiCorrelationEnabled || settings.fullTunnelMode || preferredEdges.isEmpty()) {
                return preferredEdges
            }

            val domesticProviders =
                asnRoutingCatalogProvider
                    .load()
                    .entries
                    .asSequence()
                    .filter { entry -> entry.country.equals("RU", ignoreCase = true) }
                    .map { entry -> entry.label.trim().lowercase() }
                    .filter(String::isNotEmpty)
                    .toSet()
            if (domesticProviders.isEmpty()) {
                return preferredEdges
            }

            return preferredEdges
                .mapValues { (_, candidates) ->
                    normalizePreferredEdgeCandidates(
                        candidates.map { candidate ->
                            if (candidate.shouldPromoteForAntiCorrelation(domesticProviders)) {
                                candidate.copy(successCount = candidate.successCount + AntiCorrelationPromotionBonus)
                            } else {
                                candidate
                            }
                        },
                    )
                }.filterValues { it.isNotEmpty() }
        }

        private fun PreferredEdgeCandidate.shouldPromoteForAntiCorrelation(domesticProviders: Set<String>): Boolean {
            val provider = cdnProvider?.trim()?.lowercase().orEmpty()
            return provider.isNotEmpty() && provider !in domesticProviders
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AntiCorrelationRoutingPolicyModule {
    @Binds
    @Singleton
    abstract fun bindAsnRoutingCatalogProvider(provider: AssetAsnRoutingCatalogProvider): AsnRoutingCatalogProvider

    @Binds
    @Singleton
    abstract fun bindAntiCorrelationRoutingPolicy(
        policy: DefaultAntiCorrelationRoutingPolicy,
    ): AntiCorrelationRoutingPolicy
}
