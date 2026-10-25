package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.subscription.isBootstrapUrl

/**
 * Classifies user-entered, pasted, shared, or QR-decoded text as a subscription import request.
 *
 * Accepts the same shapes as the inbound deep-link handler — `ripdpi://import?sub=…|url=…`
 * and the client remote-profile links that [SingBoxDeepLinkParser] knows — plus a bare
 * `https://` URL with a host. A bare URL whose path starts with `/bootstrap/` keeps the
 * one-time bootstrap kind, so its single-use token is consumed through the bootstrap path.
 * Anything else, including a request that exceeds the hand-off bounds, yields `null`.
 * Never throws.
 */
internal object SubscriptionLinkClassifier {
    fun classify(text: String): PendingSubscriptionImportRequest? {
        val trimmed = text.trim().removePrefix(ByteOrderMark).trim()
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
        val usable =
            text.startsWith("https://", ignoreCase = true) &&
                text.none { it.isWhitespace() } &&
                subscriptionHost(text).isNotEmpty()
        return if (usable) PendingSubscriptionImportRequest(text, name = "", bootstrap = isBootstrapUrl(text)) else null
    }
}

/**
 * The host of a subscription [url] without scheme, userinfo, port, path, query, or fragment;
 * an IPv6 literal keeps its brackets. Returns `""` when there is no host. Unlike
 * `java.net.URI`, this accepts hosts with `_`, which real subscription servers use.
 */
internal fun subscriptionHost(url: String): String {
    val authority =
        url
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
    return if (authority.startsWith("[")) {
        authority.substringBefore(']', missingDelimiterValue = "").let { if (it.isEmpty()) "" else "$it]" }
    } else {
        authority.substringBefore(':')
    }
}
