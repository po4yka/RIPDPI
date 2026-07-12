package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.inject.Inject

private const val CloudflareOriginStartupSchemaVersion = 1
private const val CloudflareOriginStartupMaxBytes = 16 * 1024
private const val CloudflaredHomeDirectoryName = ".cloudflared"
private const val CloudflaredDefaultConfigName = "config.yml"
private const val CloudflaredCredentialsName = "cloudflared-credentials.json"
private const val CloudflaredTokenName = "tunnel-token"
private const val CloudflaredHomeEnv = "HOME"
private const val CloudflaredMetricsEnv = "TUNNEL_METRICS"
private const val CloudflaredTokenFileEnv = "TUNNEL_TOKEN_FILE"
private val CloudflareOriginStartupMagic = "RIPDPI-CF-ORIGIN".toByteArray(StandardCharsets.US_ASCII)
private val CloudflareTunnelIdPattern = Regex("^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")
private val CloudflareHostnameLabelPattern = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

internal data class CloudflareLocalOriginSpec(
    val rawUrl: String,
    val host: String,
    val port: Int,
) {
    val listenerAddress: String
        get() = "${if (host == "::1") "[$host]" else host}:$port"
}

internal data class CloudflareNamedTunnelSpec(
    val tunnelId: String,
    val credentialsJson: String,
    val redactedValues: List<String>,
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
            require(CloudflareTunnelIdPattern.matches(tunnelId)) {
                "Cloudflare named-tunnel TunnelID must be a canonical UUID"
            }
            return CloudflareNamedTunnelSpec(
                tunnelId = tunnelId,
                credentialsJson = credentialsJson,
                redactedValues =
                    buildList {
                        add(credentialsJson)
                        parsed.values.forEach { value ->
                            (value as? JsonPrimitive)
                                ?.contentOrNull
                                ?.trim()
                                ?.takeIf(String::isNotBlank)
                                ?.let(::add)
                        }
                    },
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
            val cloudflaredHome = File(stateDir, CloudflaredHomeDirectoryName).apply(::ensureCloudflarePrivateDirectory)
            val commonEnvironment = mapOf(CloudflaredHomeEnv to stateDir.absolutePath)
            val staticArguments = listOf("tunnel", "--no-autoupdate", "run")
            val credentialsJson = config.cloudflareTunnelCredentialsJson?.trim().orEmpty()
            if (credentialsJson.isNotEmpty()) {
                val namedTunnel = configParser.extractNamedTunnelSpec(credentialsJson)
                val credentialsFile =
                    File(cloudflaredHome, CloudflaredCredentialsName).apply {
                        writeCloudflarePrivateFile(namedTunnel.credentialsJson)
                    }
                val configFile =
                    File(cloudflaredHome, CloudflaredDefaultConfigName).apply {
                        writeCloudflarePrivateFile(
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
                    arguments = staticArguments,
                    environment = commonEnvironment,
                    redactedValues =
                        cloudflarePublishRedactedValues(
                            config = config,
                            stateDir = stateDir,
                            extraValues =
                                namedTunnel.redactedValues +
                                    listOf(credentialsFile.absolutePath, configFile.absolutePath),
                        ),
                )
            }
            val token = config.cloudflareTunnelToken?.trim().orEmpty()
            require(token.isNotEmpty()) {
                "Cloudflare publish mode requires a tunnel token or named-tunnel credentials JSON"
            }
            val tokenFile = File(cloudflaredHome, CloudflaredTokenName).apply { writeCloudflarePrivateFile(token) }
            return CloudflaredLaunchPlan(
                arguments = staticArguments,
                environment =
                    commonEnvironment +
                        mapOf(
                            CloudflaredMetricsEnv to metricsAddress,
                            CloudflaredTokenFileEnv to tokenFile.absolutePath,
                        ),
                redactedValues =
                    cloudflarePublishRedactedValues(
                        config = config,
                        stateDir = stateDir,
                        extraValues = listOf(token, tokenFile.absolutePath),
                    ),
            )
        }
    }

internal fun encodeCloudflareOriginStartupConfig(
    originSpec: CloudflareLocalOriginSpec,
    xhttpPath: String,
    vlessUuid: String?,
): ByteArray {
    val uuid = vlessUuid?.trim().orEmpty()
    require(uuid.isNotEmpty()) { "Cloudflare origin requires a VLESS UUID" }
    val fields = listOf(originSpec.listenerAddress, xhttpPath.ifBlank { "/" }, uuid)
    val encodedFields =
        fields.map { field ->
            field.toByteArray(StandardCharsets.UTF_8).also { bytes ->
                require(bytes.size <= UShort.MAX_VALUE.toInt()) {
                    "Cloudflare origin startup field is too large"
                }
            }
        }
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { frame ->
        frame.write(CloudflareOriginStartupMagic)
        frame.writeByte(CloudflareOriginStartupSchemaVersion)
        encodedFields.forEach { bytes ->
            frame.writeShort(bytes.size)
            frame.write(bytes)
        }
    }
    return output.toByteArray().also { payload ->
        require(payload.size <= CloudflareOriginStartupMaxBytes) {
            "Cloudflare origin startup config is too large"
        }
    }
}

internal fun cloudflarePublishRedactedValues(
    config: ResolvedRipDpiRelayConfig,
    stateDir: File,
    extraValues: List<String> = emptyList(),
): List<String> =
    buildList {
        addAll(extraValues)
        config.cloudflareTunnelToken?.let(::add)
        config.cloudflareTunnelCredentialsJson?.let(::add)
        config.vlessUuid?.let(::add)
        config.xhttpPath.takeIf { it.length > 1 }?.let(::add)
        config.cloudflarePublishLocalOriginUrl.takeIf { it.isNotBlank() }?.let(::add)
        add(stateDir.absolutePath)
    }.map(String::trim)
        .filter { it.length >= MinimumRedactedValueLength }
        .distinct()

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
): String {
    require(CloudflareTunnelIdPattern.matches(tunnelId)) {
        "Cloudflare named-tunnel TunnelID must be a canonical UUID"
    }
    require(isCloudflareHostname(hostname)) {
        "Cloudflare Tunnel hostname is invalid"
    }
    return """
        |tunnel: ${yamlDoubleQuoted(tunnelId)}
        |credentials-file: ${yamlDoubleQuoted(credentialsFilePath)}
        |metrics: ${yamlDoubleQuoted(metricsAddress)}
        |ingress:
        |  - hostname: ${yamlDoubleQuoted(hostname)}
        |    service: ${yamlDoubleQuoted(serviceUrl)}
        |  - service: http_status:404
        """.trimMargin()
}

