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
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

interface VpnAppExclusionPolicy {
    fun shouldExcludeOwnPackage(): Boolean

    suspend fun russianAppsToExclude(): List<String>

    /**
     * Authoritative per-app routing plan for the `VpnService.Builder`. Android forbids mixing
     * `addAllowedApplication` and `addDisallowedApplication`, so the plan is expressed as exactly
     * one of [VpnAppRoutingPlan.AllowOnly] (allowlist) or [VpnAppRoutingPlan.Disallow] (blocklist).
     * Split-tunnel selection is read from the settings store (NOT the `routing_rules` Room table),
     * so it never reorders the user's routing rules.
     */
    suspend fun appRoutingPlan(ownPackage: String): VpnAppRoutingPlan
}

/** The two mutually-exclusive `VpnService.Builder` app-routing shapes Android permits. */
sealed interface VpnAppRoutingPlan {
    /** Blocklist/off mode: every listed package bypasses the tunnel; everything else is tunneled. */
    data class Disallow(
        val packages: Set<String>,
    ) : VpnAppRoutingPlan

    /** Allowlist mode: only the listed packages are tunneled; everything else bypasses. */
    data class AllowOnly(
        val packages: Set<String>,
    ) : VpnAppRoutingPlan
}

/**
 * Pure computation of the per-app routing plan, factored out so it can be unit-tested without the
 * protobuf settings/`PackageManager` plumbing. Precedence:
 *  - [fullTunnelMode] wins: tunnel everything except [ownPackage] (current behavior preserved).
 *  - else honor [splitTunnelMode] + [splitTunnelPackages] (intersected with [installedPackages] to
 *    drop stale entries):
 *      - "include" with a NON-EMPTY selection -> [VpnAppRoutingPlan.AllowOnly]. [ownPackage] is
 *        intentionally absent so its own traffic bypasses the tunnel (correct: avoids self-loop).
 *      - "exclude" -> [VpnAppRoutingPlan.Disallow] of own package + preset exclusions + selection.
 *      - "off" (or "include" with an EMPTY selection, to avoid tunneling nothing) ->
 *        [VpnAppRoutingPlan.Disallow] of own package + preset exclusions (current behavior).
 * The disallow set is a [Set] so a user-selected package that is also a preset exclusion collapses
 * to one entry (no double-matching).
 */
internal fun computeAppRoutingPlan(
    fullTunnelMode: Boolean,
    splitTunnelMode: String,
    splitTunnelPackages: Collection<String>,
    presetExclusions: Collection<String>,
    installedPackages: Set<String>,
    ownPackage: String,
): VpnAppRoutingPlan {
    if (fullTunnelMode) return VpnAppRoutingPlan.Disallow(setOf(ownPackage))
    val selection = splitTunnelPackages.filter { it in installedPackages }.toSet()
    return when (splitTunnelMode) {
        SplitTunnelMode.Include -> {
            if (selection.isEmpty()) {
                VpnAppRoutingPlan.Disallow(setOf(ownPackage) + presetExclusions)
            } else {
                VpnAppRoutingPlan.AllowOnly(selection)
            }
        }

        SplitTunnelMode.Exclude -> {
            VpnAppRoutingPlan.Disallow(setOf(ownPackage) + presetExclusions + selection)
        }

        else -> {
            VpnAppRoutingPlan.Disallow(setOf(ownPackage) + presetExclusions)
        }
    }
}

/** Canonical `split_tunnel_mode` string values, shared by the policy and the split-tunnel UI. */
object SplitTunnelMode {
    const val Off = "off"
    const val Include = "include"
    const val Exclude = "exclude"
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
        private val indexedCatalogCache = AtomicReference<IndexedRoutingProtectionCatalog?>()

        override fun shouldExcludeOwnPackage(): Boolean = true

        override suspend fun russianAppsToExclude(): List<String> {
            val settings = appSettingsRepository.snapshot()
            if (settings.fullTunnelMode) return emptyList()
            return presetExclusionsFor(settings, installedPackagesProvider.installedPackages())
        }

        override suspend fun appRoutingPlan(ownPackage: String): VpnAppRoutingPlan {
            val settings = appSettingsRepository.snapshot()
            val installedPackages = installedPackagesProvider.installedPackages()
            val presetExclusions =
                if (settings.fullTunnelMode) {
                    emptyList()
                } else {
                    presetExclusionsFor(settings, installedPackages)
                }
            return computeAppRoutingPlan(
                fullTunnelMode = settings.fullTunnelMode,
                splitTunnelMode = settings.splitTunnelMode,
                splitTunnelPackages = settings.splitTunnelPackagesList,
                presetExclusions = presetExclusions,
                installedPackages = installedPackages,
                ownPackage = ownPackage,
            )
        }

        private fun presetExclusionsFor(
            settings: com.poyka.ripdpi.proto.AppSettings,
            installedPackages: Set<String>,
        ): List<String> {
            val presetIds = settings.effectiveAppRoutingEnabledPresetIds().toSet()
            if (presetIds.isEmpty()) return emptyList()
            return indexedCatalogFor(appRoutingCatalogProvider.load())
                .excludedPackagesFor(
                    presetIds = presetIds,
                    installedPackages = installedPackages,
                )
        }

        private fun indexedCatalogFor(catalog: AppRoutingPolicyCatalog): IndexedRoutingProtectionCatalog {
            val cached = indexedCatalogCache.get()
            if (cached != null && (cached.source === catalog || cached.source == catalog)) {
                return cached
            }
            return IndexedRoutingProtectionCatalog.from(catalog).also(indexedCatalogCache::set)
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
