package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.uri.AmneziaWgUriCodec
import com.poyka.ripdpi.data.wireguard.WireGuardConfParser
import com.poyka.ripdpi.data.wireguard.toWgQuickConf

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
 * an `amneziawg://` link ([AmneziaWgUriCodec], rendered back to `.conf` text), and a
 * `.conf` with `[Interface]` and `[Peer]` sections.
 */
object ImportPayloadClassifier {
    fun classify(text: String): ImportPayload? {
        val trimmed = text.trim().removePrefix(ByteOrderMark).trim()
        if (trimmed.isEmpty()) return null
        return (ProxyUriShareDispatcher.dispatch(trimmed) as? ProxyImportRequest.Profile)?.let(ImportPayload::Profile)
            ?: SubscriptionLinkClassifier.classify(trimmed)?.let(ImportPayload::Subscription)
            ?: embeddedShareProfile(trimmed)?.let(ImportPayload::Profile)
            ?: AmneziaVpnKeyDecoder.confFromKey(trimmed)?.let(ImportPayload::WireGuardConf)
            ?: amneziaWgConf(trimmed)?.let(ImportPayload::WireGuardConf)
            ?: trimmed.takeIf(WireGuardConfParser::hasInterfaceAndPeerSections)?.let(ImportPayload::WireGuardConf)
    }

    private fun amneziaWgConf(text: String): String? =
        try {
            AmneziaWgUriCodec.decode(text)?.toWgQuickConf()
        } catch (_: IllegalArgumentException) {
            null
        }

    /** `v2rayng://install-config?url=vless://…` carries a share link, not a subscription URL. */
    internal fun embeddedShareProfile(text: String): ProxyImportRequest.Profile? =
        parseImportDeepLinkUri(text)
            ?.takeIf {
                it.scheme.equals("v2rayng", ignoreCase = true) &&
                    it.rawAuthority.equals("install-config", ignoreCase = true)
            }?.importQueryParameters()
            ?.get("url")
            ?.let { ProxyUriShareDispatcher.dispatch(it) as? ProxyImportRequest.Profile }
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
     * @property redactedPreview a fixed marker that never contains input text or credentials.
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
    /** Never throws; an unknown payload yields [ScannerScanResult.Invalid] with a redacted preview. */
    fun dispatch(payload: String): ScannerScanResult =
        ImportPayloadClassifier.classify(payload)?.let(ScannerScanResult::Recognized)
            ?: ScannerScanResult.Invalid("<redacted>")
}