private const val MinimumRedactedValueLength = 4
private const val MaximumHostnameLength = 253

private fun isCloudflareHostname(hostname: String): Boolean =
    hostname.length in 1..MaximumHostnameLength &&
        !hostname.contains('\n') &&
        !hostname.contains('\r') &&
        hostname.trimEnd('.').split('.').all(CloudflareHostnameLabelPattern::matches)

private fun yamlDoubleQuoted(value: String): String =
    buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

private fun ensureCloudflarePrivateDirectory(directory: File) {
    check((directory.mkdirs() || directory.isDirectory) && directory.isDirectory) {
        "Unable to create Cloudflare private state directory"
    }
    check(
        directory.setReadable(false, false) &&
            directory.setWritable(false, false) &&
            directory.setExecutable(false, false) &&
            directory.setReadable(true, true) &&
            directory.setWritable(true, true) &&
            directory.setExecutable(true, true),
    ) {
        "Unable to restrict Cloudflare private state directory permissions"
    }
}

private fun File.writeCloudflarePrivateFile(contents: String) {
    writeText(contents)
    check(
        setReadable(false, false) &&
            setWritable(false, false) &&
            setExecutable(false, false) &&
            setReadable(true, true) &&
            setWritable(true, true),
    ) {
        runCatching { delete() }
        "Unable to restrict Cloudflare private state file permissions"
    }
}
