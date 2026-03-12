package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.GeositeCatalog
import com.poyka.ripdpi.proto.GeositeDomain
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

const val HostPackTargetBlacklist = "blacklist"
const val HostPackTargetWhitelist = "whitelist"
const val HostPackApplyModeReplace = "replace"
const val HostPackApplyModeMerge = "merge"
const val HostPackCatalogSourceBundled = "bundled"
const val HostPackCatalogSourceDownloaded = "downloaded"
const val HostPackCatalogRemoteSourceName = "runetfreedom/russia-blocked-geosite"
const val HostPackCatalogRemoteSourceRef = "release"
const val HostPackCatalogRemoteSourceUrl =
    "https://raw.githubusercontent.com/runetfreedom/russia-blocked-geosite/release/geosite.dat"

@Serializable
data class HostPackCatalog(
    val version: Int = 1,
    val generatedAt: String = "",
    val packs: List<HostPackPreset> = emptyList(),
)

@Serializable
data class HostPackCatalogSnapshot(
    val catalog: HostPackCatalog = HostPackCatalog(),
    val source: String = HostPackCatalogSourceBundled,
    val lastFetchedAtEpochMillis: Long? = null,
    val verifiedChecksumSha256: String? = null,
) {
    val packs: List<HostPackPreset>
        get() = catalog.packs
}

@Serializable
data class HostPackPreset(
    val id: String,
    val title: String,
    val description: String,
    val hostCount: Int,
    val hosts: List<String>,
    val sources: List<HostPackSource> = emptyList(),
)

@Serializable
data class HostPackSource(
    val name: String,
    val url: String,
    val ref: String,
    val commit: String? = null,
)

data class HostPackApplyResult(
    val hostsMode: String,
    val hostsBlacklist: String,
    val hostsWhitelist: String,
)

private data class CuratedHostPackDefinition(
    val id: String,
    val title: String,
    val description: String,
)

private val curatedHostPackDefinitions =
    listOf(
        CuratedHostPackDefinition(
            id = "youtube",
            title = "YouTube",
            description = "Video playback, embeds, and CDN endpoints from public geosite lists.",
        ),
        CuratedHostPackDefinition(
            id = "telegram",
            title = "Telegram",
            description = "Messenger, CDN, and share links bundled from public geosite sources.",
        ),
        CuratedHostPackDefinition(
            id = "discord",
            title = "Discord",
            description = "App, media, invite, and attachment domains from public geosite lists.",
        ),
    )

private val hostPackCatalogJson =
    Json {
        ignoreUnknownKeys = true
    }

fun hostPackCatalogFromJson(payload: String): HostPackCatalog = hostPackCatalogJson.decodeFromString(payload)

fun HostPackCatalog.toJson(): String = hostPackCatalogJson.encodeToString(this)

fun hostPackCatalogSnapshotFromJson(payload: String): HostPackCatalogSnapshot = hostPackCatalogJson.decodeFromString(payload)

fun HostPackCatalogSnapshot.toJson(): String = hostPackCatalogJson.encodeToString(this)

fun normalizeHostSpecToken(token: String): String? {
    if (token.isBlank()) {
        return null
    }

    val normalized = StringBuilder(token.length)
    for (char in token) {
        val lowered =
            when {
                char in 'A'..'Z' -> char.lowercaseChar()
                char in '-'..'9' || char in 'a'..'z' -> char
                else -> return null
            }
        normalized.append(lowered)
    }

    return normalized.toString().takeIf { it.isNotEmpty() }
}

fun extractNormalizedHostTokens(spec: String): List<String> =
    spec
        .splitToSequence(Regex("\\s+"))
        .mapNotNull(::normalizeHostSpecToken)
        .toList()

fun formatHostPackHosts(hosts: List<String>): String =
    hosts
        .asSequence()
        .mapNotNull(::normalizeHostSpecToken)
        .distinct()
        .joinToString(separator = "\n")

fun mergeHostPackHosts(
    existingText: String,
    presetHosts: List<String>,
): String {
    val existingHosts = extractNormalizedHostTokens(existingText).toSet()
    val missingHosts =
        presetHosts
            .asSequence()
            .mapNotNull(::normalizeHostSpecToken)
            .distinct()
            .filterNot(existingHosts::contains)
            .toList()

    if (missingHosts.isEmpty()) {
        return existingText
    }

    val formattedMissing = formatHostPackHosts(missingHosts)
    if (existingText.isBlank()) {
        return formattedMissing
    }

    val separator =
        when {
            existingText.endsWith("\r\n") || existingText.endsWith("\n") -> ""
            else -> "\n"
        }
    return existingText + separator + formattedMissing
}

fun applyCuratedHostPack(
    currentBlacklist: String,
    currentWhitelist: String,
    presetHosts: List<String>,
    targetMode: String,
    applyMode: String,
): HostPackApplyResult {
    val normalizedTargetMode =
        when (targetMode) {
            HostPackTargetWhitelist -> HostPackTargetWhitelist
            else -> HostPackTargetBlacklist
        }
    val currentTargetText =
        when (normalizedTargetMode) {
            HostPackTargetWhitelist -> currentWhitelist
            else -> currentBlacklist
        }
    val updatedTargetText =
        when (applyMode) {
            HostPackApplyModeMerge -> mergeHostPackHosts(currentTargetText, presetHosts)
            else -> formatHostPackHosts(presetHosts)
        }

    return when (normalizedTargetMode) {
        HostPackTargetWhitelist ->
            HostPackApplyResult(
                hostsMode = HostPackTargetWhitelist,
                hostsBlacklist = currentBlacklist,
                hostsWhitelist = updatedTargetText,
            )

        else ->
            HostPackApplyResult(
                hostsMode = HostPackTargetBlacklist,
                hostsBlacklist = updatedTargetText,
                hostsWhitelist = currentWhitelist,
            )
    }
}

fun curatedHostPackCatalogFromGeosite(
    geositeCatalog: GeositeCatalog,
    generatedAt: String,
    source: HostPackSource,
): HostPackCatalog {
    val entriesById =
        geositeCatalog.entryList.associateBy { entry ->
            entry.countryCode.lowercase(Locale.ROOT)
        }

    val packs =
        curatedHostPackDefinitions.map { definition ->
            val entry =
                requireNotNull(entriesById[definition.id]) {
                    "Missing geosite entry for ${definition.id}"
                }
            val hosts =
                entry.domainList
                    .asSequence()
                    .mapNotNull(::safeGeositeDomainToHost)
                    .distinct()
                    .toList()
            require(hosts.isNotEmpty()) {
                "Geosite entry ${definition.id} did not produce any safe host rules"
            }
            HostPackPreset(
                id = definition.id,
                title = definition.title,
                description = definition.description,
                hostCount = hosts.size,
                hosts = hosts,
                sources = listOf(source),
            )
        }

    return HostPackCatalog(
        version = 1,
        generatedAt = generatedAt,
        packs = packs,
    )
}

fun safeGeositeDomainToHost(domain: GeositeDomain): String? =
    when (domain.type) {
        GeositeDomain.Type.ROOT_DOMAIN,
        GeositeDomain.Type.FULL,
            -> normalizeHostSpecToken(domain.value)

        else -> null
    }
