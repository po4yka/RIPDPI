package com.poyka.ripdpi.proxyimport

/** Outcome of classifying a QR-decoded text payload. */
sealed interface ScannerScanResult {
    /** The payload is an allowlisted proxy URI and parsed into a profile import request. */
    data class Profile(
        val request: ProxyImportRequest.Profile,
    ) : ScannerScanResult

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
 * fallback so both behave identically. A decoded payload is validated against the
 * scanner prefilter and parsed via [ProxyUriShareDispatcher]; anything else becomes a
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
        setOf("vless", "vmess", "trojan", "ss", "hysteria", "hysteria2", "hy2", "tuic", "ripdpi")

    /**
     * Parses [payload] into a [ScannerScanResult]. Never throws; an unparseable or
     * non-allowlisted payload yields [ScannerScanResult.Invalid] with a redacted preview.
     */
    fun dispatch(payload: String): ScannerScanResult {
        val trimmed = payload.trim()
        val scheme = ProxyUriShareDispatcher.schemeOf(trimmed)
        if (scheme.isEmpty() || scheme !in allowedSchemes) {
            return ScannerScanResult.Invalid(redact(trimmed))
        }
        return when (val request = ProxyUriShareDispatcher.dispatch(trimmed)) {
            is ProxyImportRequest.Profile -> ScannerScanResult.Profile(request)
            is ProxyImportRequest.UnsupportedScheme -> ScannerScanResult.Invalid(redact(trimmed))
        }
    }

    /** Returns at most the first [REDACTION_LIMIT] characters of [value]. */
    internal fun redact(value: String): String = value.take(REDACTION_LIMIT)
}
