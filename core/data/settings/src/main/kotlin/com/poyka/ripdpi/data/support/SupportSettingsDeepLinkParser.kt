package com.poyka.ripdpi.data.support

import java.net.URLDecoder
import java.util.Base64

private const val RipDpiScheme = "ripdpi"
private const val SupportConfigHost = "support-config"
private const val HttpsScheme = "https"
private const val AppLinkHost = "po4yka.github.io"
private const val AppLinkPath = "/RIPDPI/support-config"
private const val PayloadQueryKey = "payload"
private const val MaxEncodedPayloadLength = 24 * 1024
private const val Base64BlockSize = 4
private const val Base64PaddingOffset = Base64BlockSize - 1

sealed interface SupportSettingsDeepLinkParseResult {
    data class Success(
        val packageJson: String,
    ) : SupportSettingsDeepLinkParseResult

    enum class Error : SupportSettingsDeepLinkParseResult {
        Unsupported,
        MissingPayload,
        PayloadTooLarge,
        BadEncoding,
    }
}

object SupportSettingsDeepLinkParser {
    fun parse(deepLink: String): SupportSettingsDeepLinkParseResult {
        val trimmed = deepLink.trim()
        val payload =
            when {
                trimmed.isBlank() -> null
                trimmed.startsWith("$RipDpiScheme://", ignoreCase = true) -> ripDpiPayload(trimmed)
                trimmed.startsWith("$HttpsScheme://", ignoreCase = true) -> httpsPayload(trimmed)
                else -> null
            }
        return when {
            payload == null -> SupportSettingsDeepLinkParseResult.Error.Unsupported
            payload.isBlank() -> SupportSettingsDeepLinkParseResult.Error.MissingPayload
            payload.length > MaxEncodedPayloadLength -> SupportSettingsDeepLinkParseResult.Error.PayloadTooLarge
            else -> decodePayload(payload)
        }
    }

    private fun ripDpiPayload(deepLink: String): String? {
        val afterScheme = deepLink.substringAfter("://", missingDelimiterValue = "")
        val hostAndRest = afterScheme.substringBefore('?').substringBefore('#').substringBefore('/')
        if (!hostAndRest.equals(SupportConfigHost, ignoreCase = true)) return null
        val query = afterScheme.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        return queryParams(query)[PayloadQueryKey]
    }

    private fun httpsPayload(deepLink: String): String? {
        val afterScheme = deepLink.substringAfter("://", missingDelimiterValue = "")
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val pathAndRest = afterScheme.substringAfter(host, missingDelimiterValue = "")
        val path = pathAndRest.substringBefore('?').substringBefore('#')
        return if (host.equals(AppLinkHost, ignoreCase = true) && path == AppLinkPath) {
            pathAndRest.substringAfter('#', missingDelimiterValue = "").takeIf(String::isNotBlank)
                ?: queryParams(
                    pathAndRest.substringAfter('?', missingDelimiterValue = "").substringBefore('#'),
                )[PayloadQueryKey]
        } else {
            null
        }
    }

    private fun queryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val entries = query.split('&').filter(String::isNotBlank).map(::decodeQueryEntry)
        return if (entries.any { it == null }) {
            emptyMap()
        } else {
            buildMap {
                entries.filterNotNull().forEach { (key, value) -> putIfAbsent(key, value) }
            }
        }
    }

    private fun decodeQueryEntry(part: String): Pair<String, String>? {
        val key = decodePercent(part.substringBefore('='))
        val value = decodePercent(part.substringAfter('=', missingDelimiterValue = ""))
        return if (key != null && value != null) key to value else null
    }

    private fun decodePayload(payload: String): SupportSettingsDeepLinkParseResult {
        val bytes =
            try {
                Base64
                    .getUrlDecoder()
                    .decode(
                        payload.padEnd((payload.length + Base64PaddingOffset) / Base64BlockSize * Base64BlockSize, '='),
                    )
            } catch (_: IllegalArgumentException) {
                return SupportSettingsDeepLinkParseResult.Error.BadEncoding
            }
        return SupportSettingsDeepLinkParseResult.Success(bytes.toString(Charsets.UTF_8))
    }

    private fun decodePercent(raw: String): String? = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
}
