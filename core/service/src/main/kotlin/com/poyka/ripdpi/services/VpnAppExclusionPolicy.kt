@file:Suppress("ReturnCount")

package com.poyka.ripdpi.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.AppRoutingPolicyCatalog
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.appRoutingPolicyCatalogFromJson
import com.poyka.ripdpi.data.effectiveAppRoutingEnabledPresetIds
import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
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
     * Split-tunnel selection comes from the caller's immutable settings snapshot (NOT the
     * `routing_rules` Room table), so the Android builder and native UID guard use one generation.
     */
    suspend fun appRoutingPlan(
        ownPackage: String,
        settings: AppSettings,
        packageRoutingRules: Collection<PackageRoutingRule>,
        installedPackages: Set<String>,
    ): VpnAppRoutingPlan
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
 * to one entry (no double-matching). Imported/user [packageRoutingRules] refine that base plan:
 * [PackageRoutingAction.BYPASS] routes direct, while [PackageRoutingAction.VIA_TUN] and legacy
 * [PackageRoutingAction.VIA_OUTBOUND] force the package into the tunnel. Forced-tunnel wins a
 * conflict regardless of group/rule order. Only installed packages participate, and imported
 * rules can never add [ownPackage] to the tunnel.
 */
internal fun computeAppRoutingPlan(
    fullTunnelMode: Boolean,
    splitTunnelMode: String,
    splitTunnelPackages: Collection<String>,
    presetExclusions: Collection<String>,
    installedPackages: Set<String>,
    ownPackage: String,
    packageRoutingRules: Collection<PackageRoutingRule> = emptyList(),
): VpnAppRoutingPlan {
    if (fullTunnelMode) return VpnAppRoutingPlan.Disallow(setOf(ownPackage))
    val selection = splitTunnelPackages.filter { it in installedPackages }.toSet()
    val basePlan =
        when (splitTunnelMode) {
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
    val applicableRules =
        packageRoutingRules.filter { rule ->
            rule.packageName != ownPackage && rule.packageName in installedPackages
        }
    val forcedTunnel =
        applicableRules
            .filter { it.action != PackageRoutingAction.BYPASS }
            .mapTo(mutableSetOf()) { it.packageName }
    val bypass =
        applicableRules
            .filter { it.action == PackageRoutingAction.BYPASS }
            .mapTo(mutableSetOf()) { it.packageName }
            .minus(forcedTunnel)
    return when (basePlan) {
        is VpnAppRoutingPlan.Disallow -> {
            VpnAppRoutingPlan.Disallow(
                packages = ((basePlan.packages + bypass) - forcedTunnel) + ownPackage,
            )
        }

        is VpnAppRoutingPlan.AllowOnly -> {
            val allowedPackages = ((basePlan.packages + forcedTunnel) - bypass) - ownPackage
            if (allowedPackages.isEmpty()) {
                // Android treats a Builder with no allow/disallow calls as "tunnel every app".
                // Preserve the empty allowlist meaning by explicitly disallowing every installed
                // package; the native UID policy then receives the matching non-empty denylist.
                VpnAppRoutingPlan.Disallow(installedPackages + ownPackage)
            } else {
                VpnAppRoutingPlan.AllowOnly(allowedPackages)
            }
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

    fun observeInstalledPackages(): Flow<Set<String>> = flowOf(installedPackages())
}

internal fun applyInstalledPackageEvent(
    current: Set<String>,
    action: String?,
    packageName: String,
    replacing: Boolean,
    installed: Boolean,
): Set<String> =
    when (action) {
        Intent.ACTION_PACKAGE_ADDED,
        Intent.ACTION_PACKAGE_CHANGED,
        Intent.ACTION_PACKAGE_REPLACED,
        -> if (installed) current + packageName else current - packageName

        Intent.ACTION_PACKAGE_REMOVED,
        Intent.ACTION_PACKAGE_FULLY_REMOVED,
        -> if (replacing) current else current - packageName

        else -> current
    }

internal fun initializeInstalledPackageSnapshot(
    packages: MutableStateFlow<Set<String>>,
    loadInstalledPackages: () -> Set<String>,
    registerReceiver: () -> Unit,
) {
    packages.value = loadInstalledPackages()
    registerReceiver()
    // Package broadcasts are non-sticky. Close the snapshot-to-registration gap so an add or
    // removal in that window cannot leave the cache stale until another package event arrives.
    packages.value = loadInstalledPackages()
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
        private val packages = MutableStateFlow(emptySet<String>())
        private val packageChangeReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    val packageName = intent?.data?.schemeSpecificPart?.takeIf(String::isNotBlank) ?: return
                    val action = intent.action
                    val requiresPresenceCheck =
                        action == Intent.ACTION_PACKAGE_ADDED ||
                            action == Intent.ACTION_PACKAGE_CHANGED ||
                            action == Intent.ACTION_PACKAGE_REPLACED
                    packages.update { current ->
                        applyInstalledPackageEvent(
                            current = current,
                            action = action,
                            packageName = packageName,
                            replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false),
                            installed = !requiresPresenceCheck || isInstalled(packageName),
                        )
                    }
                }
            }

        init {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                    addDataScheme("package")
                }
            initializeInstalledPackageSnapshot(
                packages = packages,
                loadInstalledPackages = ::loadInstalledPackages,
                registerReceiver = {
                    ContextCompat.registerReceiver(
                        context,
                        packageChangeReceiver,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                },
            )
        }

        override fun installedPackages(): Set<String> = packages.value

        override fun observeInstalledPackages(): Flow<Set<String>> = packages.asStateFlow()

        private fun loadInstalledPackages(): Set<String> =
            context.packageManager
                .getInstalledApplications(0)
                .asSequence()
                .map(ApplicationInfo::packageName)
                .filter(String::isNotBlank)
                .toSet()

        private fun isInstalled(packageName: String): Boolean =
            try {
                context.packageManager.getApplicationInfo(packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
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

        override suspend fun appRoutingPlan(
            ownPackage: String,
            settings: AppSettings,
            packageRoutingRules: Collection<PackageRoutingRule>,
            installedPackages: Set<String>,
        ): VpnAppRoutingPlan {
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
                packageRoutingRules = packageRoutingRules,
            )
        }

        private fun presetExclusionsFor(
            settings: AppSettings,
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
