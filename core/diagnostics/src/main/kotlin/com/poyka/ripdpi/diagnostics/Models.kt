package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

fun deriveProbeRetryCount(details: List<ProbeDetail>): Int? {
    val detailMap = details.associate { it.key to it.value }
    val fromExplicit =
        detailMap["probeRetryCount"]?.toIntOrNull()?.takeIf { it >= 0 }
            ?: detailMap["retryCount"]?.toIntOrNull()?.takeIf { it >= 0 }
    return fromExplicit ?: detailMap["attempts"]
        ?.split('|')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.size
        ?.let { attempts -> (attempts - 1).takeIf { it > 0 } }
}

fun ProbeResult.withDerivedProbeRetryCount(): ProbeResult =
    copy(probeRetryCount = probeRetryCount ?: deriveProbeRetryCount(details))

private val modelsCompatibilityJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

internal fun decodeProbeDetailsCompat(payload: String): List<ProbeDetail> =
    runCatching {
        modelsCompatibilityJson.decodeFromString(
            ListSerializer(ProbeDetail.serializer()),
            payload,
        )
    }.getOrElse { emptyList() }
