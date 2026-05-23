package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppRoutingPackageEntry
import com.poyka.ripdpi.data.AppRoutingPolicyCatalog
import com.poyka.ripdpi.data.AppRoutingPolicyPreset
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class RoutingProtectionMatchedPreset(
    val id: String,
    val title: String,
    val detectionMethod: String,
    val fixCoverage: String,
    val limitations: String,
    val matchedPackages: List<String>,
)

data class RoutingProtectionDetectedApp(
    val packageName: String,
    val presetId: String,
    val presetTitle: String,
    val detectionMethod: String,
    val fixCoverage: String,
    val vpnDetection: Boolean = false,
    val packageDetectionMethods: List<String> = emptyList(),
    val severity: String = "none",
)

data class RoutingProtectionCatalogSnapshot(
    val presets: List<RoutingProtectionMatchedPreset> = emptyList(),
    val detectedApps: List<RoutingProtectionDetectedApp> = emptyList(),
)

interface RoutingProtectionCatalogService {
    fun snapshot(): RoutingProtectionCatalogSnapshot
}

@Singleton
class DefaultRoutingProtectionCatalogService
    @Inject
    constructor(
        private val appRoutingCatalogProvider: AppRoutingCatalogProvider,
        private val installedPackagesProvider: InstalledPackagesProvider,
    ) : RoutingProtectionCatalogService {
        private val indexedCatalogCache = AtomicReference<IndexedRoutingProtectionCatalog?>()

        override fun snapshot(): RoutingProtectionCatalogSnapshot {
            val indexedCatalog = indexedCatalogFor(appRoutingCatalogProvider.load())
            if (indexedCatalog.isEmpty()) {
                return RoutingProtectionCatalogSnapshot()
            }
            return indexedCatalog.snapshot(installedPackagesProvider.installedPackages())
        }

        private fun indexedCatalogFor(catalog: AppRoutingPolicyCatalog): IndexedRoutingProtectionCatalog {
            val cached = indexedCatalogCache.get()
            if (cached != null && (cached.source === catalog || cached.source == catalog)) {
                return cached
            }
            return IndexedRoutingProtectionCatalog.from(catalog).also(indexedCatalogCache::set)
        }
    }

internal class IndexedRoutingProtectionCatalog private constructor(
    val source: AppRoutingPolicyCatalog,
    private val presets: List<IndexedRoutingProtectionPreset>,
    private val packageMetadataByPresetId: Map<String, RoutingProtectionPackageMetadataIndex>,
) {
    fun isEmpty(): Boolean = presets.isEmpty()

    fun snapshot(installedPackages: Set<String>): RoutingProtectionCatalogSnapshot {
        val presetSnapshots =
            presets.map { preset ->
                preset.snapshot(
                    installedPackages = installedPackages,
                    packageMetadataIndex = packageMetadataByPresetId[preset.id],
                )
            }
        return RoutingProtectionCatalogSnapshot(
            presets = presetSnapshots.map(RoutingProtectionPresetSnapshot::preset),
            detectedApps =
                presetSnapshots
                    .flatMap(RoutingProtectionPresetSnapshot::detectedApps)
                    .sortedBy(RoutingProtectionDetectedApp::packageName),
        )
    }

    fun excludedPackagesFor(
        presetIds: Set<String>,
        installedPackages: Set<String>,
    ): List<String> =
        presets
            .asSequence()
            .filter { it.id in presetIds }
            .flatMap { it.resolvePackages(installedPackages).asSequence() }
            .distinct()
            .sorted()
            .toList()

    companion object {
        fun from(catalog: AppRoutingPolicyCatalog): IndexedRoutingProtectionCatalog {
            val metadataByPresetId = LinkedHashMap<String, RoutingProtectionPackageMetadataIndex>()
            catalog.presets.forEach { preset ->
                metadataByPresetId.putIfAbsent(preset.id, RoutingProtectionPackageMetadataIndex.from(preset.packages))
            }
            return IndexedRoutingProtectionCatalog(
                source = catalog,
                presets = catalog.presets.map(IndexedRoutingProtectionPreset::from),
                packageMetadataByPresetId = metadataByPresetId,
            )
        }
    }
}

