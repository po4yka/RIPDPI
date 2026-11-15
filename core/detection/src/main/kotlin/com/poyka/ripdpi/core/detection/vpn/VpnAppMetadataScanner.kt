@file:Suppress("ReturnCount", "ComplexCondition")

package com.poyka.ripdpi.core.detection.vpn

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.poyka.ripdpi.core.detection.VpnAppTechnicalMetadata
import java.io.IOException
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
        val apkLibraryNameHint = appInfo?.findApkLibraryNameHint()

        if (packageInfo == null && serviceNames.isEmpty() && !matchedByNameHeuristic) {
            return null
        }

        return VpnAppTechnicalMetadata(
            versionName = packageInfo?.versionName?.takeIf { it.isNotBlank() },
            serviceNames = (serviceNames + packageInfo.vpnServiceNames()).normalizeServiceNames(),
            apkLibraryNameHint = apkLibraryNameHint,
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
            metadata.apkLibraryNameHint?.let { add("APK library name hint: $it") }
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

    private fun ApplicationInfo.isSystemApp(): Boolean {
        val system = flags and ApplicationInfo.FLAG_SYSTEM != 0
        val updatedSystem = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return system || updatedSystem
    }

    private fun ApplicationInfo.findApkLibraryNameHint(): String? {
        libraryNameInApk(publicSourceDir)?.let { return "base APK: $it" }
        for (path in splitPublicSourceDirs.orEmpty()) {
            libraryNameInApk(path)?.let { return "split APK: $it" }
        }
        return null
    }

    private fun libraryNameInApk(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return try {
            ZipFile(path).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .firstOrNull { entry ->
                        !entry.isDirectory && entry.name.isVpnLibraryName()
                    }?.name
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun String.isVpnLibraryName(): Boolean {
        val fileName = substringAfterLast('/').lowercase()
        return startsWith("lib/") && count { it == '/' } == 2 &&
            fileName.startsWith("lib") && fileName.endsWith(".so") &&
            VpnLibraryNameMarkers.any { it in fileName } && none(Char::isISOControl)
    }

    private fun List<String>.normalizeServiceNames(): List<String> =
        map(String::trim).filter(String::isNotEmpty).distinct()

    private val VpnLibraryNameMarkers =
        listOf("xray", "v2ray", "sing-box", "singbox", "clash", "shadowsocks", "ss-local")
    private const val MaxServicesInSuffix = 3
}
