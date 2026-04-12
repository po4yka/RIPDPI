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
const val StrategyPackCatalogSchemaVersion = 3

const val DefaultTlsProfileCatalogVersion = "v1"
const val DefaultTlsProfileSetId = "default"
const val DefaultMorphPolicyId = "off"
const val StrategyFeatureCloudflarePublish = "cloudflare_publish"
const val StrategyFeatureCloudflareConsumeValidation = "cloudflare_consume_validation"
const val StrategyFeatureFinalmask = "finalmask"
const val StrategyFeatureMasqueCloudflareDirect = "masque_cloudflare_direct"
const val StrategyFeatureNaiveProxyWatchdog = "naiveproxy_watchdog"

const val QuicMigrationStatusNotAttempted = "not_attempted"
const val QuicMigrationStatusRebindOnly = "rebind_only"
const val QuicMigrationStatusValidated = "validated"
const val QuicMigrationStatusReverted = "reverted"
const val QuicMigrationStatusFailed = "failed"

@Serializable
data class StrategyPackCatalog(
    val schemaVersion: Int = StrategyPackCatalogSchemaVersion,
    val generatedAt: String = "",
    val channel: String = StrategyPackChannelStable,
    val minAppVersion: String = "0.0.0",
    val minNativeVersion: String = "0.0.0",
    val notes: String = "",
    val packs: List<StrategyPackDefinition> = emptyList(),
    val tlsProfiles: List<StrategyPackTlsProfileSet> = emptyList(),
    val morphPolicies: List<StrategyPackMorphPolicy> = emptyList(),
    val hostLists: List<StrategyPackHostList> = emptyList(),
    val transportModules: List<StrategyPackTransportModule> = emptyList(),
    val featureFlags: List<StrategyPackFeatureFlag> = emptyList(),
    val rollout: StrategyPackRolloutMetadata = StrategyPackRolloutMetadata(),
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
    val hostListRefs: List<String> = emptyList(),
    val tlsProfileSetId: String = "",
    val morphPolicyId: String = "",
    val transportModuleIds: List<String> = emptyList(),
    val featureFlagIds: List<String> = emptyList(),
    val rollout: StrategyPackPackRollout = StrategyPackPackRollout(),
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

@Serializable
data class StrategyPackTlsProfileSet(
    val id: String,
    val title: String,
    val catalogVersion: String = DefaultTlsProfileCatalogVersion,
    val allowedProfileIds: List<String> = emptyList(),
    val rotationEnabled: Boolean = false,
    val notes: String = "",
)

@Serializable
data class StrategyPackMorphPolicy(
    val id: String = DefaultMorphPolicyId,
    val title: String = "Disabled",
    val description: String = "",
    val firstFlightSizeMin: Int = 0,
    val firstFlightSizeMax: Int = 0,
    val paddingEnvelopeMin: Int = 0,
    val paddingEnvelopeMax: Int = 0,
    val entropyTargetPermil: Int = 0,
    val tcpBurstCadenceMs: List<Int> = emptyList(),
    val tlsBurstCadenceMs: List<Int> = emptyList(),
    val quicBurstProfile: String = "",
    val fakePacketShapeProfile: String = "",
    val notes: String = "",
)

@Serializable
data class StrategyPackTransportModule(
    val id: String,
    val kind: String,
    val title: String,
    val configRef: String = "",
    val notes: String = "",
)

@Serializable
data class StrategyPackFeatureFlag(
    val id: String,
    val enabled: Boolean = false,
    val scope: String = "pack",
    val notes: String = "",
)

@Serializable
data class StrategyPackRolloutMetadata(
    val id: String = "",
    val channel: String = "",
    val cohort: String = "",
    val percentage: Int = 0,
    val staged: Boolean = false,
    val notes: String = "",
)

@Serializable
data class StrategyPackPackRollout(
    val cohort: String = "",
    val percentage: Int = 0,
    val staged: Boolean = false,
)

data class StrategyPackResolvedSelection(
    val pack: StrategyPackDefinition? = null,
    val tlsProfileSet: StrategyPackTlsProfileSet? = null,
    val morphPolicy: StrategyPackMorphPolicy? = null,
    val hostLists: List<StrategyPackHostList> = emptyList(),
    val transportModules: List<StrategyPackTransportModule> = emptyList(),
    val featureFlags: List<StrategyPackFeatureFlag> = emptyList(),
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

fun StrategyPackCatalog.resolveSelection(
    pinnedPackId: String = "",
    pinnedPackVersion: String = "",
): StrategyPackResolvedSelection {
    val normalizedPinnedId = pinnedPackId.trim()
    val normalizedPinnedVersion = pinnedPackVersion.trim()
    val selectedPack =
        when {
            normalizedPinnedId.isNotEmpty() && normalizedPinnedVersion.isNotEmpty() -> {
                packs.firstOrNull { it.id == normalizedPinnedId && it.version == normalizedPinnedVersion }
            }

            normalizedPinnedId.isNotEmpty() -> {
                packs.firstOrNull { it.id == normalizedPinnedId }
            }

            else -> {
                packs.firstOrNull()
            }
        }
    val resolvedHostLists =
        buildList {
            addAll(selectedPack?.hostLists.orEmpty())
            val refIds = selectedPack?.hostListRefs.orEmpty().toSet()
            addAll(hostLists.filter { it.id in refIds })
        }.distinctBy(StrategyPackHostList::id)
    val resolvedTlsProfileSet =
        tlsProfiles.firstOrNull { it.id == selectedPack?.tlsProfileSetId }
            ?: tlsProfiles.firstOrNull()
    val resolvedMorphPolicy =
        morphPolicies.firstOrNull { it.id == selectedPack?.morphPolicyId }
            ?: morphPolicies.firstOrNull()
    val resolvedTransportModules =
        if (selectedPack == null) {
            emptyList()
        } else {
            transportModules.filter { it.id in selectedPack.transportModuleIds.toSet() }
        }
    val resolvedFeatureFlags =
        if (selectedPack == null) {
            emptyList()
        } else {
            featureFlags.filter { it.id in selectedPack.featureFlagIds.toSet() }
        }
    return StrategyPackResolvedSelection(
        pack = selectedPack,
        tlsProfileSet = resolvedTlsProfileSet,
        morphPolicy = resolvedMorphPolicy,
        hostLists = resolvedHostLists,
        transportModules = resolvedTransportModules,
        featureFlags = resolvedFeatureFlags,
    )
}

@Suppress("ReturnCount")
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
