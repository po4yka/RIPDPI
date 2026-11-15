package com.poyka.ripdpi.core.detection.vpn

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.MatchedVpnApp
import com.poyka.ripdpi.core.detection.VpnAppKind
import kotlinx.coroutines.currentCoroutineContext

data class InstalledVpnDetectionResult(
    val findings: List<Finding>,
    val evidence: List<EvidenceItem>,
    val matchedApps: List<MatchedVpnApp>,
    val needsReview: Boolean,
    val executionStatus: CheckExecutionStatus,
)

object InstalledVpnAppDetector {
    suspend fun detect(
        context: Context,
        excludePackage: String? = null,
    ): InstalledVpnDetectionResult {
        val budget = VpnInventoryBudget(currentCoroutineContext())
        val pm =
            budget.readPackageManager { context.packageManager }
                ?: return result(emptyList(), CheckExecutionStatus.ERROR)
        val packages =
            budget.readPackageManager {
                installedPackages(
                    pm,
                ).bounded(budget).associateBy { it.packageName }
            }
        val services = budget.readPackageManager { queryVpnServiceProviders(pm, budget) }
        val visiblePackages = packages.orEmpty()
        val observedServices = services.orEmpty()
        val matchedApps = mutableListOf<MatchedVpnApp>()

        val declaredOrCatalogued =
            VpnAppCatalog.signatures.mapNotNull { it.packageName.takeIf(visiblePackages::containsKey) }.toSet() +
                observedServices.keys
        val excluded = listOfNotNull(excludePackage)
        for (packageName in declaredOrCatalogued - excluded) {
            if (!budget.canContinue()) break
            val packageInfo = visiblePackages[packageName]
            val signature = VpnAppCatalog.findByPackageName(packageName)
            val serviceNames = observedServices[packageName].orEmpty()
            val appName = signature?.appName ?: resolveDisplayLabel(pm, packageInfo, budget) ?: packageName
            val confidence =
                when {
                    serviceNames.isNotEmpty() -> EvidenceConfidence.MEDIUM
                    signature?.kind == VpnAppKind.TARGETED_BYPASS -> EvidenceConfidence.MEDIUM
                    else -> EvidenceConfidence.LOW
                }
            matchedApps +=
                MatchedVpnApp(
                    packageName = packageName,
                    appName = appName,
                    family = signature?.family,
                    kind = signature?.kind ?: VpnAppKind.GENERIC_VPN,
                    source =
                        if (serviceNames.isEmpty()) {
                            EvidenceSource.INSTALLED_APP
                        } else {
                            EvidenceSource.VPN_SERVICE_DECLARATION
                        },
                    active = false,
                    confidence = confidence,
                    technicalMetadata = VpnAppMetadataScanner.scan(packageInfo, budget, serviceNames),
                )
        }

        matchedApps += findLabelMatches(pm, visiblePackages, declaredOrCatalogued + excluded, budget)
        budget.canContinue()
        val status =
            when {
                packages == null && services == null -> CheckExecutionStatus.ERROR
                budget.incomplete -> CheckExecutionStatus.INCONCLUSIVE
                else -> CheckExecutionStatus.COMPLETED
            }
        return result(matchedApps, status)
    }

    private fun findLabelMatches(
        pm: PackageManager,
        visiblePackages: Map<String, PackageInfo>,
        excludedPackages: Set<String>,
        budget: VpnInventoryBudget,
    ): List<MatchedVpnApp> =
        buildList {
            val candidates =
                visiblePackages.asSequence().filter { (packageName, packageInfo) ->
                    packageName !in excludedPackages && packageInfo.applicationInfo?.isSystemApp() == false
                }
            for ((packageName, packageInfo) in candidates) {
                if (!budget.canContinue()) break
                val appName = resolveDisplayLabel(pm, packageInfo, budget)
                if (appName != null && appName != packageName && appName.contains("VPN", ignoreCase = true)) {
                    add(
                        MatchedVpnApp(
                            packageName = packageName,
                            appName = appName,
                            family = null,
                            kind = VpnAppKind.GENERIC_VPN,
                            source = EvidenceSource.INSTALLED_APP,
                            active = false,
                            confidence = EvidenceConfidence.LOW,
                            technicalMetadata =
                                VpnAppMetadataScanner.scan(
                                    packageInfo,
                                    budget,
                                    matchedByNameHeuristic = true,
                                ),
                        ),
                    )
                }
            }
        }

