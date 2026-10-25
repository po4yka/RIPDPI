package com.poyka.ripdpi.proxyimport

import java.net.URI

/**
 * Classifies user-entered or QR-decoded text as a subscription import request.
 *
 * Accepts the same shapes as the inbound deep-link handler — `ripdpi://import?sub=…|url=…`
 * and `singbox|ripdpi|sn://import-remote-profile?url=…` — plus a bare `https://` URL with a
 * host, which is enrolled as a long-lived subscription. Anything else, including a request
 * that exceeds the hand-off bounds, yields `null`. Never throws.
 */
internal object SubscriptionLinkClassifier {
    fun classify(text: String): PendingSubscriptionImportRequest? {
        val trimmed = text.trim()
        val request =
            ripDpiImport(trimmed)
                ?: singBoxImport(trimmed)
                ?: plainHttps(trimmed)
        return request?.takeIf(PendingSubscriptionImportRequest::isBounded)
    }

    private fun ripDpiImport(text: String): PendingSubscriptionImportRequest? {
        val result = RipDpiImportDeepLinkParser.parse(text) as? RipDpiImportDeepLinkResult.Success ?: return null
        val bootstrap = result.kind == RipDpiImportDeepLinkResult.Success.Kind.OneShot
        return PendingSubscriptionImportRequest(result.targetUrl, name = "", bootstrap = bootstrap)
    }

    private fun singBoxImport(text: String): PendingSubscriptionImportRequest? {
        val result = SingBoxDeepLinkParser.parse(text) as? SingBoxDeepLinkResult.Success ?: return null
        return PendingSubscriptionImportRequest(result.url, result.name, result.bootstrap)
    }

    private fun plainHttps(text: String): PendingSubscriptionImportRequest? {
        val host =
            text
                .takeIf { it.startsWith("https://", ignoreCase = true) }
                ?.let { runCatching { URI(it).host }.getOrNull() }
        return if (host.isNullOrBlank()) null else PendingSubscriptionImportRequest(text, name = "", bootstrap = false)
    }
}
