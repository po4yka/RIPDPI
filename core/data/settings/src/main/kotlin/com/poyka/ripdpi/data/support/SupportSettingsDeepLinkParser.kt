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
        if (trimmed.isBlank()) return SupportSettingsDeepLinkParseResult.Error.Unsupported
        val payload =
            when {
                trimmed.startsWith("$RipDpiScheme://", ignoreCase = true) -> ripDpiPayload(trimmed)
                trimmed.startsWith("$HttpsScheme://", ignoreCase = true) -> httpsPayload(trimmed)
                else -> return SupportSettingsDeepLinkParseResult.Error.Unsupported
            } ?: return SupportSettingsDeepLinkParseResult.Error.Unsupported
        if (payload.isBlank()) return SupportSettingsDeepLinkParseResult.Error.MissingPayload
        if (payload.length > MaxEncodedPayloadLength) return SupportSettingsDeepLinkParseResult.Error.PayloadTooLarge
        return decodePayload(payload)
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
        if (!host.equals(AppLinkHost, ignoreCase = true)) return null
        val pathAndRest = afterScheme.substringAfter(host, missingDelimiterValue = "")
        if (!pathAndRest.startsWith(AppLinkPath)) return null
        return pathAndRest.substringAfter('#', missingDelimiterValue = "").takeIf(String::isNotBlank)
            ?: queryParams(
                pathAndRest.substringAfter('?', missingDelimiterValue = "").substringBefore('#'),
            )[PayloadQueryKey]
    }

    private fun queryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (part in query.split('&')) {
            if (part.isBlank()) continue
            val key = decodePercent(part.substringBefore('=')) ?: return emptyMap()
            val value = decodePercent(part.substringAfter('=', missingDelimiterValue = "")) ?: return emptyMap()
            result.putIfAbsent(key, value)
        }
        return result
    }

    private fun decodePayload(payload: String): SupportSettingsDeepLinkParseResult {
        val bytes =
            try {
                Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '='))
            } catch (_: IllegalArgumentException) {
                return SupportSettingsDeepLinkParseResult.Error.BadEncoding
            }
        return SupportSettingsDeepLinkParseResult.Success(bytes.toString(Charsets.UTF_8))
    }

    private fun decodePercent(raw: String): String? = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
}
