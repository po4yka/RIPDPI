package com.poyka.ripdpi.failover

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.seed.SIMPLE_SEED_GROUP_ID
import com.poyka.ripdpi.seed.seedRelayProfileId
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies only relay profiles owned by the embedded Simple configuration. */
internal fun interface SimpleFailoverRelayCatalog {
    /**
     * Loads the exact persisted rows described by the current embedded bundle.
     *
     * // NOT cancel-safe: cancellation can stop the bounded sequence of independent store reads.
     * The caller rebuilds the catalog on the next observation attempt.
     */
    suspend fun loadManagedProfiles(): List<RelayProfileRecord>
}

@Singleton
internal class EmbeddedSimpleFailoverRelayCatalog
    @Inject
    constructor(
        private val bundleSource: SimpleRelayBundleSource,
        private val relayProfileStore: RelayProfileStore,
    ) : SimpleFailoverRelayCatalog {
        override suspend fun loadManagedProfiles(): List<RelayProfileRecord> {
            val parsed =
                bundleSource
                    .read()
                    ?.let { payload -> SingBoxSubscriptionParser.parse(payload, SIMPLE_SEED_GROUP_ID) }
                    as? SingBoxParseResult.Success
                    ?: return emptyList()
            val occurrencesByKind = mutableMapOf<String, Int>()
            return parsed.profiles.mapNotNull { embedded ->
                val expectedKind = embedded.automaticFailoverRelayKindOrNull() ?: return@mapNotNull null
                val occurrence = occurrencesByKind.getOrDefault(expectedKind, 0)
                occurrencesByKind[expectedKind] = occurrence + 1
                relayProfileStore
                    .load(seedRelayProfileId(embedded, occurrence))
                    ?.takeIf { stored -> stored.kind == expectedKind }
            }
        }
    }

private fun ProxyProfile.automaticFailoverRelayKindOrNull(): String? =
    when (this) {
        is ProxyProfile.VlessReality -> RelayKindVlessReality
        is ProxyProfile.Vless -> RelayKindVless
        is ProxyProfile.Hysteria2 -> RelayKindHysteria2
        else -> null
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SimpleFailoverRelayCatalogModule {
    @Binds
    abstract fun bindSimpleFailoverRelayCatalog(
        catalog: EmbeddedSimpleFailoverRelayCatalog,
    ): SimpleFailoverRelayCatalog
}
