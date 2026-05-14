package com.poyka.ripdpi.proxyimport

import java.net.URLDecoder

/** Outcome of parsing a `singbox://import-remote-profile?url=…` style deep link. */
sealed interface SingBoxDeepLinkResult {
    /**
     * The deep link is a well-formed subscription import request.
     *
     * @property url the URL-decoded subscription URL (`url=`).
     * @property name the URL-decoded display name (`name=`), or `""` when absent.
     * @property bootstrap `true` when the [url] path begins with `/bootstrap/`, signalling
     *   the one-time bootstrap-token import shape.
     */
    data class Success(
        val url: String,
        val name: String,
        val bootstrap: Boolean,
    ) : SingBoxDeepLinkResult

    /** Typed parse failures. The handler surfaces these as a toast and does not navigate. */
    enum class Error : SingBoxDeepLinkResult {
        /** Scheme or host does not match the `import-remote-profile` deep-link shape. */
        Unsupported,

        /** `url=` query parameter is missing or empty. */
        MissingUrl,

        /** A query value contained invalid percent-encoding. */
        BadEncoding,
    }
}

/**
 * Pure parser for the subscription-import deep link the deployer's recipient page hands
 * out: `singbox://import-remote-profile?url=<enc>&name=<enc>`. The `ripdpi://` and `sn://`
 * schemes share the identical shape. Parsing is string-only (no `android.net.Uri`) so it
 * is fully unit-testable; the handler activity feeds it the raw intent-data string.
 *
 * Convention source: sing-box-for-Android / NekoBox; deployer renderer
 * `ripdpi-vpn-deploy/vpnd/src/pages/recipient.rs`.
 */
object SingBoxDeepLinkParser {
    private val SUPPORTED_SCHEMES = setOf("singbox", "ripdpi", "sn")
    private const val IMPORT_HOST = "import-remote-profile"
    private const val BOOTSTRAP_PATH_PREFIX = "/bootstrap/"

    /**
     * Parses [deepLink] into a [SingBoxDeepLinkResult]. Never throws: malformed
     * percent-encoding yields [SingBoxDeepLinkResult.Error.BadEncoding], a missing `url=`
     * yields [SingBoxDeepLinkResult.Error.MissingUrl], and any other shape mismatch
     * yields [SingBoxDeepLinkResult.Error.Unsupported].
     */
    fun parse(deepLink: String): SingBoxDeepLinkResult {
        val trimmed = deepLink.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return SingBoxDeepLinkResult.Error.Unsupported
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme !in SUPPORTED_SCHEMES) return SingBoxDeepLinkResult.Error.Unsupported

        val afterScheme = trimmed.substring(schemeEnd + "://".length)
        val querySeparator = afterScheme.indexOf('?')
        val host =
            if (querySeparator >= 0) {
                afterScheme.substring(0, querySeparator)
            } else {
                afterScheme
            }.substringBefore('/')
        if (host != IMPORT_HOST) return SingBoxDeepLinkResult.Error.Unsupported

        val query = if (querySeparator >= 0) afterScheme.substring(querySeparator + 1) else ""
        val params =
            when (val parsed = parseQuery(query)) {
                is QueryParseResult.Failure -> return SingBoxDeepLinkResult.Error.BadEncoding
                is QueryParseResult.Params -> parsed.values
            }

        val url = params["url"]?.takeIf { it.isNotBlank() } ?: return SingBoxDeepLinkResult.Error.MissingUrl
        val name = params["name"].orEmpty()
        return SingBoxDeepLinkResult.Success(
            url = url,
            name = name,
            bootstrap = isBootstrapUrl(url),
        )
    }

    private sealed interface QueryParseResult {
        data class Params(
            val values: Map<String, String>,
        ) : QueryParseResult

        data object Failure : QueryParseResult
    }

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

    private fun isBootstrapUrl(url: String): Boolean {
        // Strip scheme + authority so only the path is inspected, then look for /bootstrap/.
        val schemeEnd = url.indexOf("://")
        val afterAuthority =
            if (schemeEnd >= 0) {
                val rest = url.substring(schemeEnd + "://".length)
                val slash = rest.indexOf('/')
                if (slash >= 0) rest.substring(slash) else ""
            } else {
                url
            }
        val path = afterAuthority.substringBefore('?').substringBefore('#')
        return path.startsWith(BOOTSTRAP_PATH_PREFIX)
    }
}
