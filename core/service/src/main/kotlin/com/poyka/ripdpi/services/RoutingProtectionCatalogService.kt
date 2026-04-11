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
            val presets =
                appRoutingCatalogProvider.load().presets.map { preset ->
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
                        preset.matchedPackages.map { packageName ->
                            RoutingProtectionDetectedApp(
                                packageName = packageName,
                                presetId = preset.id,
                                presetTitle = preset.title,
                                detectionMethod = preset.detectionMethod,
                                fixCoverage = preset.fixCoverage,
                            )
                        }
                    }.sortedBy(RoutingProtectionDetectedApp::packageName)
            return RoutingProtectionCatalogSnapshot(
                presets = presets,
                detectedApps = detectedApps,
            )
        }
    }