private data class IndexedRoutingProtectionPreset(
    val id: String,
    private val title: String,
    private val detectionMethod: String,
    private val fixCoverage: String,
    private val limitations: String,
    private val exactPackageNames: List<String>,
    private val packageRegexes: List<Regex>,
) {
    fun snapshot(
        installedPackages: Set<String>,
        packageMetadataIndex: RoutingProtectionPackageMetadataIndex?,
    ): RoutingProtectionPresetSnapshot {
        val matchedPackages = resolvePackages(installedPackages)
        val matchedPreset =
            RoutingProtectionMatchedPreset(
                id = id,
                title = title,
                detectionMethod = detectionMethod,
                fixCoverage = fixCoverage,
                limitations = limitations,
                matchedPackages = matchedPackages,
            )
        return RoutingProtectionPresetSnapshot(
            preset = matchedPreset,
            detectedApps =
                matchedPackages.map { packageName ->
                    val entry = packageMetadataIndex?.entryFor(packageName)
                    RoutingProtectionDetectedApp(
                        packageName = packageName,
                        presetId = id,
                        presetTitle = title,
                        detectionMethod = entry?.detectionMethods?.firstOrNull() ?: detectionMethod,
                        fixCoverage = fixCoverage,
                        vpnDetection = entry?.vpnDetection ?: false,
                        packageDetectionMethods = entry?.detectionMethods ?: emptyList(),
                        severity = entry?.severity ?: "none",
                    )
                },
        )
    }

    fun resolvePackages(installedPackages: Set<String>): List<String> {
        val matches = LinkedHashSet<String>()
        matches.addAll(exactPackageNames)
        if (packageRegexes.isNotEmpty()) {
            installedPackages.forEach { packageName ->
                if (packageRegexes.any { regex -> regex.matches(packageName) }) {
                    matches += packageName
                }
            }
        }
        return matches.sorted()
    }

    companion object {
        fun from(preset: AppRoutingPolicyPreset): IndexedRoutingProtectionPreset =
            IndexedRoutingProtectionPreset(
                id = preset.id,
                title = preset.title,
                detectionMethod = preset.detectionMethod,
                fixCoverage = preset.fixCoverage,
                limitations = preset.limitations,
                exactPackageNames = preset.exactPackageNames(),
                packageRegexes = preset.compiledPackageRegexes(),
            )
    }
}

private data class RoutingProtectionPackageMetadataIndex(
    private val entriesByPackageName: Map<String, AppRoutingPackageEntry>,
) {
    fun entryFor(packageName: String): AppRoutingPackageEntry? = entriesByPackageName[packageName]

    companion object {
        fun from(entries: List<AppRoutingPackageEntry>): RoutingProtectionPackageMetadataIndex {
            val entriesByPackageName = LinkedHashMap<String, AppRoutingPackageEntry>()
            entries.forEach { entry ->
                entriesByPackageName.putIfAbsent(entry.packageName, entry)
            }
            return RoutingProtectionPackageMetadataIndex(entriesByPackageName)
        }
    }
}

private data class RoutingProtectionPresetSnapshot(
    val preset: RoutingProtectionMatchedPreset,
    val detectedApps: List<RoutingProtectionDetectedApp>,
)

private fun AppRoutingPolicyPreset.exactPackageNames(): List<String> {
    val exactNames =
        if (packages.isNotEmpty()) {
            packages.map { entry -> entry.packageName }
        } else {
            exactPackages
        }
    return exactNames.map(String::trim).filter(String::isNotEmpty)
}

private fun AppRoutingPolicyPreset.compiledPackageRegexes(): List<Regex> =
    packageRegexes
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(::Regex)
