@file:Suppress("ReturnCount", "ComplexCondition")

package com.poyka.ripdpi.core.detection.vpn

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.poyka.ripdpi.core.detection.VpnAppTechnicalMetadata
import java.util.zip.ZipFile

object VpnAppMetadataScanner {
    fun scan(
        context: Context,
        packageName: String?,
        serviceNames: List<String> = emptyList(),
        matchedByNameHeuristic: Boolean = false,
    ): VpnAppTechnicalMetadata? {
        if (packageName.isNullOrBlank()) {
            return serviceNames
                .normalizeServiceNames()
                .takeIf { it.isNotEmpty() }
                ?.let { VpnAppTechnicalMetadata(serviceNames = it, matchedByNameHeuristic = matchedByNameHeuristic) }
        }

        val pm = context.packageManager
        val packageInfo = getPackageInfo(pm, packageName)
        val appInfo = packageInfo?.applicationInfo
        val apkInspection = appInfo?.apkPaths()?.inspectApkPaths()

        if (packageInfo == null && apkInspection == null && serviceNames.isEmpty() && !matchedByNameHeuristic) {
            return null
        }

        return VpnAppTechnicalMetadata(
            versionName = packageInfo?.versionName?.takeIf { it.isNotBlank() },
            serviceNames = (serviceNames + packageInfo.vpnServiceNames()).normalizeServiceNames(),
            appType = apkInspection?.appType,
            coreType = apkInspection?.coreType,
            corePath = apkInspection?.corePath,
            goVersion = apkInspection?.goVersion,
            systemApp = appInfo?.isSystemApp() == true,
            matchedByNameHeuristic = matchedByNameHeuristic,
        )
    }

    fun formatMetadataSuffix(metadata: VpnAppTechnicalMetadata?): String {
        val details = metadataDetails(metadata)
        return if (details.isEmpty()) "" else " [${details.joinToString(", ")}]"
    }

    fun metadataDetails(metadata: VpnAppTechnicalMetadata?): List<String> {
        if (metadata == null) return emptyList()
        return buildList {
            metadata.versionName?.let { add("version=$it") }
            metadata.appType?.let { add("app=$it") }
            metadata.coreType?.let { add("core=$it") }
            metadata.corePath?.let { add("path=$it") }
            metadata.goVersion?.let { add("go=$it") }
            if (metadata.serviceNames.isNotEmpty()) {
                add("services=${metadata.serviceNames.take(MaxServicesInSuffix).joinToString()}")
            }
            if (metadata.matchedByNameHeuristic) add("nameHeuristic=true")
            if (metadata.systemApp) add("systemApp=true")
        }
    }

    private fun getPackageInfo(
        pm: PackageManager,
        packageName: String,
    ): PackageInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SERVICES)
            }
        }.getOrNull()

    private fun PackageInfo?.vpnServiceNames(): List<String> =
        this
            ?.services
            ?.filter { service -> service.permission == Manifest.permission.BIND_VPN_SERVICE }
            ?.mapNotNull { service -> service.name?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()

    private fun ApplicationInfo.apkPaths(): List<String> =
        buildList {
            publicSourceDir?.takeIf { it.isNotBlank() }?.let(::add)
            splitPublicSourceDirs?.filter { it.isNotBlank() }?.let(::addAll)
        }

    private fun ApplicationInfo.isSystemApp(): Boolean {
        val system = flags and ApplicationInfo.FLAG_SYSTEM != 0
        val updatedSystem = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return system || updatedSystem
    }

    private fun List<String>.inspectApkPaths(): ApkInspection? {
        val entries = flatMap { path -> inspectApk(path) }
        if (entries.isEmpty()) return null
        val coreEntry = entries.firstOrNull { entry -> entry.coreType != null }
        val goVersion = entries.firstNotNullOfOrNull { entry -> entry.goVersion }
        return ApkInspection(
            appType = entries.firstNotNullOfOrNull { entry -> entry.appType },
            coreType = coreEntry?.coreType,
            corePath = coreEntry?.path,
            goVersion = goVersion,
        )
    }

    private fun inspectApk(path: String): List<ApkEntrySignal> =
        runCatching {
            ZipFile(path).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .mapNotNull { entry ->
                        val name = entry.name
                        val lower = name.lowercase()
                        val coreType =
                            when {
                                "xray" in lower || "v2ray" in lower -> "xray"
                                "sing-box" in lower || "singbox" in lower -> "sing-box"
                                "clash" in lower -> "clash"
                                "shadowsocks" in lower || "ss-local" in lower -> "shadowsocks"
                                else -> null
                            }
                        val appType =
                            when {
                                lower.startsWith("lib/") -> "native"
                                lower.startsWith("assets/") -> "asset"
                                else -> null
                            }
                        val goVersion = GoVersionRegex.find(name)?.value
                        if (coreType == null && appType == null && goVersion == null) {
                            null
                        } else {
                            ApkEntrySignal(path = name, appType = appType, coreType = coreType, goVersion = goVersion)
                        }
                    }.toList()
            }
        }.getOrElse { emptyList() }

    private fun List<String>.normalizeServiceNames(): List<String> =
        map(String::trim).filter(String::isNotEmpty).distinct()

    private data class ApkInspection(
        val appType: String?,
        val coreType: String?,
        val corePath: String?,
        val goVersion: String?,
    )

    private data class ApkEntrySignal(
        val path: String,
        val appType: String?,
        val coreType: String?,
        val goVersion: String?,
    )

    private val GoVersionRegex = Regex("""go\d+\.\d+(?:\.\d+)?""")
    private const val MaxServicesInSuffix = 3
}
