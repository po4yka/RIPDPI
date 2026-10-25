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
 * Pure parser for the remote-profile (subscription) deep links that proxy clients hand out.
 * Parsing is string-only (no `android.net.Uri`) so it is fully unit-testable; the handler
 * activity, the QR scanner, and the clipboard feed it raw text.
 *
 * Accepted shapes (the display name comes from `name=` or, when that is absent, the
 * `#fragment`):
 * - `sing-box://import-remote-profile?url=<enc>#<name>` (sing-box docs/clients/general.md),
 *   plus the `singbox`, `ripdpi` and `sn` spellings the deployer recipient page uses;
 * - `sn://subscription?url=<enc>&name=<enc>` (NekoBox);
 * - `clash://install-config?url=<enc>&name=<enc>` and `clashmeta://…` (Clash Meta);
 * - `v2rayng://install-config?url=<enc>#<name>` and `v2rayng://install-sub?url=…`;
 * - `hiddify://install-config?url=<enc>#<name>` and `hiddify://import/<url>#<name>`.
 *
 * Deployer renderer: `ripdpi-vpn-deploy/vpnd/src/pages/recipient.rs`.
 */
object SingBoxDeepLinkParser {
    private val SUPPORTED_HOSTS: Map<String, Set<String>> =
        mapOf(
            "sing-box" to setOf("import-remote-profile"),
            "singbox" to setOf("import-remote-profile"),
            "ripdpi" to setOf("import-remote-profile"),
            "sn" to setOf("import-remote-profile", "subscription"),
            "clash" to setOf("install-config"),
            "clashmeta" to setOf("install-config"),
            "v2rayng" to setOf("install-config", "install-sub"),
            "hiddify" to setOf("install-config", HIDDIFY_PATH_IMPORT_HOST),
        )
    private const val HIDDIFY_PATH_IMPORT_HOST = "import"
    private const val BOOTSTRAP_PATH_PREFIX = "/bootstrap/"

    /** Schemes this parser can accept; the manifest registers the same set. */
    val supportedSchemes: Set<String> get() = SUPPORTED_HOSTS.keys

    /**
     * Parses [deepLink] into a [SingBoxDeepLinkResult]. Never throws: malformed
     * percent-encoding yields [SingBoxDeepLinkResult.Error.BadEncoding], a missing `url=`
     * yields [SingBoxDeepLinkResult.Error.MissingUrl], and any other shape mismatch
     * yields [SingBoxDeepLinkResult.Error.Unsupported].
     */
    @Suppress("ReturnCount")
    fun parse(deepLink: String): SingBoxDeepLinkResult {
        val trimmed = deepLink.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return SingBoxDeepLinkResult.Error.Unsupported
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        val hosts = SUPPORTED_HOSTS[scheme] ?: return SingBoxDeepLinkResult.Error.Unsupported

        val afterScheme = trimmed.substring(schemeEnd + "://".length)
        val beforeFragment = afterScheme.substringBefore('#')
        val fragment = decode(afterScheme.substringAfter('#', "")) ?: return SingBoxDeepLinkResult.Error.BadEncoding
        val host = beforeFragment.substringBefore('?').substringBefore('/').lowercase()
        if (host !in hosts) return SingBoxDeepLinkResult.Error.Unsupported

        // `hiddify://import/<url>` carries the target URL, with its own query, as the path.
        if (scheme == "hiddify" && host == HIDDIFY_PATH_IMPORT_HOST) {
            val rawUrl = beforeFragment.substringAfter('/', "")
            val url =
                (if (rawUrl.contains("://")) rawUrl else decode(rawUrl))
                    ?.takeIf { it.isNotBlank() }
                    ?: return SingBoxDeepLinkResult.Error.MissingUrl
            return SingBoxDeepLinkResult.Success(url = url, name = fragment, bootstrap = isBootstrapUrl(url))
        }

        val query = beforeFragment.substringAfter('?', "")
        val params =
            when (val parsed = parseQuery(query)) {
                is QueryParseResult.Failure -> return SingBoxDeepLinkResult.Error.BadEncoding
                is QueryParseResult.Params -> parsed.values
            }

        val url = params["url"]?.takeIf { it.isNotBlank() } ?: return SingBoxDeepLinkResult.Error.MissingUrl
        val name = params["name"]?.takeIf { it.isNotBlank() } ?: fragment
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
