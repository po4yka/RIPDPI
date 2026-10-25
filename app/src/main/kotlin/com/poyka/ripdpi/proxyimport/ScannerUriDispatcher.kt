package com.poyka.ripdpi.proxyimport

/** What a block of imported text is. Shared by the QR scanner, the clipboard, and the share sheet. */
sealed interface ImportPayload {
    /** A single proxy share URI, parsed into a profile import request. */
    data class Profile(
        val request: ProxyImportRequest.Profile,
    ) : ImportPayload {
        // Most proxy profiles carry a UUID or password.
        override fun toString(): String = "Profile(<redacted>)"
    }

    /** A subscription link (`https://…` or a client subscription deep link). */
    data class Subscription(
        val request: PendingSubscriptionImportRequest,
    ) : ImportPayload

    /**
     * A WireGuard or AmneziaWG `.conf`: pasted or scanned text (the WireGuard and AmneziaWG
     * apps put the raw `.conf` in their QR codes), or unpacked from an AmneziaVPN `vpn://` key.
     * The editor validates it and reports a precise reason when it cannot be used.
     */
    data class WireGuardConf(
        val conf: String,
    ) : ImportPayload {
        // The conf carries the private key.
        override fun toString(): String = "WireGuardConf(conf=<redacted>)"
    }
}

/**
 * Classifies text from any import surface. Never throws, never logs the text. Tries, in
 * order: a proxy share URI ([ProxyUriShareDispatcher]), a subscription link
 * ([SubscriptionLinkClassifier]), an AmneziaVPN `vpn://` key ([AmneziaVpnKeyDecoder]),
 * and a `.conf` with `[Interface]` and `[Peer]` sections.
 */
object ImportPayloadClassifier {
    fun classify(text: String): ImportPayload? {
        val trimmed = text.trim().removePrefix(ByteOrderMark).trim()
        if (trimmed.isEmpty()) return null
        return (ProxyUriShareDispatcher.dispatch(trimmed) as? ProxyImportRequest.Profile)?.let(ImportPayload::Profile)
            ?: SubscriptionLinkClassifier.classify(trimmed)?.let(ImportPayload::Subscription)
            ?: embeddedShareLink(trimmed)
                ?.let { ProxyUriShareDispatcher.dispatch(it) as? ProxyImportRequest.Profile }
                ?.let(ImportPayload::Profile)
            ?: AmneziaVpnKeyDecoder.confFromKey(trimmed)?.let(ImportPayload::WireGuardConf)
            ?: trimmed.takeIf(::looksLikeWireGuardConf)?.let(ImportPayload::WireGuardConf)
    }

    /** `v2rayng://install-config?url=vless://…` carries a share link, not a subscription URL. */
    private fun embeddedShareLink(text: String): String? =
        (SingBoxDeepLinkParser.parse(text) as? SingBoxDeepLinkResult.Success)
            ?.url
            ?.takeUnless { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

    private fun looksLikeWireGuardConf(text: String): Boolean {
        val sections = text.lineSequence().map { it.trim().lowercase() }.toSet()
        return "[interface]" in sections && "[peer]" in sections
    }
}

/** Outcome of classifying a QR-decoded text payload. */
sealed interface ScannerScanResult {
    /** The payload is something RIPDPI can import. */
    data class Recognized(
        val payload: ImportPayload,
    ) : ScannerScanResult

    /**
     * The payload is not importable.
     *
     * @property redactedPreview at most the first 16 characters of the payload — the full
     *   QR content is never surfaced or logged, since it may carry credentials.
     */
    data class Invalid(
        val redactedPreview: String,
    ) : ScannerScanResult
}

/**
 * Classifies QR-scanner decoded text through [ImportPayloadClassifier]. Shared by the live
 * CameraX path and the image-file fallback so both behave identically.
 */
object ScannerUriDispatcher {
    private const val REDACTION_LIMIT = 16

    /** Never throws; an unknown payload yields [ScannerScanResult.Invalid] with a redacted preview. */
    fun dispatch(payload: String): ScannerScanResult =
        ImportPayloadClassifier.classify(payload)?.let(ScannerScanResult::Recognized)
            ?: ScannerScanResult.Invalid(redact(payload.trim()))

    /** Returns at most the first [REDACTION_LIMIT] characters of [value]. */
    internal fun redact(value: String): String = value.take(REDACTION_LIMIT)
}
