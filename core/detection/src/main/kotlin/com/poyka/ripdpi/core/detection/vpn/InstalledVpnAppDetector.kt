package com.poyka.ripdpi.core.detection.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.MatchedVpnApp
import com.poyka.ripdpi.core.detection.VpnAppKind
import java.util.Locale

data class InstalledVpnDetectionResult(
    val findings: List<Finding>,
    val evidence: List<EvidenceItem>,
    val matchedApps: List<MatchedVpnApp>,
    val needsReview: Boolean,
)

object InstalledVpnAppDetector {
    fun detect(
        context: Context,
        excludePackage: String? = null,
    ): InstalledVpnDetectionResult {
        val pm = context.packageManager
        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()
        val matchedApps = linkedMapOf<String, MatchedVpnApp>()

        detectKnownInstalledPackages(context, pm, excludePackage, findings, evidence, matchedApps)
        detectDeclaredVpnServices(context, pm, excludePackage, findings, evidence, matchedApps)
        detectPackagesWithVpnInName(context, pm, excludePackage, findings, evidence, matchedApps)

        if (matchedApps.isEmpty()) {
            findings.add(Finding("Known VPN/proxy apps and VpnService providers: not detected"))
        }

        return InstalledVpnDetectionResult(
            findings = findings,
            evidence = evidence,
            matchedApps = matchedApps.values.toList(),
            needsReview = matchedApps.isNotEmpty(),
        )
    }

    private fun detectKnownInstalledPackages(
        context: Context,
        pm: PackageManager,
        excludePackage: String?,
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
        matchedApps: MutableMap<String, MatchedVpnApp>,
    ) {
        for (signature in VpnAppCatalog.signatures) {
            if (signature.packageName == excludePackage) continue
            if (!isPackageInstalled(pm, signature.packageName)) continue

            val confidence =
                when (signature.kind) {
                    VpnAppKind.TARGETED_BYPASS -> EvidenceConfidence.MEDIUM
                    VpnAppKind.GENERIC_VPN -> EvidenceConfidence.LOW
                }
            val metadata = VpnAppMetadataScanner.scan(context, signature.packageName)
            val description =
                "Installed app: ${signature.appName} (${signature.packageName})" +
                    VpnAppMetadataScanner.formatMetadataSuffix(metadata)

            findings.add(
                Finding(
                    description = description,
                    needsReview = true,
                    source = EvidenceSource.INSTALLED_APP,
                    confidence = confidence,
                    family = signature.family,
                    packageName = signature.packageName,
                ),
            )
            evidence.add(
                EvidenceItem(
                    source = EvidenceSource.INSTALLED_APP,
                    detected = true,
                    confidence = confidence,
                    description = description,
                    family = signature.family,
                    packageName = signature.packageName,
                    kind = signature.kind,
                ),
            )
            matchedApps.putIfAbsent(
                signature.packageName,
                MatchedVpnApp(
                    packageName = signature.packageName,
                    appName = signature.appName,
                    family = signature.family,
                    kind = signature.kind,
                    source = EvidenceSource.INSTALLED_APP,
                    active = false,
                    confidence = confidence,
                    technicalMetadata = metadata,
                ),
            )
        }
    }

    private fun detectDeclaredVpnServices(
        context: Context,
        pm: PackageManager,
        excludePackage: String?,
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
        matchedApps: MutableMap<String, MatchedVpnApp>,
    ) {
        for ((packageName, serviceNames) in queryVpnServiceProviders(pm)) {
            if (packageName == excludePackage) continue
            val signature = VpnAppCatalog.findByPackageName(packageName)
            val appName = signature?.appName ?: resolveAppName(pm, packageName)
            val family = signature?.family
            val kind = signature?.kind ?: VpnAppKind.GENERIC_VPN
            val confidence = EvidenceConfidence.MEDIUM
            val metadata =
                VpnAppMetadataScanner.scan(
                    context = context,
                    packageName = packageName,
                    serviceNames = serviceNames,
                )
            val serviceSuffix = serviceNames.takeIf { it.isNotEmpty() }?.joinToString()
            val description =
                buildString {
                    append("App declares VpnService: ")
                    append(appName)
                    append(" (")
                    append(packageName)
                    append(")")
                    if (!serviceSuffix.isNullOrBlank()) {
                        append(" -> ")
                        append(serviceSuffix)
                    }
                    append(VpnAppMetadataScanner.formatMetadataSuffix(metadata))
                }

            findings.add(
                Finding(
                    description = description,
                    needsReview = true,
                    source = EvidenceSource.VPN_SERVICE_DECLARATION,
                    confidence = confidence,
                    family = family,
                    packageName = packageName,
                ),
            )
            evidence.add(
                EvidenceItem(
                    source = EvidenceSource.VPN_SERVICE_DECLARATION,
                    detected = true,
                    confidence = confidence,
                    description = description,
                    family = family,
                    packageName = packageName,
                    kind = kind,
                ),
            )

            matchedApps[packageName] =
                MatchedVpnApp(
                    packageName = packageName,
                    appName = appName,
                    family = family,
                    kind = kind,
                    source = EvidenceSource.VPN_SERVICE_DECLARATION,
                    active = false,
                    confidence = confidence,
                    technicalMetadata = metadata,
                )
        }
    }

