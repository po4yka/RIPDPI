package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import javax.inject.Inject

internal data class CloudflareLocalOriginSpec(
    val rawUrl: String,
    val host: String,
    val port: Int,
)

internal data class CloudflareNamedTunnelSpec(
    val tunnelId: String,
    val credentialsJson: String,
)

internal data class CloudflaredLaunchPlan(
    val arguments: List<String>,
    val environment: Map<String, String>,
    val redactedValues: List<String>,
)

internal class CloudflarePublishConfigParser
    @Inject
    constructor() {
        fun parseLocalOriginSpec(rawUrl: String): CloudflareLocalOriginSpec {
            val uri = URI(rawUrl.trim())
            require(uri.scheme.equals("http", ignoreCase = true)) {
                "Cloudflare publish origin must use http:// loopback"
            }
            val host = uri.host ?: error("Cloudflare publish origin must include a host")
            require(host == "127.0.0.1" || host == "localhost" || host == "::1") {
                "Cloudflare publish origin must bind to loopback only"
            }
            require(uri.port > 0) {
                "Cloudflare publish origin must include an explicit port"
            }
            require(uri.rawPath.isNullOrBlank() || uri.rawPath == "/") {
                "Cloudflare publish origin URL must not include a path"
            }
            require(uri.rawQuery.isNullOrBlank() && uri.rawFragment.isNullOrBlank()) {
                "Cloudflare publish origin URL must not include query or fragment parameters"
            }
            return CloudflareLocalOriginSpec(
                rawUrl = "http://${if (host == "::1") "[::1]" else host}:${uri.port}",
                host = host,
                port = uri.port,
            )
        }

        fun extractNamedTunnelSpec(credentialsJson: String): CloudflareNamedTunnelSpec {
            val parsed =
                Json.parseToJsonElement(credentialsJson).let { element ->
                    require(element is JsonObject) { "Cloudflare named-tunnel credentials must be a JSON object" }
                    element
                }
            val tunnelId =
                listOf("TunnelID", "tunnelID", "tunnelId", "tunnel_id")
                    .firstNotNullOfOrNull { key ->
                        parsed[key]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.trim()
                            ?.takeIf(String::isNotBlank)
                    } ?: error("Cloudflare named-tunnel credentials are missing TunnelID")
            return CloudflareNamedTunnelSpec(
                tunnelId = tunnelId,
                credentialsJson = credentialsJson,
            )
        }
    }

internal class CloudflaredLaunchPlanBuilder
    @Inject
    constructor(
        private val configParser: CloudflarePublishConfigParser,
    ) {
        fun build(
            config: ResolvedRipDpiRelayConfig,
            originSpec: CloudflareLocalOriginSpec,
            metricsAddress: String,
            stateDir: File,
        ): CloudflaredLaunchPlan {
            val credentialsJson = config.cloudflareTunnelCredentialsJson?.trim().orEmpty()
            if (credentialsJson.isNotEmpty()) {
                val namedTunnel = configParser.extractNamedTunnelSpec(credentialsJson)
                val credentialsFile =
                    File(stateDir, "cloudflared-credentials.json").apply {
                        writeText(namedTunnel.credentialsJson)
                    }
                val configFile =
                    File(stateDir, "cloudflared-config.yml").apply {
                        writeText(
                            buildCloudflaredConfigYaml(
                                tunnelId = namedTunnel.tunnelId,
                                credentialsFilePath = credentialsFile.absolutePath,
                                metricsAddress = metricsAddress,
                                hostname = config.server,
                                serviceUrl = originSpec.rawUrl,
                            ),
                        )
                    }
                return CloudflaredLaunchPlan(
                    arguments =
                        listOf(
                            "tunnel",
                            "--no-autoupdate",
                            "--config",
                            configFile.absolutePath,
                            "run",
                        ),
                    environment = emptyMap(),
                    redactedValues = emptyList(),
                )
            }
            val token = config.cloudflareTunnelToken?.trim().orEmpty()
            require(token.isNotEmpty()) {
                "Cloudflare publish mode requires a tunnel token or named-tunnel credentials JSON"
            }
            return CloudflaredLaunchPlan(
                arguments =
                    listOf(
                        "tunnel",
                        "--no-autoupdate",
                        "--metrics",
                        metricsAddress,
                        "run",
                        "--token",
                        token,
                    ),
                environment = emptyMap(),
                redactedValues = listOf(token),
            )
        }
    }

internal fun parseCloudflareLocalOriginSpec(rawUrl: String): CloudflareLocalOriginSpec =
    CloudflarePublishConfigParser().parseLocalOriginSpec(rawUrl)

internal fun extractCloudflareNamedTunnelSpec(credentialsJson: String): CloudflareNamedTunnelSpec =
    CloudflarePublishConfigParser().extractNamedTunnelSpec(credentialsJson)

internal fun buildCloudflaredConfigYaml(
    tunnelId: String,
    credentialsFilePath: String,
    metricsAddress: String,
    hostname: String,
    serviceUrl: String,
): String =
    """
    |tunnel: $tunnelId
    |credentials-file: $credentialsFilePath
    |metrics: $metricsAddress
    |ingress:
    |  - hostname: $hostname
    |    service: $serviceUrl
    |  - service: http_status:404
    """.trimMargin()
