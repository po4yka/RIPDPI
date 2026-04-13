package com.poyka.ripdpi.services

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
        override fun snapshot(): RoutingProtectionCatalogSnapshot {
            val installedPackages = installedPackagesProvider.installedPackages()
            val catalog = appRoutingCatalogProvider.load()
            val presets =
                catalog.presets.map { preset ->
                    val matchedPackages = preset.resolvePackages(installedPackages).toList().sorted()
                    RoutingProtectionMatchedPreset(
                        id = preset.id,
                        title = preset.title,
                        detectionMethod = preset.detectionMethod,
                        fixCoverage = preset.fixCoverage,
                        limitations = preset.limitations,
                        matchedPackages = matchedPackages,
                    )
                }
            val detectedApps =
                presets
                    .flatMap { preset ->
                        val catalogPreset = catalog.presets.find { it.id == preset.id }
                        preset.matchedPackages.map { packageName ->
                            val entry = catalogPreset?.findPackageEntry(packageName)
                            RoutingProtectionDetectedApp(
                                packageName = packageName,
                                presetId = preset.id,
                                presetTitle = preset.title,
                                detectionMethod =
                                    entry?.detectionMethods?.firstOrNull()
                                        ?: preset.detectionMethod,
                                fixCoverage = preset.fixCoverage,
                                vpnDetection = entry?.vpnDetection ?: false,
                                packageDetectionMethods = entry?.detectionMethods ?: emptyList(),
                                severity = entry?.severity ?: "none",
                            )
                        }
                    }.sortedBy(RoutingProtectionDetectedApp::packageName)
            return RoutingProtectionCatalogSnapshot(
                presets = presets,
                detectedApps = detectedApps,
            )
        }
    }
