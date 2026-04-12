@file:Suppress("ReturnCount")

package com.poyka.ripdpi.services

import android.content.Context
import android.content.pm.ApplicationInfo
import com.poyka.ripdpi.data.AppRoutingPolicyCatalog
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.appRoutingPolicyCatalogFromJson
import com.poyka.ripdpi.data.effectiveAppRoutingEnabledPresetIds
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

interface VpnAppExclusionPolicy {
    fun shouldExcludeOwnPackage(): Boolean

    fun russianAppsToExclude(): List<String>
}

interface AppRoutingCatalogProvider {
    fun load(): AppRoutingPolicyCatalog
}

interface InstalledPackagesProvider {
    fun installedPackages(): Set<String>
}

@Singleton
class AssetAppRoutingCatalogProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : AppRoutingCatalogProvider {
        private val cache = AtomicReference<AppRoutingPolicyCatalog?>()

        override fun load(): AppRoutingPolicyCatalog =
            cache.get()
                ?: context.assets
                    .open(AppRoutingPolicyAssetPath)
                    .bufferedReader()
                    .use { reader -> appRoutingPolicyCatalogFromJson(reader.readText()) }
                    .also(cache::set)

        private companion object {
            const val AppRoutingPolicyAssetPath = "integrations/app-routing-policy.json"
        }
    }

@Singleton
class PackageManagerInstalledPackagesProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : InstalledPackagesProvider {
        override fun installedPackages(): Set<String> =
            context.packageManager
                .getInstalledApplications(0)
                .asSequence()
                .map(ApplicationInfo::packageName)
                .filter(String::isNotBlank)
                .toSet()
    }

@Singleton
class DefaultVpnAppExclusionPolicy
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val appRoutingCatalogProvider: AppRoutingCatalogProvider,
        private val installedPackagesProvider: InstalledPackagesProvider,
    ) : VpnAppExclusionPolicy {
        override fun shouldExcludeOwnPackage(): Boolean = true

        override fun russianAppsToExclude(): List<String> {
            val settings = runBlocking { appSettingsRepository.snapshot() }
            if (settings.fullTunnelMode) return emptyList()
            val presetIds = settings.effectiveAppRoutingEnabledPresetIds().toSet()
            if (presetIds.isEmpty()) return emptyList()
            val installedPackages = installedPackagesProvider.installedPackages()
            val catalog = appRoutingCatalogProvider.load()
            return catalog.presets
                .asSequence()
                .filter { it.id in presetIds }
                .flatMap { it.resolvePackages(installedPackages).asSequence() }
                .distinct()
                .sorted()
                .toList()
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class VpnAppExclusionPolicyModule {
    @Binds
    @Singleton
    abstract fun bindVpnAppExclusionPolicy(policy: DefaultVpnAppExclusionPolicy): VpnAppExclusionPolicy

    @Binds
    @Singleton
    abstract fun bindAppRoutingCatalogProvider(provider: AssetAppRoutingCatalogProvider): AppRoutingCatalogProvider

    @Binds
    @Singleton
    abstract fun bindInstalledPackagesProvider(
        provider: PackageManagerInstalledPackagesProvider,
    ): InstalledPackagesProvider

    @Binds
    @Singleton
    abstract fun bindRoutingProtectionCatalogService(
        service: DefaultRoutingProtectionCatalogService,
    ): RoutingProtectionCatalogService
}
