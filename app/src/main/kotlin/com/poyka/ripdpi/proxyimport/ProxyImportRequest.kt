package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile

/**
 * Outcome of classifying an inbound proxy share-link URI.
 *
 * A single share link (`vless://`, `ss://`, `trojan://`, …) maps to a concrete
 * [ProxyProfile] via [com.poyka.ripdpi.data.uri.ProxyUriCodec]; anything the codec
 * cannot interpret resolves to [UnsupportedScheme] so callers surface a typed error
 * rather than crashing.
 */
sealed interface ProxyImportRequest {
    /** A successfully parsed single-profile share link, ready for the confirm screen. */
    data class Profile(
        val profile: ProxyProfile,
    ) : ProxyImportRequest

    /** The URI scheme is not a recognised proxy-node scheme, or the URI is malformed. */
    data class UnsupportedScheme(
        val scheme: String,
    ) : ProxyImportRequest
}
