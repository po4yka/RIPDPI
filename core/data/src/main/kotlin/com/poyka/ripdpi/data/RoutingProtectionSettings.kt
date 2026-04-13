package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

const val FakeTlsSourceProfile = "profile"
const val FakeTlsSourceCapturedClientHello = "captured_client_hello"
const val AppRoutingPolicyModeOff = "off"
const val AppRoutingPolicyModePrompt = "prompt"
const val DhtMitigationModeOff = "off"
const val DhtMitigationModeBypass = "bypass"
const val DhtMitigationModeDropWarn = "drop_warn"
const val DefaultAppRoutingRussianPresetId = "russian-mainstream"

fun normalizeFakeTlsSource(value: String): String =
    when (value.trim().lowercase()) {
        FakeTlsSourceCapturedClientHello -> FakeTlsSourceCapturedClientHello
        else -> FakeTlsSourceProfile
    }

fun normalizeAppRoutingPolicyMode(value: String): String =
    when (value.trim().lowercase()) {
        AppRoutingPolicyModeOff -> AppRoutingPolicyModeOff
        else -> AppRoutingPolicyModePrompt
    }

fun normalizeDhtMitigationMode(value: String): String =
    when (value.trim().lowercase()) {
        DhtMitigationModeBypass -> DhtMitigationModeBypass
        DhtMitigationModeDropWarn -> DhtMitigationModeDropWarn
        else -> DhtMitigationModeOff
    }

fun AppSettings.effectiveAppRoutingEnabledPresetIds(): List<String> =
    when {
        appRoutingEnabledPresetIdsCount > 0 -> {
            appRoutingEnabledPresetIdsList
                .map(
                    String::trim,
                ).filter(String::isNotEmpty)
        }

        excludeRussianAppsEnabled -> {
            listOf(DefaultAppRoutingRussianPresetId)
        }

        else -> {
            emptyList()
        }
    }

@Serializable
data class AppRoutingPolicyCatalog(
    val presets: List<AppRoutingPolicyPreset> = emptyList(),
)

@Serializable
data class DhtTriggerCidrsCatalog(
    val cidrs: List<String> = emptyList(),
)

@Serializable
data class AsnRoutingMapCatalog(
    val entries: List<AsnRoutingMapEntry> = emptyList(),
)

@Serializable
data class AsnRoutingMapEntry(
    val asn: Int,
    val label: String,
    val country: String = "",
    val cdn: Boolean = false,
)

@Serializable
data class AppRoutingPackageEntry(
    @SerialName("package") val packageName: String,
    val vpnDetection: Boolean = false,
    val detectionMethods: List<String> = emptyList(),
    val severity: String = "none",
)

@Serializable
data class AppRoutingPolicyPreset(
    val id: String,
    val title: String,
    val exactPackages: List<String> = emptyList(),
    val packages: List<AppRoutingPackageEntry> = emptyList(),
    val packageRegexes: List<String> = emptyList(),
    val detectionMethod: String = "",
    val fixCoverage: String = "",
    val limitations: String = "",
) {
    fun resolvePackages(installedPackages: Set<String>): Set<String> {
        val exactNames =
            if (packages.isNotEmpty()) {
                packages.map { it.packageName }
            } else {
                exactPackages
            }
        val matches = exactNames.map(String::trim).filter(String::isNotEmpty).toMutableSet()
        val regexes = packageRegexes.map(String::trim).filter(String::isNotEmpty).map(::Regex)
        if (regexes.isEmpty()) {
            return matches
        }
        installedPackages.forEach { packageName ->
            if (regexes.any { regex -> regex.matches(packageName) }) {
                matches += packageName
            }
        }
        return matches
    }

    fun findPackageEntry(packageName: String): AppRoutingPackageEntry? = packages.find { it.packageName == packageName }
}

private val appRoutingPolicyJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

fun appRoutingPolicyCatalogFromJson(payload: String): AppRoutingPolicyCatalog =
    appRoutingPolicyJson.decodeFromString(payload)

fun dhtTriggerCidrsCatalogFromJson(payload: String): DhtTriggerCidrsCatalog =
    appRoutingPolicyJson.decodeFromString(payload)

fun asnRoutingMapCatalogFromJson(payload: String): AsnRoutingMapCatalog = appRoutingPolicyJson.decodeFromString(payload)
