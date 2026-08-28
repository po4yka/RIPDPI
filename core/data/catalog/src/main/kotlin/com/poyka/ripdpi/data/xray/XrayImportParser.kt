package com.poyka.ripdpi.data.xray

import com.poyka.ripdpi.data.XrayConfigValidator
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.json.JsonObject
import java.net.URLDecoder

/**
 * Fail-closed importer for the first two approved Xray profile shapes.
 *
 * Supported inputs:
 * 1. A `vless://` share link carrying a REALITY (`security=reality`) or XHTTP
 *    (`type=xhttp`) VLESS node. Parsed into a typed [XrayProfile] and then run
 *    through the same validate-and-test gate as the renderer.
 * 2. A raw JSON representation of the supported client shape, projected into
 *    the same typed profile. Supplied fields must survive rendering unchanged.
 *
 * ### Fail-closed contract
 * Anything outside the supported surface — an unknown scheme, a non-REALITY /
 * non-XHTTP transport, a missing UUID/host/port, an `allowInsecure=true` flag,
 * REALITY+XHTTP at a broken upstream tag, or malformed JSON — resolves to
 * [Result.Rejected]. The parser NEVER produces a partially-trusted profile and
 * NEVER falls back to a permissive default.
 *
 * ### Redaction contract
 * Every human-readable string a [Result.Rejected] exposes is already scrubbed
 * by [XrayProfileRedactor]: it never echoes a UUID, key, password, server
 * address, or live endpoint from the rejected input, so the import error can be
 * shown in the UI (and logged) without leaking secrets.
 *
 * Pure Kotlin (no `android.net.Uri`, no Android runtime) → fully unit-testable
 * offline.
 */
