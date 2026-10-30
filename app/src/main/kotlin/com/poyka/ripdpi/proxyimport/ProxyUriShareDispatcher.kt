package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.uri.ProxyUriCodec

/**
 * Classifies a single proxy share-link URI into a [ProxyImportRequest].
 *
 * This is the share-sheet handler's dispatch core: it delegates parsing to the shared
 * [ProxyUriCodec] and never throws — an unknown scheme or a structurally malformed URI
 * of a known scheme both resolve to [ProxyImportRequest.UnsupportedScheme] so the UI can
 * show a typed "unsupported scheme" error instead of crashing.
 */
object ProxyUriShareDispatcher {
    /**
     * Parses [uri] via [ProxyUriCodec] and wraps the result. The returned
     * [ProxyImportRequest.UnsupportedScheme.scheme] is the lower-cased scheme prefix when
     * one can be extracted, or an empty string for input with no `scheme://` prefix.
     */
    fun dispatch(uri: String): ProxyImportRequest {
        val profile = ProxyUriCodec.parse(uri)
        if (profile != null) {
            return ProxyImportRequest.Profile(profile)
        }
        return ProxyImportRequest.UnsupportedScheme(schemeOf(uri))
    }

    /** Extracts the lower-cased `scheme` from `scheme://…`, or `""` when absent. */
    internal fun schemeOf(uri: String): String {
        val trimmed = uri.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return ""
        return trimmed.substring(0, schemeEnd).lowercase()
    }
}
