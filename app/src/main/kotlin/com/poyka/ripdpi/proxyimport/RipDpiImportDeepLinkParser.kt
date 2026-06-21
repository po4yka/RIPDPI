package com.poyka.ripdpi.proxyimport

import java.net.URLDecoder

/** Outcome of parsing a `ripdpi://import?sub=…` or `ripdpi://import?url=…` deep link. */
sealed interface RipDpiImportDeepLinkResult {
    /**
     * A well-formed import deep link.
     *
     * @property targetUrl the decoded https URL extracted from the `sub=` or `url=` parameter.
     * @property kind whether the caller should enroll this URL as a long-lived subscription
     *   ([Kind.Subscription]) or fetch-and-import it once ([Kind.OneShot]).
     */
    data class Success(
        val targetUrl: String,
        val kind: Kind,
    ) : RipDpiImportDeepLinkResult {
        enum class Kind { Subscription, OneShot }
    }

    /** Typed parse failures. The handler surfaces these as a toast and does not navigate. */
    enum class Error : RipDpiImportDeepLinkResult {
        /** The URI scheme, host, or both do not match `ripdpi://import`. */
        Unsupported,

        /** Neither `sub=` nor `url=` query parameter is present or non-empty. */
        MissingTarget,

        /** The decoded target value is not an https URL. */
        NonHttps,

        /** A query value contained invalid percent-encoding. */
        BadEncoding,
    }
}

/**
 * Pure parser for the RIPDPI one-tap import deep link: `ripdpi://import?sub=<enc>` and
 * `ripdpi://import?url=<enc>`. Parsing is string-only (no `android.net.Uri`) so it is
 * fully unit-testable without an Android runtime.
 *
 * - `sub=<percent-encoded https URL>` → enroll as a long-lived subscription (the existing
 *   subscription add + background auto-update path).
 * - `url=<percent-encoded https URL>` → one-shot remote import (fetch + import once,
 *   stored as a bootstrap-kind subscription so the auto-update worker skips it).
 *
 * Both parameters must decode to an https URL; anything else is rejected with a typed error.
 */
object RipDpiImportDeepLinkParser {
    private const val IMPORT_SCHEME = "ripdpi"
    private const val IMPORT_HOST = "import"

    /**
     * Parses [deepLink] into a [RipDpiImportDeepLinkResult]. Never throws: malformed
     * percent-encoding yields [RipDpiImportDeepLinkResult.Error.BadEncoding], a missing
     * or non-https value yields the corresponding typed error, and any other shape
     * mismatch yields [RipDpiImportDeepLinkResult.Error.Unsupported].
     */
    @Suppress("ReturnCount")
    fun parse(deepLink: String): RipDpiImportDeepLinkResult {
        val trimmed = deepLink.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return RipDpiImportDeepLinkResult.Error.Unsupported
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != IMPORT_SCHEME) return RipDpiImportDeepLinkResult.Error.Unsupported

        val afterScheme = trimmed.substring(schemeEnd + "://".length)
        val querySeparator = afterScheme.indexOf('?')
        val host =
            if (querySeparator >= 0) {
                afterScheme.substring(0, querySeparator)
            } else {
                afterScheme
            }.substringBefore('/')
        if (!host.equals(IMPORT_HOST, ignoreCase = true)) return RipDpiImportDeepLinkResult.Error.Unsupported

        val query = if (querySeparator >= 0) afterScheme.substring(querySeparator + 1) else ""
        val params =
            when (val parsed = parseQuery(query)) {
                is QueryParseResult.Failure -> return RipDpiImportDeepLinkResult.Error.BadEncoding
                is QueryParseResult.Params -> parsed.values
            }

        val rawSub = params["sub"]?.takeIf { it.isNotBlank() }
        val rawUrl = params["url"]?.takeIf { it.isNotBlank() }

        return when {
            rawSub != null -> {
                if (!rawSub.startsWith("https://", ignoreCase = true)) return RipDpiImportDeepLinkResult.Error.NonHttps
                RipDpiImportDeepLinkResult.Success(rawSub, RipDpiImportDeepLinkResult.Success.Kind.Subscription)
            }

            rawUrl != null -> {
                if (!rawUrl.startsWith("https://", ignoreCase = true)) return RipDpiImportDeepLinkResult.Error.NonHttps
                RipDpiImportDeepLinkResult.Success(rawUrl, RipDpiImportDeepLinkResult.Success.Kind.OneShot)
            }

            else -> {
                RipDpiImportDeepLinkResult.Error.MissingTarget
            }
        }
    }

    private sealed interface QueryParseResult {
        data class Params(
            val values: Map<String, String>,
        ) : QueryParseResult

        data object Failure : QueryParseResult
    }

    @Suppress("ReturnCount")
    private fun parseQuery(query: String): QueryParseResult {
        if (query.isEmpty()) return QueryParseResult.Params(emptyMap())
        val values = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
            val rawValue = if (eq >= 0) pair.substring(eq + 1) else ""
            val key = decode(rawKey) ?: return QueryParseResult.Failure
            val value = decode(rawValue) ?: return QueryParseResult.Failure
            if (key.isNotEmpty() && key !in values) {
                values[key] = value
            }
        }
        return QueryParseResult.Params(values)
    }

    /** URL-decodes [raw] as UTF-8, returning `null` on invalid percent-encoding. */
    private fun decode(raw: String): String? = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
}
