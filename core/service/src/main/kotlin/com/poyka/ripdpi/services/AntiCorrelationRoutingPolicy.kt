@file:Suppress("ReturnCount")

package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.data.AsnRoutingMapCatalog
import com.poyka.ripdpi.data.AsnRoutingMapEntry
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

            val providerCatalog =
                asnRoutingCatalogProvider
                    .load()
                    .entries
                    .asSequence()
                    .mapNotNull { entry ->
                        entry.label
                            .trim()
                            .lowercase()
                            .takeIf(String::isNotEmpty)
                            ?.let { label -> label to entry }
                    }.toMap()
            val domesticProviders =
                providerCatalog
                    .values
                    .asSequence()
                    .filter { entry -> entry.country.equals("RU", ignoreCase = true) }
                    .map { entry -> entry.label.trim().lowercase() }
                    .filter(String::isNotEmpty)
                    .toSet()
            if (providerCatalog.isEmpty() || domesticProviders.isEmpty()) {
                return preferredEdges
            }

            return preferredEdges
                .mapValues { (_, candidates) ->
                    candidates.applyAntiCorrelationRouting(providerCatalog, domesticProviders)
                }.filterValues { it.isNotEmpty() }
        }

        private fun List<PreferredEdgeCandidate>.applyAntiCorrelationRouting(
            providerCatalog: Map<String, AsnRoutingMapEntry>,
            domesticProviders: Set<String>,
        ): List<PreferredEdgeCandidate> {
            val normalized = normalizePreferredEdgeCandidates(this)
            val nonDomesticCandidates =
                normalized.filterNot { candidate ->
                    candidate.isDomesticCdnCandidate(providerCatalog, domesticProviders)
                }
            val foreignCdnCandidates =
                nonDomesticCandidates.filter { candidate ->
                    candidate.isForeignCdnCandidate(providerCatalog, domesticProviders)
                }
            if (foreignCdnCandidates.isEmpty()) {
                return if (nonDomesticCandidates.size == normalized.size) {
                    normalized
                } else {
                    // Do not keep a domestic CDN edge pinned when anti-correlation is on.
                    normalizePreferredEdgeCandidates(nonDomesticCandidates)
                }
            }
            val retained =
                foreignCdnCandidates.map { candidate ->
                    candidate.copy(successCount = candidate.successCount + AntiCorrelationPromotionBonus)
                } +
                    nonDomesticCandidates.filterNot { candidate ->
                        candidate.isForeignCdnCandidate(providerCatalog, domesticProviders)
                    }
            return normalizePreferredEdgeCandidates(retained)
        }

        private fun PreferredEdgeCandidate.isForeignCdnCandidate(
            providerCatalog: Map<String, AsnRoutingMapEntry>,
            domesticProviders: Set<String>,
        ): Boolean {
            val provider = cdnProvider?.trim()?.lowercase().orEmpty()
            if (provider.isEmpty() || provider in domesticProviders) {
                return false
            }
            return providerCatalog[provider]?.cdn != false
        }

        private fun PreferredEdgeCandidate.isDomesticCdnCandidate(
            providerCatalog: Map<String, AsnRoutingMapEntry>,
            domesticProviders: Set<String>,
        ): Boolean {
            val provider = cdnProvider?.trim()?.lowercase().orEmpty()
            if (provider.isEmpty() || provider !in domesticProviders) {
                return false
            }
            return providerCatalog[provider]?.cdn != false
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