class XrayImportParser(
    private val renderer: XrayConfigRenderer = XrayConfigRenderer(),
) {
    /**
     * Outcome of an import attempt.
     *
     * [Accepted] carries a validated typed profile for either input format plus
     * the rendered/validated config ready to persist.
     * [Rejected] carries a typed [reason] and a redacted, user-safe [message].
     */
    sealed interface Result {
        data class Accepted(
            val profile: XrayProfile,
            val config: JsonObject,
            val capabilities: List<XrayCapability>,
        ) : Result

        data class Rejected(
            val reason: Reason,
            /** Already redacted; safe to display and log verbatim. */
            val message: String,
        ) : Result
    }

    /** Typed, jargon-free rejection reasons the UI maps to localized copy. */
    enum class Reason {
        /** The input is neither a recognised share link nor parseable JSON. */
        UNRECOGNISED_INPUT,

        /** A recognised share link, but a transport/security RIPDPI does not support. */
        UNSUPPORTED_TRANSPORT,

        /** A required field (UUID, host, port, REALITY key/SNI) is missing. */
        MISSING_REQUIRED_FIELD,

        /** The profile parsed but failed the safety/validation gate (e.g. allowInsecure). */
        FAILED_SAFETY_CHECK,
    }

    private val json = RipDpiJson

    /**
     * Parse [input] (a share link or raw JSON config) for the given
     * [upstreamTag]. [profileName] labels a share-link-derived profile.
     */
    fun parse(
        input: String,
        upstreamTag: String,
        profileName: String = "Imported profile",
    ): Result {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith(VLESS_SCHEME, ignoreCase = true) -> {
                parseVlessLink(trimmed, upstreamTag, profileName)
            }

            looksLikeJson(trimmed) -> {
                parseRawJson(trimmed, upstreamTag, profileName)
            }

            else -> {
                Result.Rejected(
                    reason = Reason.UNRECOGNISED_INPUT,
                    message = "Unrecognised profile. Paste a supported share link or config.",
                )
            }
        }
    }

    @Suppress("ReturnCount")
    private fun parseVlessLink(
        link: String,
        upstreamTag: String,
        profileName: String,
    ): Result {
        val parsed =
            parseVlessUri(link)
                ?: return Result.Rejected(
                    Reason.MISSING_REQUIRED_FIELD,
                    "Profile link is incomplete or malformed.",
                )

        val insecure = parsed.params["allowInsecure"] !in setOf(null, "0", "false")
        val unknownEncryption = parsed.params["encryption"] !in setOf(null, "none")
        if (parsed.params.keys.any { it !in supportedParametersFor(parsed.params) } || insecure || unknownEncryption) {
            return Result.Rejected(Reason.FAILED_SAFETY_CHECK, "Profile contains unsupported or unsafe options.")
        }

        val type = parsed.params["type"]?.lowercase() ?: "tcp"
        val networkEnum =
            when (type) {
                "tcp" -> {
                    XrayProfile.Network.TCP
                }

                "xhttp" -> {
                    XrayProfile.Network.XHTTP
                }

                else -> {
                    return Result.Rejected(
                        Reason.UNSUPPORTED_TRANSPORT,
                        "This profile uses an unsupported tunnel transport.",
                    )
                }
            }

        val stream =
            when (val layer = buildStreamLayer(parsed)) {
                is StreamLayer.Resolved -> layer
                is StreamLayer.Failure -> return Result.Rejected(layer.reason, layer.message)
            }

        val profile =
            XrayProfile(
                name = profileName,
                outbound =
                    XrayProfile.Outbound(
                        serverAddress = parsed.host,
                        serverPort = parsed.port,
                        uuid = parsed.uuid,
                        flow =
                            parsed.params["flow"]
                                ?: if (networkEnum == XrayProfile.Network.XHTTP) "" else "xtls-rprx-vision",
                        security = stream.security,
                        network = networkEnum,
                        reality = stream.reality,
                        tls = stream.tls,
                        xhttp = if (networkEnum == XrayProfile.Network.XHTTP) buildXhttp(parsed) else null,
                    ),
            )

        return gate(profile, upstreamTag)
    }

    /** Resolved stream-layer security selection, or a typed failure to reject with. */
    private sealed interface StreamLayer {
        data class Resolved(
            val security: XrayProfile.Security,
            val reality: XrayProfile.Reality?,
            val tls: XrayProfile.Tls?,
        ) : StreamLayer

        data class Failure(
            val reason: Reason,
            val message: String,
        ) : StreamLayer
    }

    private fun buildStreamLayer(parsed: VlessUri): StreamLayer =
        when (parsed.params["security"]?.lowercase()) {
            "reality" -> {
                buildRealityLayer(parsed)
            }

            "tls" -> {
                buildTlsLayer(parsed)
            }

            else -> {
                StreamLayer.Failure(
                    Reason.UNSUPPORTED_TRANSPORT,
                    "This profile uses an unsupported security mode.",
                )
            }
        }

    private fun buildRealityLayer(parsed: VlessUri): StreamLayer {
        val pbk = parsed.params["pbk"]
        val sni = parsed.params["sni"]
        if (pbk.isNullOrBlank() || sni.isNullOrBlank()) {
            return StreamLayer.Failure(
                Reason.MISSING_REQUIRED_FIELD,
                "Profile is missing required masquerading parameters.",
            )
        }
        return StreamLayer.Resolved(
            security = XrayProfile.Security.REALITY,
            reality =
                XrayProfile.Reality(
                    publicKey = pbk,
                    serverName = sni,
                    shortId = parsed.params["sid"].orEmpty(),
                    fingerprint = parsed.params["fp"].orEmpty().ifBlank { "chrome" },
                ),
            tls = null,
        )
    }

    private fun buildTlsLayer(parsed: VlessUri): StreamLayer {
        val sni = parsed.params["sni"]
        if (sni.isNullOrBlank()) {
            return StreamLayer.Failure(
                Reason.MISSING_REQUIRED_FIELD,
                "Profile is missing the required server name.",
            )
        }
        return StreamLayer.Resolved(
            security = XrayProfile.Security.TLS,
            reality = null,
            tls =
                XrayProfile.Tls(
                    serverName = sni,
                    fingerprint = parsed.params["fp"].orEmpty().ifBlank { "chrome" },
                    // Imports are forced safe; allowInsecure can never be opted in via a link.
                    allowInsecure = false,
                ),
        )
    }

    private fun buildXhttp(parsed: VlessUri): XrayProfile.Xhttp =
        XrayProfile.Xhttp(
            path =
                if ("path" in parsed.params) {
                    parsed.params.getValue("path")
                } else {
                    "/"
                },
            mode = parsed.params["mode"].orEmpty().ifBlank { "auto" },
            host = parsed.params["host"].orEmpty(),
        )

    /** Render + validate a typed profile, mapping rejection to a redacted message. */
    private fun gate(
        profile: XrayProfile,
        upstreamTag: String,
    ): Result =
        when (val rendered = renderer.render(profile, upstreamTag)) {
            is XrayConfigRenderer.Result.Success -> {
                Result.Accepted(
                    profile = profile,
                    config = rendered.config,
                    capabilities = XrayCapability.forProfile(profile),
                )
            }

            is XrayConfigRenderer.Result.Rejected -> {
                Result.Rejected(
                    reason = Reason.FAILED_SAFETY_CHECK,
                    message = redactedRejection(rendered),
                )
            }
        }

    @Suppress("ReturnCount")
    private fun parseRawJson(
        raw: String,
        upstreamTag: String,
        profileName: String,
    ): Result {
        val config =
            runCatching { json.decodeFromString(JsonObject.serializer(), raw) }
                .getOrNull()
                ?: return Result.Rejected(
                    Reason.UNRECOGNISED_INPUT,
                    "Could not read the pasted config as valid JSON.",
                )

        return when (val validated = renderer.validateImported(config, upstreamTag)) {
            is XrayConfigRenderer.Result.Success -> {
                val profile =
                    XrayJsonProfileReader.read(config, profileName)
                        ?: return unsupportedJson()
                when (val result = gate(profile, upstreamTag)) {
                    is Result.Accepted -> {
                        if (XrayJsonProfileReader.preservesInput(
                                config,
                                result.config,
                            )
                        ) {
                            result
                        } else {
                            unsupportedJson()
                        }
                    }

                    is Result.Rejected -> {
                        result
                    }
                }
            }

            is XrayConfigRenderer.Result.Rejected -> {
                Result.Rejected(
                    reason = Reason.FAILED_SAFETY_CHECK,
                    message = redactedRejection(validated),
                )
            }
        }
    }

    private fun unsupportedJson() =
        Result.Rejected(
            Reason.FAILED_SAFETY_CHECK,
            "Config contains unsupported or incomplete provider settings.",
        )

    /**
     * Builds a user-safe, redacted message from a renderer rejection. Validator
     * messages are static (no secrets) but are still routed through
     * [XrayProfileRedactor.redactText] defensively; the tester message (which
     * may echo input) is always redacted.
     */
    private fun redactedRejection(rejected: XrayConfigRenderer.Result.Rejected): String {
        val parts = mutableListOf<String>()
        rejected.validationErrors.forEach { parts += describe(it.code) }
        rejected.testError?.let { parts += XrayProfileRedactor.redactText(it) }
        return if (parts.isEmpty()) {
            "Profile failed the safety check."
        } else {
            parts.joinToString(" ")
        }
    }

    /** Jargon-free, secret-free description of a validation error code. */
    private fun describe(code: XrayConfigValidator.ErrorCode): String =
        when (code) {
            XrayConfigValidator.ErrorCode.PROFILE_INVALID -> {
                "Profile contains invalid connection settings."
            }

            XrayConfigValidator.ErrorCode.VLESS_FLOW_MISSING -> {
                "Profile is missing a required connection setting."
            }

            XrayConfigValidator.ErrorCode.VLESS_FLOW_UNSUPPORTED -> {
                "Profile flow is incompatible with its transport."
            }

            XrayConfigValidator.ErrorCode.ALLOW_INSECURE_DISABLED -> {
                "Profile disables certificate checks, which is not allowed."
            }

            XrayConfigValidator.ErrorCode.REALITY_XHTTP_BROKEN_AT_TAG -> {
                "Profile uses a transport combination known to be broken."
            }
        }

    private fun looksLikeJson(s: String): Boolean = s.startsWith("{") && s.endsWith("}")

    /** Parsed `vless://uuid@host:port?params#name` skeleton, or null if malformed. */
    private data class VlessUri(
        val uuid: String,
        val host: String,
        val port: Int,
        val params: Map<String, String>,
    )

    @Suppress("ReturnCount")
    private fun parseVlessUri(link: String): VlessUri? {
        val body = link.substring(VLESS_SCHEME.length)
        val afterFragment = body.substringBefore('#')
        val beforeQuery = afterFragment.substringBefore('?')
        val query = afterFragment.substringAfter('?', "")

        val atIndex = beforeQuery.indexOf('@')
        if (atIndex <= 0) return null
        val uuid = beforeQuery.substring(0, atIndex)
        val hostPort = beforeQuery.substring(atIndex + 1)
        if (uuid.isBlank() || hostPort.isBlank()) return null

        val colonIndex = hostPort.lastIndexOf(':')
        if (colonIndex <= 0 || colonIndex == hostPort.length - 1) return null
        val host = hostPort.substring(0, colonIndex)
        val port = hostPort.substring(colonIndex + 1).toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..PORT_MAX) return null

        val params = parseQuery(query) ?: return null
        return VlessUri(uuid = uuid, host = host, port = port, params = params)
    }

    private fun parseQuery(query: String): Map<String, String>? =
        runCatching {
            if (query.isBlank()) {
                emptyMap()
            } else {
                val entries =
                    query.split('&').map { pair ->
                        val eq = pair.indexOf('=')
                        require(eq > 0)
                        val key = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                        val value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                        key to value
                    }
                require(entries.map { it.first }.distinct().size == entries.size)
                entries.toMap()
            }
        }.getOrNull()

    companion object {
        private const val VLESS_SCHEME = "vless://"
        private const val PORT_MAX = 65_535

        private fun supportedParametersFor(params: Map<String, String>): Set<String> {
            val xhttp = params["type"]?.lowercase() == "xhttp"
            val reality = params["security"]?.lowercase() == "reality"
            return supportedParameters +
                (if (xhttp) setOf("path", "mode", "host") else emptySet()) +
                (if (reality) setOf("pbk", "sid") else setOf("allowInsecure"))
        }

        private val supportedParameters =
            setOf(
                "type",
                "security",
                "sni",
                "fp",
                "flow",
                "encryption",
            )
    }
}
