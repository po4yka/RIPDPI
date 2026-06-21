package com.poyka.ripdpi.data.xray

/**
 * Scrubs secrets and endpoint identifiers out of any string that may reach a
 * log line, diagnostics export, or bug report.
 *
 * Two surfaces:
 * - [redact] for a typed [XrayProfile] (precise: knows exactly which fields are
 *   secret).
 * - [redactText] for an arbitrary string (best-effort pattern scrub of a
 *   rendered config, an error message, or any free-form diagnostic blob).
 *
 * What is removed: VLESS UUIDs, REALITY public/private keys, passwords, server
 * addresses, and live `host:port` endpoints. Each is replaced with a stable
 * placeholder so the redacted output is still readable and diff-stable.
 *
 * Tracks `docs/tasks/issues/render-validated-xray-client-configs.md`.
 */
object XrayProfileRedactor {
    const val REDACTED: String = "[REDACTED]"

    /** RFC-4122 UUID, e.g. a VLESS user id. */
    private val uuidRegex =
        Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )

    /**
     * JSON value of a known-secret field. Matches `"key": "value"` /
     * `"key":"value"` for the field names that carry secrets, replacing the
     * value while preserving the key so the structure stays legible.
     */
    private val secretFieldRegex =
        Regex(
            "(\"(?:id|publicKey|privateKey|password)\"\\s*:\\s*\")[^\"]*(\")",
        )

    /** `"address": "value"` / `"serverName": "value"` — endpoint identifiers. */
    private val addressFieldRegex =
        Regex(
            "(\"(?:address|serverName|host|path)\"\\s*:\\s*\")[^\"]*(\")",
        )

    /** Full share links can appear in lower-level parse/test errors; hide them as one unit. */
    private val vlessLinkRegex = Regex("vless://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

    /** URL query values for VLESS/Reality/xHTTP material when only a fragment is echoed. */
    private val querySecretRegex =
        Regex(
            "((?:^|[?&#\\s])(?:id|uuid|pbk|sid|sni|host|path|password)=)[^&#\\s\"'<>]+",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Returns a redacted, human-readable single-line summary of [profile].
     * Never includes a UUID, key, password, server address, or live endpoint.
     */
    fun redact(profile: XrayProfile): String {
        val o = profile.outbound
        val security = o.security.name.lowercase()
        val network = o.network.name.lowercase()
        return buildString {
            append("XrayProfile(name=")
            append(profile.name)
            append(", server=")
            append(REDACTED)
            append(":")
            append(REDACTED)
            append(", uuid=")
            append(REDACTED)
            append(", flow=")
            append(o.flow)
            append(", security=")
            append(security)
            append(", network=")
            append(network)
            if (o.reality != null) {
                append(", reality.sni=")
                append(REDACTED)
                append(", reality.publicKey=")
                append(REDACTED)
            }
            if (o.tls != null) {
                append(", tls.sni=")
                append(REDACTED)
                append(", tls.allowInsecure=")
                append(o.tls.allowInsecure)
            }
            if (o.xhttp != null) {
                append(", xhttp.mode=")
                append(o.xhttp.mode)
            }
            append(", inbound=")
            append(profile.inbound.listen)
            append(":")
            append(profile.inbound.port)
            append(")")
        }
    }

    /**
     * Best-effort scrub of an arbitrary diagnostic string (rendered config
     * JSON, error message, free-form blob). Order matters: secret-field and
     * address-field substitutions run first, then any remaining bare UUID is
     * caught by the UUID pattern.
     */
    fun redactText(text: String): String =
        text
            .replace(secretFieldRegex) { "${it.groupValues[1]}$REDACTED${it.groupValues[2]}" }
            .replace(addressFieldRegex) { "${it.groupValues[1]}$REDACTED${it.groupValues[2]}" }
            .replace(vlessLinkRegex) { "vless://$REDACTED" }
            .replace(querySecretRegex) { "${it.groupValues[1]}$REDACTED" }
            .replace(uuidRegex, REDACTED)
}
