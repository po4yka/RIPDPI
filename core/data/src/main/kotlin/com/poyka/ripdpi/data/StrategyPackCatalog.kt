package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val StrategyPackCatalogSourceBundled = "bundled"
const val StrategyPackCatalogSourceDownloaded = "downloaded"
const val StrategyPackChannelStable = "stable"
const val StrategyPackChannelBeta = "beta"
const val StrategyPackRefreshPolicyManual = "manual"
const val StrategyPackRefreshPolicyAutomatic = "automatic"
const val DefaultStrategyPackChannel = StrategyPackChannelStable
const val DefaultStrategyPackRefreshPolicy = StrategyPackRefreshPolicyAutomatic
const val DefaultStrategyPackPinnedId = ""
const val DefaultStrategyPackPinnedVersion = ""
const val StrategyPackCatalogSchemaVersion = 1

@Serializable
data class StrategyPackCatalog(
    val schemaVersion: Int = StrategyPackCatalogSchemaVersion,
    val generatedAt: String = "",
    val channel: String = StrategyPackChannelStable,
    val minAppVersion: String = "0.0.0",
    val minNativeVersion: String = "0.0.0",
    val notes: String = "",
    val packs: List<StrategyPackDefinition> = emptyList(),
)

@Serializable
data class StrategyPackSnapshot(
    val catalog: StrategyPackCatalog = StrategyPackCatalog(),
    val source: String = StrategyPackCatalogSourceBundled,
    val lastFetchedAtEpochMillis: Long? = null,
    val manifestVersion: String? = null,
    val verifiedChecksumSha256: String? = null,
    val verifiedSignatureBase64: String? = null,
) {
    val packs: List<StrategyPackDefinition>
        get() = catalog.packs
}

@Serializable
data class StrategyPackManifest(
    val schemaVersion: Int = StrategyPackCatalogSchemaVersion,
    val version: String,
    val channel: String = StrategyPackChannelStable,
    val catalogUrl: String,
    val catalogChecksumSha256: String,
    val catalogSignatureBase64: String,
    val signatureAlgorithm: String = StrategyPackSignatureAlgorithmSha256WithEcdsa,
    val keyId: String = DefaultStrategyPackSigningKeyId,
)

@Serializable
data class StrategyPackDefinition(
    val id: String,
    val version: String,
    val title: String,
    val description: String,
    val notes: String = "",
    val triggerMetadata: List<String> = emptyList(),
    val hostLists: List<StrategyPackHostList> = emptyList(),
    val strategies: List<StrategyPackStrategy> = emptyList(),
)

@Serializable
data class StrategyPackHostList(
    val id: String,
    val title: String,
    val description: String = "",
    val hosts: List<String> = emptyList(),
)

@Serializable
data class StrategyPackStrategy(
    val id: String,
    val label: String,
    val recommendedProxyConfigJson: String = "",
    val candidateIds: List<String> = emptyList(),
    val notes: String = "",
)

data class StrategyPackCompatibility(
    val isCompatible: Boolean,
    val reason: String? = null,
)

private val strategyPackJson =
    Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

fun normalizeStrategyPackChannel(value: String): String =
    when (value.trim().lowercase()) {
        StrategyPackChannelBeta -> StrategyPackChannelBeta
        else -> StrategyPackChannelStable
    }

fun normalizeStrategyPackRefreshPolicy(value: String): String =
    when (value.trim().lowercase()) {
        StrategyPackRefreshPolicyManual -> StrategyPackRefreshPolicyManual
        else -> StrategyPackRefreshPolicyAutomatic
    }

fun strategyPackCatalogFromJson(payload: String): StrategyPackCatalog = strategyPackJson.decodeFromString(payload)

fun StrategyPackCatalog.toJson(): String = strategyPackJson.encodeToString(this)

fun strategyPackSnapshotFromJson(payload: String): StrategyPackSnapshot = strategyPackJson.decodeFromString(payload)

fun StrategyPackSnapshot.toJson(): String = strategyPackJson.encodeToString(this)

fun strategyPackManifestFromJson(payload: String): StrategyPackManifest = strategyPackJson.decodeFromString(payload)

fun StrategyPackManifest.toJson(): String = strategyPackJson.encodeToString(this)

fun StrategyPackCatalog.checkCompatibility(
    appVersion: String,
    nativeVersion: String,
): StrategyPackCompatibility {
    if (schemaVersion > StrategyPackCatalogSchemaVersion) {
        return StrategyPackCompatibility(
            isCompatible = false,
            reason = "Unsupported strategy pack schema $schemaVersion",
        )
    }
    if (compareVersionStrings(appVersion, minAppVersion) < 0) {
        return StrategyPackCompatibility(
            isCompatible = false,
            reason = "Requires app version $minAppVersion or newer",
        )
    }
    if (compareVersionStrings(nativeVersion, minNativeVersion) < 0) {
        return StrategyPackCompatibility(
            isCompatible = false,
            reason = "Requires native version $minNativeVersion or newer",
        )
    }
    return StrategyPackCompatibility(isCompatible = true)
}

internal fun compareVersionStrings(
    left: String,
    right: String,
): Int {
    val normalizedLeft = left.parseVersionSegments()
    val normalizedRight = right.parseVersionSegments()
    val segmentCount = maxOf(normalizedLeft.size, normalizedRight.size)
    repeat(segmentCount) { index ->
        val leftSegment = normalizedLeft.getOrElse(index) { 0 }
        val rightSegment = normalizedRight.getOrElse(index) { 0 }
        if (leftSegment != rightSegment) {
            return leftSegment.compareTo(rightSegment)
        }
    }
    return 0
}

private fun String.parseVersionSegments(): List<Int> =
    trim()
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .mapNotNull { segment ->
            segment.toIntOrNull()
        }.ifEmpty { listOf(0) }
