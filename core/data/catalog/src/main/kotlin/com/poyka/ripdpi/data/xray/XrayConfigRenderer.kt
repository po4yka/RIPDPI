package com.poyka.ripdpi.data.xray

import com.poyka.ripdpi.data.XrayConfigValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renders a typed [XrayProfile] into the local-inbound + outbound xray-core
 * config JSON used by the
 * [com.poyka.ripdpi.data.xray.XrayTunnelTopology.TunToLocalInbound] topology,
 * then gates the result through [XrayConfigValidator] and an injected
 * [XrayConfigTester] before it can be saved or started.
 *
 * The rendered object is the exact `JsonObject` shape [XrayConfigValidator]
 * consumes (`outbounds[].settings.vnext[].users[].flow`,
 * `outbounds[].streamSettings.{network,security,realitySettings,tlsSettings}`),
 * so the renderer and validator never drift.
 *
 * Field insertion order is deterministic so rendered configs are
 * golden-test-stable.
 *
 * Tracks the completed `render-validated-xray-client-configs` task (see git history).
 */
class XrayConfigRenderer(
    /**
     * Equivalent of `libXray.TestXray`. The real implementation parses the
     * rendered config through the embedded xray-core to catch errors the
     * static [XrayConfigValidator] cannot. Defaults to a no-op so this module
     * stays free of the native dependency.
     */
    private val tester: XrayConfigTester = XrayConfigTester.NoOp,
) {
    /** Outbound routing tag for the proxied path. */
    private val proxyTag = "proxy"

    /** Outbound routing tag for direct/bypass traffic. */
    private val directTag = "direct"

    /**
     * Outcome of a render + validate + test pass.
     *
     * [Success] carries the rendered [JsonObject]; [Rejected] carries the
     * typed reasons the config was refused (never the secret-bearing config).
     */
    sealed interface Result {
        data class Success(
            val config: JsonObject,
        ) : Result

        data class Rejected(
            val validationErrors: List<XrayConfigValidator.ValidationError>,
            val testError: String? = null,
        ) : Result
    }

    /**
     * Render [profile], validate it for [upstreamTag], run it through the
     * injected [tester], and return [Result.Success] only if every gate
     * passes. An invalid config is NEVER returned as a renderable artifact.
     */
    fun render(
        profile: XrayProfile,
        upstreamTag: String,
    ): Result {
        XrayProfileValidation.error(profile)?.let { return rejectProfile(it) }
        val config = build(profile)
        val errors = XrayConfigValidator.validate(config, XrayConfigValidator.Context(upstreamTag))
        return when {
            profile.inbound.listen != "127.0.0.1" || profile.inbound.port !in 1..MaxPort -> {
                rejectProfile("Provider requires a loopback SOCKS listener with a valid port.")
            }

            errors.isNotEmpty() -> {
                Result.Rejected(validationErrors = errors)
            }

            else -> {
                when (val test = tester.test(config)) {
                    is XrayConfigTester.TestResult.Ok -> {
                        Result.Success(config)
                    }

                    is XrayConfigTester.TestResult.Invalid -> {
                        Result.Rejected(validationErrors = emptyList(), testError = test.message)
                    }
                }
            }
        }
    }

    private fun rejectProfile(message: String): Result.Rejected =
        Result.Rejected(
            listOf(
                XrayConfigValidator.ValidationError(
                    XrayConfigValidator.ErrorCode.PROFILE_INVALID,
                    "profile",
                    message,
                ),
            ),
        )

    /**
     * Validate a raw, already-parsed Xray config (the "import raw JSON before
     * save/start" path). Same gates as [render], but the caller supplies the
     * `JsonObject` instead of a typed profile.
     */
    fun validateImported(
        config: JsonObject,
        upstreamTag: String,
    ): Result {
        val errors = XrayConfigValidator.validate(config, XrayConfigValidator.Context(upstreamTag))
        if (errors.isNotEmpty()) {
            return Result.Rejected(validationErrors = errors)
        }
        return when (val test = tester.test(config)) {
            is XrayConfigTester.TestResult.Ok -> {
                Result.Success(config)
            }

            is XrayConfigTester.TestResult.Invalid -> {
                Result.Rejected(validationErrors = emptyList(), testError = test.message)
            }
        }
    }

    private fun build(profile: XrayProfile): JsonObject =
        buildJsonObject {
            put("dns", renderDns(profile.dns))
            put("inbounds", buildJsonArray { add(renderInbound(profile.inbound)) })
            put(
                "outbounds",
                buildJsonArray {
                    add(renderOutbound(profile.outbound))
                    add(renderDirectOutbound())
                },
            )
            put("routing", renderRouting())
        }

    private fun renderDns(dns: XrayProfile.DnsSettings): JsonObject =
        buildJsonObject {
            put(
                "servers",
                buildJsonArray { dns.servers.forEach { add(JsonPrimitive(it)) } },
            )
            put("queryStrategy", dns.queryStrategy)
        }

    private fun renderInbound(inbound: XrayProfile.LocalInbound): JsonObject =
        buildJsonObject {
            put("tag", "socks-in")
            put("listen", inbound.listen)
            put("port", inbound.port)
            put("protocol", "socks")
            put(
                "settings",
                buildJsonObject {
                    put("auth", "noauth")
                    put("udp", inbound.udpEnabled)
                },
            )
        }

    private fun renderOutbound(outbound: XrayProfile.Outbound): JsonObject =
        buildJsonObject {
            put("tag", proxyTag)
            put("protocol", "vless")
            put(
                "settings",
                buildJsonObject {
                    put(
                        "vnext",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("address", outbound.serverAddress)
                                    put("port", outbound.serverPort)
                                    put(
                                        "users",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("id", outbound.uuid)
                                                    put("encryption", "none")
                                                    put("flow", outbound.flow)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put("streamSettings", renderStreamSettings(outbound))
        }

    private fun renderStreamSettings(outbound: XrayProfile.Outbound): JsonObject =
        buildJsonObject {
            put("network", outbound.network.wire())
            put("security", outbound.security.wire())
            when (outbound.security) {
                XrayProfile.Security.REALITY -> {
                    outbound.reality?.let { put("realitySettings", renderReality(it)) }
                }

                XrayProfile.Security.TLS -> {
                    outbound.tls?.let { put("tlsSettings", renderTls(it)) }
                }
            }
            if (outbound.network == XrayProfile.Network.XHTTP) {
                outbound.xhttp?.let { put("xhttpSettings", renderXhttp(it)) }
            }
        }

    private fun renderReality(reality: XrayProfile.Reality): JsonObject =
        buildJsonObject {
            put("serverName", reality.serverName)
            put("publicKey", reality.publicKey)
            put("shortId", reality.shortId)
            put("fingerprint", reality.fingerprint)
        }

    private fun renderTls(tls: XrayProfile.Tls): JsonObject =
        buildJsonObject {
            put("serverName", tls.serverName)
            put("fingerprint", tls.fingerprint)
            put("allowInsecure", tls.allowInsecure)
        }

    private fun renderXhttp(xhttp: XrayProfile.Xhttp): JsonObject =
        buildJsonObject {
            put("path", xhttp.path)
            put("mode", xhttp.mode)
            if (xhttp.host.isNotEmpty()) {
                put("host", xhttp.host)
            }
        }

    private fun renderDirectOutbound(): JsonObject =
        buildJsonObject {
            put("tag", directTag)
            put("protocol", "freedom")
            put("settings", buildJsonObject {})
        }

    /**
     * Routing with an explicit catch-all default rule. The default rule MUST
     * carry a selector (`network`) — xray-core v26+ rejects a selectorless
     * `"type": "field"` rule with `app/router: this rule has no effective
     * fields` (see `.claude/rules/ansible-molecule.md` Rule 3).
     */
    private fun renderRouting(): JsonObject =
        buildJsonObject {
            put(
                "rules",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "field")
                            put("network", "tcp,udp")
                            put("outboundTag", proxyTag)
                        },
                    )
                },
            )
        }

    private fun XrayProfile.Network.wire(): String =
        when (this) {
            XrayProfile.Network.TCP -> "tcp"
            XrayProfile.Network.XHTTP -> "xhttp"
        }

    private fun XrayProfile.Security.wire(): String =
        when (this) {
            XrayProfile.Security.REALITY -> "reality"
            XrayProfile.Security.TLS -> "tls"
        }

    private companion object {
        const val MaxPort = 65_535
    }
}
