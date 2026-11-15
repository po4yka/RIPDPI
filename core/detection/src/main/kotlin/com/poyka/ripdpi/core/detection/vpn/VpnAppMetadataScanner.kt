@file:Suppress("ReturnCount", "ComplexCondition")

package com.poyka.ripdpi.core.detection.vpn

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.poyka.ripdpi.core.detection.VpnAppTechnicalMetadata
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object VpnAppMetadataScanner {
    internal fun scan(
        packageInfo: PackageInfo?,
        budget: VpnInventoryBudget,
        serviceNames: List<String> = emptyList(),
        matchedByNameHeuristic: Boolean = false,
    ): VpnAppTechnicalMetadata {
        val appInfo = packageInfo?.applicationInfo
        if (appInfo == null) budget.markIncomplete()
        return VpnAppTechnicalMetadata(
            versionName = packageInfo?.versionName?.boundedInventoryText(),
            serviceNames = serviceNames.mapNotNull(String::boundedInventoryText).distinct(),
            apkLibraryNameHint = appInfo?.findApkLibraryNameHint(budget),
            systemApp = appInfo?.isSystemApp() == true,
            matchedByNameHeuristic = matchedByNameHeuristic,
        )
    }

    fun formatMetadataSuffix(metadata: VpnAppTechnicalMetadata?): String {
        val details = metadataDetails(metadata)
        return if (details.isEmpty()) "" else " [${details.joinToString(", ")}]"
    }

    private fun metadataDetails(metadata: VpnAppTechnicalMetadata?): List<String> {
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

    private fun ApplicationInfo.findApkLibraryNameHint(budget: VpnInventoryBudget): String? {
        var hint = libraryNameInApk(publicSourceDir, budget, searchEntries = true)?.let { "base APK: $it" }
        val splits = splitPublicSourceDirs.orEmpty()
        if (splits.size > MaxSplitArchives) budget.markIncomplete()
        for (path in splits.take(MaxSplitArchives)) {
            if (!budget.canContinue()) break
            val entry = libraryNameInApk(path, budget, searchEntries = hint == null)
            if (entry != null) hint = "split APK: $entry"
        }
        return hint
    }

    private fun libraryNameInApk(
        path: String?,
        budget: VpnInventoryBudget,
        searchEntries: Boolean,
    ): String? {
        if (!budget.takeArchive()) return null
        return try {
            if (path.isNullOrBlank() || File(path).length() > MaxArchiveBytes) {
                budget.markIncomplete()
                return null
            }
            ZipFile(path).use { zip ->
                if (searchEntries) zip.findVpnLibraryName(budget) else null
            }
        } catch (_: IOException) {
            budget.markIncomplete()
            null
        } catch (_: SecurityException) {
            budget.markIncomplete()
            null
        } catch (_: IllegalArgumentException) {
            budget.markIncomplete()
            null
        }
    }

    private fun ZipFile.findVpnLibraryName(budget: VpnInventoryBudget): String? {
        val entries = entries()
        repeat(MaxEntriesPerArchive) {
            if (!budget.canContinue() || !entries.hasMoreElements()) return null
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.name.isVpnLibraryName()) return entry.name
        }
        if (entries.hasMoreElements()) budget.markIncomplete()
        return null
    }

    private fun String.isVpnLibraryName(): Boolean {
        if (length > MaxInventoryTextLength) return false
        val fileName = substringAfterLast('/').lowercase()
        return startsWith("lib/") &&
            count { it == '/' } == 2 &&
            fileName.startsWith("lib") &&
            fileName.endsWith(".so") &&
            VpnLibraryNameMarkers.any { it in fileName } &&
            none(Char::isISOControl)
    }

    private val VpnLibraryNameMarkers =
        listOf("xray", "v2ray", "sing-box", "singbox", "clash", "shadowsocks", "ss-local")
    private const val MaxServicesInSuffix = 3
    private const val MaxSplitArchives = 12
    private const val MaxEntriesPerArchive = 4096
    private const val MaxArchiveBytes = 256L * 1024 * 1024
}

internal fun ApplicationInfo.isSystemApp(): Boolean =
    flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

internal fun String.boundedInventoryText(): String? =
    take(MaxInventoryTextLength).filterNot(Char::isISOControl).trim().takeIf(String::isNotEmpty)

private const val MaxInventoryTextLength = 256