    private fun result(
        matchedApps: List<MatchedVpnApp>,
        status: CheckExecutionStatus,
    ): InstalledVpnDetectionResult {
        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()
        for (app in matchedApps) {
            val labelOnly = app.technicalMetadata?.matchedByNameHeuristic == true
            val findingLabel =
                when {
                    labelOnly -> "Installed app name suggests VPN"
                    app.source == EvidenceSource.VPN_SERVICE_DECLARATION -> "App declares VpnService"
                    else -> "Installed app"
                }
            val description =
                "$findingLabel: ${app.appName} (${app.packageName})" +
                    VpnAppMetadataScanner.formatMetadataSuffix(app.technicalMetadata)
            findings +=
                Finding(
                    description = description,
                    needsReview = true,
                    source = app.source,
                    confidence = app.confidence,
                    family = app.family,
                    packageName = app.packageName,
                )
            evidence +=
                EvidenceItem(
                    source = app.source,
                    detected = !labelOnly,
                    confidence = app.confidence,
                    description = description,
                    family = app.family,
                    packageName = app.packageName,
                    kind = app.kind,
                )
        }
        val summary =
            when (status) {
                CheckExecutionStatus.COMPLETED -> {
                    if (matchedApps.isEmpty()) "No VPN app matches among packages visible to this app" else null
                }

                CheckExecutionStatus.INCONCLUSIVE -> {
                    "VPN app inventory is incomplete for packages visible to this app"
                }

                else -> {
                    "VPN app inventory is unavailable"
                }
            }
        summary?.let { findings += Finding(it) }
        return InstalledVpnDetectionResult(findings, evidence, matchedApps, matchedApps.isNotEmpty(), status)
    }

    private fun queryVpnServiceProviders(
        pm: PackageManager,
        budget: VpnInventoryBudget,
    ): Map<String, List<String>> {
        val intent = Intent(VpnService.SERVICE_INTERFACE)
        val resolveInfos =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }

        return resolveInfos
            .bounded(budget)
            .mapNotNull { resolveInfo ->
                val service = resolveInfo.serviceInfo ?: return@mapNotNull null
                if (service.permission != Manifest.permission.BIND_VPN_SERVICE) return@mapNotNull null
                service.packageName to service.name
            }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
    }

    private fun installedPackages(pm: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

    private fun resolveDisplayLabel(
        pm: PackageManager,
        packageInfo: PackageInfo?,
        budget: VpnInventoryBudget,
    ): String? {
        val appInfo = packageInfo?.applicationInfo ?: return null
        val launcherLabel = budget.readPackageManager { resolveLauncherLabel(pm, packageInfo.packageName, budget) }
        return launcherLabel ?: budget.readPackageManager { appInfo.loadLabel(pm).toString().boundedInventoryText() }
    }

    private fun resolveLauncherLabel(
        pm: PackageManager,
        packageName: String,
        budget: VpnInventoryBudget,
    ): String? {
        val intent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                `package` = packageName
            }
        val resolveInfos =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        return resolveInfos
            .bounded(budget)
            .firstNotNullOfOrNull { info ->
                if (budget.canContinue()) info.loadLabel(pm).toString().boundedInventoryText() else null
            }
    }

    private fun <T> List<T>.bounded(budget: VpnInventoryBudget): List<T> {
        if (size > MaxVisibleRecords) budget.markIncomplete()
        return take(MaxVisibleRecords)
    }

    private const val MaxVisibleRecords = 1024
}
