package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgCohortCatalog
import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Narrow seam over [AwgCohortCatalog] so the AmneziaWG editor ViewModel can be unit-tested
 * with an in-memory catalog instead of the Android-asset-backed loader.
 */
interface AwgCohortCatalogProvider {
    /** The shipped cohort catalog; an empty catalog is the documented degraded state. */
    fun catalog(): AwgCohortCatalogData
}

/** [AwgCohortCatalogProvider] backed by the real asset-loading [AwgCohortCatalog]. */
@Singleton
class AssetAwgCohortCatalogProvider
    @Inject
    constructor(
        private val catalog: AwgCohortCatalog,
    ) : AwgCohortCatalogProvider {
        override fun catalog(): AwgCohortCatalogData = AwgCohortCatalogData(presets = catalog.all())
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AwgCohortCatalogProviderModule {
    @Binds
    @Singleton
    abstract fun bindCatalogProvider(provider: AssetAwgCohortCatalogProvider): AwgCohortCatalogProvider
}