    private fun detectPackagesWithVpnInName(
        context: Context,
        pm: PackageManager,
        excludePackage: String?,
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
        matchedApps: MutableMap<String, MatchedVpnApp>,
    ) {
        for (packageInfo in installedPackages(pm)) {
            val packageName = packageInfo.packageName
            if (packageName == excludePackage || matchedApps.containsKey(packageName)) continue
            val appInfo = packageInfo.applicationInfo ?: continue
            if (appInfo.isSystemApp()) continue

            val appName = resolveDisplayAppName(pm, packageName, appInfo)
            if (!appName.uppercase(Locale.ROOT).contains("VPN")) continue

            val metadata =
                VpnAppMetadataScanner.scan(
                    context = context,
                    packageName = packageName,
                    matchedByNameHeuristic = true,
                )
            val description =
                "Installed app name suggests VPN: $appName ($packageName)" +
                    VpnAppMetadataScanner.formatMetadataSuffix(metadata)

            findings.add(
                Finding(
                    description = description,
                    needsReview = true,
                    source = EvidenceSource.INSTALLED_APP,
                    confidence = EvidenceConfidence.LOW,
                    packageName = packageName,
                ),
            )
            evidence.add(
                EvidenceItem(
                    source = EvidenceSource.INSTALLED_APP,
                    detected = false,
                    confidence = EvidenceConfidence.LOW,
                    description = description,
                    packageName = packageName,
                    kind = VpnAppKind.GENERIC_VPN,
                ),
            )
            matchedApps.putIfAbsent(
                packageName,
                MatchedVpnApp(
                    packageName = packageName,
                    appName = appName,
                    family = null,
                    kind = VpnAppKind.GENERIC_VPN,
                    source = EvidenceSource.INSTALLED_APP,
                    active = false,
                    confidence = EvidenceConfidence.LOW,
                    technicalMetadata = metadata,
                ),
            )
        }
    }

    private fun queryVpnServiceProviders(pm: PackageManager): Map<String, List<String>> {
        val intent = Intent(VpnService.SERVICE_INTERFACE)
        val resolveInfos =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }

        return resolveInfos
            .mapNotNull { resolveInfo ->
                val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
                if (serviceInfo.permission != android.Manifest.permission.BIND_VPN_SERVICE) return@mapNotNull null
                serviceInfo.packageName to serviceInfo.name
            }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
    }

    private fun installedPackages(pm: PackageManager): List<android.content.pm.PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

    private fun resolveDisplayAppName(
        pm: PackageManager,
        packageName: String,
        appInfo: android.content.pm.ApplicationInfo,
    ): String {
        val launcherLabel = resolveLauncherLabel(pm, packageName)
        if (!launcherLabel.isNullOrBlank()) return launcherLabel

        val applicationLabel = appInfo.loadLabel(pm).toString().trim()
        return applicationLabel.ifBlank { packageName }
    }

    private fun resolveLauncherLabel(
        pm: PackageManager,
        packageName: String,
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
            .asSequence()
            .mapNotNull { resolveInfo -> resolveInfo.loadLabel(pm).toString().trim() }
            .firstOrNull { label -> label.isNotBlank() }
    }

    private fun android.content.pm.ApplicationInfo.isSystemApp(): Boolean {
        val system = flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        val updatedSystem = flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return system || updatedSystem
    }

    private fun isPackageInstalled(
        pm: PackageManager,
        packageName: String,
    ): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun resolveAppName(
        pm: PackageManager,
        packageName: String,
    ): String =
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
}
