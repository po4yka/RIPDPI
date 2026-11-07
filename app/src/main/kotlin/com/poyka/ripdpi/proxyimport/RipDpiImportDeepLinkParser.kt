package com.poyka.ripdpi.proxyimport

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

        /** The decoded target value is not a complete HTTPS URL. */
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
 * Both parameters must decode to a complete HTTPS URL; anything else yields a typed error.
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
        val scheme = trimmed.substringBefore("://", "").lowercase()
        if (scheme != IMPORT_SCHEME) return RipDpiImportDeepLinkResult.Error.Unsupported
        val parsed = parseImportDeepLinkUri(trimmed) ?: return RipDpiImportDeepLinkResult.Error.BadEncoding
        if (!parsed.rawAuthority.equals(
                IMPORT_HOST,
                ignoreCase = true,
            )
        ) {
            return RipDpiImportDeepLinkResult.Error.Unsupported
        }
        val params = parsed.importQueryParameters()

        val rawSub = params["sub"]?.takeIf { it.isNotBlank() }
        val rawUrl = params["url"]?.takeIf { it.isNotBlank() }

        return when {
            rawSub != null -> {
                if (rawSub.toHttpUrlOrNull()?.isHttps != true) return RipDpiImportDeepLinkResult.Error.NonHttps
                RipDpiImportDeepLinkResult.Success(rawSub, RipDpiImportDeepLinkResult.Success.Kind.Subscription)
            }

            rawUrl != null -> {
                if (rawUrl.toHttpUrlOrNull()?.isHttps != true) return RipDpiImportDeepLinkResult.Error.NonHttps
                RipDpiImportDeepLinkResult.Success(rawUrl, RipDpiImportDeepLinkResult.Success.Kind.OneShot)
            }

            else -> {
                RipDpiImportDeepLinkResult.Error.MissingTarget
            }
        }
    }
}
