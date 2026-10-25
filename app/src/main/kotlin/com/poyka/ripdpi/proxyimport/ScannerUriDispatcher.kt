package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.wireguard.AmneziaWgConfig
import com.poyka.ripdpi.data.wireguard.WireGuardConfParser

/** Outcome of classifying a QR-decoded text payload. */
sealed interface ScannerScanResult {
    /** The payload is an allowlisted proxy URI and parsed into a profile import request. */
    data class Profile(
        val request: ProxyImportRequest.Profile,
    ) : ScannerScanResult

    /** The payload is a subscription link (`https://…` or a subscription deep link). */
    data class Subscription(
        val request: PendingSubscriptionImportRequest,
    ) : ScannerScanResult

    /** The payload is an AmneziaWG `.conf` file, the format the reference client puts in its QR codes. */
    data class AmneziaWgConf(
        val conf: String,
    ) : ScannerScanResult {
        // The conf carries the private key.
        override fun toString(): String = "AmneziaWgConf(conf=<redacted>)"
    }

    /**
     * The payload is not an allowlisted proxy URI, or a known scheme failed to parse.
     *
     * @property redactedPreview at most the first 16 characters of the payload — the full
     *   QR content is never surfaced or logged, since it may carry credentials.
     */
    data class Invalid(
        val redactedPreview: String,
    ) : ScannerScanResult
}

/**
 * Classifies QR-scanner decoded text. Shared by the live CameraX path and the image-file
 * fallback so both behave identically. A decoded payload is tried, in order, as an
 * allowlisted proxy URI ([ProxyUriShareDispatcher]), a subscription link
 * ([SubscriptionLinkClassifier]), and an AmneziaWG `.conf`; anything else becomes a
 * [ScannerScanResult.Invalid] carrying only a redacted 16-char preview.
 */
object ScannerUriDispatcher {
    private const val REDACTION_LIMIT = 16

    /**
     * Schemes the scanner prefilter allows before parser validation. This is not the
     * full clipboard/share-sheet codec list; [ProxyUriShareDispatcher] still decides
     * whether the payload is a supported single-profile URI.
     */
    val allowedSchemes: Set<String> =
        setOf("vless", "trojan", "mieru", "ss", "hysteria2", "hy2", "tuic", "ripdpi")

    /**
     * Parses [payload] into a [ScannerScanResult]. Never throws; an unparseable or
     * non-allowlisted payload yields [ScannerScanResult.Invalid] with a redacted preview.
     */
    fun dispatch(payload: String): ScannerScanResult {
        val trimmed = payload.trim()
        return profileOf(trimmed)?.let(ScannerScanResult::Profile)
            ?: SubscriptionLinkClassifier.classify(trimmed)?.let(ScannerScanResult::Subscription)
            ?: trimmed.takeIf(::isAmneziaWgConf)?.let(ScannerScanResult::AmneziaWgConf)
            ?: ScannerScanResult.Invalid(redact(trimmed))
    }

    private fun profileOf(text: String): ProxyImportRequest.Profile? =
        text
            .takeIf { ProxyUriShareDispatcher.schemeOf(it) in allowedSchemes }
            ?.let { ProxyUriShareDispatcher.dispatch(it) as? ProxyImportRequest.Profile }

    private fun isAmneziaWgConf(text: String): Boolean =
        text.contains("[Interface]", ignoreCase = true) &&
            runCatching { WireGuardConfParser.parse(text) }.getOrNull() is AmneziaWgConfig

    /** Returns at most the first [REDACTION_LIMIT] characters of [value]. */
    internal fun redact(value: String): String = value.take(REDACTION_LIMIT)
}
