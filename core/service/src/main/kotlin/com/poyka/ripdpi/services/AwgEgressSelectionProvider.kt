package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolver-facing source of VPN-mode AmneziaWG egress selected outside
 * persisted AppSettings.
 *
 * Production DI binds this once via [DefaultAwgEgressSelectionProvider]. Feature
 * owners contribute [AwgEgressSelectionSource] entries instead, avoiding multiple
 * competing bindings for the same resolver dependency.
 */
interface AwgEgressSelectionProvider {
    suspend fun selectedAwgEgress(): AwgActivationRequest?
}

/** Feature-owned AWG egress source consumed by [DefaultAwgEgressSelectionProvider]. */
interface AwgEgressSelectionSource {
    val selectionPriority: Int get() = 100

    suspend fun selectedAwgEgress(): AwgActivationRequest?
}

@Singleton
internal class DefaultAwgEgressSelectionProvider
    @Inject
    constructor(
        private val sources: Set<@JvmSuppressWildcards AwgEgressSelectionSource>,
    ) : AwgEgressSelectionProvider {
        override suspend fun selectedAwgEgress(): AwgActivationRequest? {
            sources
                .sortedBy(AwgEgressSelectionSource::selectionPriority)
                .forEach { source ->
                    source.selectedAwgEgress()?.let { return it }
                }
            return null
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AwgEgressSelectionProviderModule {
    @Binds
    @Singleton
    abstract fun bindAwgEgressSelectionProvider(
        provider: DefaultAwgEgressSelectionProvider,
    ): AwgEgressSelectionProvider
}
