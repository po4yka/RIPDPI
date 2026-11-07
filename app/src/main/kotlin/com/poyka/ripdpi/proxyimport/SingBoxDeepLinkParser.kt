package com.poyka.ripdpi.proxyimport

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

        /** The link has invalid percent-encoding or an invalid HTTPS target. */
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
    private const val HIDDIFY_PATH_IMPORT_PREFIX = "hiddify://$HIDDIFY_PATH_IMPORT_HOST/"
    private const val BOOTSTRAP_PATH_PREFIX = "/bootstrap/"

    /**
     * Parses [deepLink] into a [SingBoxDeepLinkResult]. Never throws: malformed
     * percent-encoding yields [SingBoxDeepLinkResult.Error.BadEncoding], a missing `url=`
     * yields [SingBoxDeepLinkResult.Error.MissingUrl], and any other shape mismatch
     * yields [SingBoxDeepLinkResult.Error.Unsupported].
     */
    @Suppress("ReturnCount")
    fun parse(deepLink: String): SingBoxDeepLinkResult {
        val trimmed = deepLink.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        val hosts = SUPPORTED_HOSTS[scheme] ?: return SingBoxDeepLinkResult.Error.Unsupported
        val nestedTarget =
            trimmed
                .takeIf { it.startsWith(HIDDIFY_PATH_IMPORT_PREFIX, ignoreCase = true) }
                ?.substring(HIDDIFY_PATH_IMPORT_PREFIX.length)
                ?.substringBefore('#')
        // A nested IPv6 authority must be parsed as a URL, not as an outer URI path.
        val outerLink =
            if (nestedTarget == null) {
                trimmed
            } else {
                trimmed.removeRange(
                    HIDDIFY_PATH_IMPORT_PREFIX.length,
                    HIDDIFY_PATH_IMPORT_PREFIX.length + nestedTarget.length,
                )
            }
        val parsed = parseImportDeepLinkUri(outerLink) ?: return SingBoxDeepLinkResult.Error.BadEncoding
        val fragment = parsed.fragment.orEmpty()
        val host = parsed.rawAuthority?.lowercase()
        if (host !in hosts) return SingBoxDeepLinkResult.Error.Unsupported

        // `hiddify://import/<url>` carries the target URL, with its own query, as the path.
        if (scheme == "hiddify" && host == HIDDIFY_PATH_IMPORT_HOST) {
            val rawUrl =
                nestedTarget?.let {
                    parseImportDeepLinkUri(it)?.toString() ?: return SingBoxDeepLinkResult.Error.BadEncoding
                } ?: (parsed.rawPath.orEmpty().removePrefix("/") + parsed.rawQuery?.let { "?$it" }.orEmpty())
            val url =
                (if (rawUrl.contains("://")) rawUrl else decode(rawUrl))
                    ?.takeIf { it.isNotBlank() }
                    ?: return SingBoxDeepLinkResult.Error.MissingUrl
            val target = url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return SingBoxDeepLinkResult.Error.BadEncoding
            return SingBoxDeepLinkResult.Success(
                url = url,
                name = fragment,
                bootstrap = target.encodedPath.startsWith(BOOTSTRAP_PATH_PREFIX),
            )
        }
        val params = parsed.importQueryParameters()

        val url = params["url"]?.takeIf { it.isNotBlank() } ?: return SingBoxDeepLinkResult.Error.MissingUrl
        val target = url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return SingBoxDeepLinkResult.Error.BadEncoding
        val name = params["name"]?.takeIf { it.isNotBlank() } ?: fragment
        return SingBoxDeepLinkResult.Success(
            url = url,
            name = name,
            bootstrap = target.encodedPath.startsWith(BOOTSTRAP_PATH_PREFIX),
        )
    }

    /** URL-decodes [raw] as UTF-8, returning `null` on invalid percent-encoding. */
    private fun decode(raw: String): String? = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
}
