package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named

class DiagnosticsArchiveRedactor
    @Inject
    constructor(
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        fun redact(model: NetworkSnapshotModel): NetworkSnapshotModel =
            model.copy(
                publicIp = model.publicIp?.let { "redacted" },
                publicAsn = model.publicAsn?.let { "redacted" },
                dnsServers =
                    if (model.dnsServers.isNotEmpty()) {
                        listOf("redacted(${model.dnsServers.size})")
                    } else {
                        model.dnsServers
                    },
                localAddresses =
                    if (model.localAddresses.isNotEmpty()) {
                        listOf("redacted(${model.localAddresses.size})")
                    } else {
                        model.localAddresses
                    },
                wifiDetails =
                    model.wifiDetails?.let { wifi ->
                        wifi.copy(
                            ssid = if (wifi.ssid != "unknown") "redacted" else wifi.ssid,
                            bssid = if (wifi.bssid != "unknown") "redacted" else wifi.bssid,
                            gateway = wifi.gateway?.let { "redacted" },
                            dhcpServer = wifi.dhcpServer?.let { "redacted" },
                            ipAddress = wifi.ipAddress?.let { "redacted" },
                            subnetMask = wifi.subnetMask?.let { "redacted" },
                        )
                    },
            )

        fun redact(model: DiagnosticContextModel): DiagnosticContextModel =
            model.copy(
                service =
                    model.service.copy(
                        proxyEndpoint =
                            if (model.service.proxyEndpoint != "unknown") {
                                "redacted"
                            } else {
                                model.service.proxyEndpoint
                            },
                        proxy =
                            model.service.proxy?.copy(
                                listenerAddress =
                                    model.service.proxy.listenerAddress
                                        ?.let { "redacted" },
                                upstreamAddress =
                                    model.service.proxy.upstreamAddress
                                        ?.let { "redacted" },
                            ),
                        tunnel =
                            model.service.tunnel?.copy(
                                listenerAddress =
                                    model.service.tunnel.listenerAddress
                                        ?.let { "redacted" },
                                upstreamAddress =
                                    model.service.tunnel.upstreamAddress
                                        ?.let { "redacted" },
                            ),
                        relay =
                            model.service.relay?.copy(
                                listenerAddress =
                                    model.service.relay.listenerAddress
                                        ?.let { "redacted" },
                                upstreamAddress =
                                    model.service.relay.upstreamAddress
                                        ?.let { "redacted" },
                            ),
                        warp =
                            model.service.warp?.copy(
                                listenerAddress =
                                    model.service.warp.listenerAddress
                                        ?.let { "redacted" },
                                upstreamAddress =
                                    model.service.warp.upstreamAddress
                                        ?.let { "redacted" },
                            ),
                    ),
            )

        fun redact(entity: NetworkSnapshotEntity): NetworkSnapshotEntity {
            val model =
                decodeNetworkSnapshot(entity)
                    ?: return entity.copy(payloadJson = UndecodableArchivePayloadMarker)
            return entity.copy(
                payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), redact(model)),
            )
        }

        fun redact(entity: DiagnosticContextEntity): DiagnosticContextEntity {
            val model =
                decodeDiagnosticContext(entity)
                    ?: return entity.copy(payloadJson = UndecodableArchivePayloadMarker)
            return entity.copy(
                payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), redact(model)),
            )
        }

        fun redact(entity: NativeSessionEventEntity): NativeSessionEventEntity =
            entity.copy(
                message = redactDiagnosticsFreeText(entity.message),
                runtimeId = entity.runtimeId?.let(::redactDiagnosticsFreeText),
                policySignature = entity.policySignature?.let(::redactDiagnosticsFreeText),
            )

        fun redact(entity: ProbeResultEntity): ProbeResultEntity =
            entity.copy(
                target = redactDiagnosticsArchiveText(entity.target),
                detailJson = redactDiagnosticsArchiveText(entity.detailJson),
            )

        fun decodeNetworkSnapshot(entity: NetworkSnapshotEntity?): NetworkSnapshotModel? =
            entity?.payloadJson?.let { payloadJson ->
                runCatching {
                    json.decodeFromString(NetworkSnapshotModel.serializer(), payloadJson)
                }.getOrNull()
            }

        fun decodeDiagnosticContext(entity: DiagnosticContextEntity?): DiagnosticContextModel? =
            entity?.payloadJson?.let { payloadJson ->
                runCatching {
                    json.decodeFromString(DiagnosticContextModel.serializer(), payloadJson)
                }.getOrNull()
            }
    }

private const val UndecodableArchivePayloadMarker = "{\"redactionStatus\":\"payload_decode_failed\"}"

internal fun redactDiagnosticsFreeText(value: String): String =
    value
        .replaceWhenContainsAny(AuthorizationHeaderRegex, "$1 redacted", "authorization:")
        .replaceWhenContainsAny(CredentialUrlRegex, "$1//redacted@", "://")
        .replaceWhenContainsAny(SensitiveQueryRegex, "$1=redacted", "=")
        .replaceWhenContainsAny(BssidRegex, "redacted-bssid", ":")
        .replaceWhenContainsAny(QuotedNetworkNameRegex, "$1=\"redacted\"", "ssid=", "operator=", "carrier=")

internal fun redactDiagnosticsLogcat(value: String): String =
    redactDiagnosticsFreeText(value)
        .replaceWhenContainsAny(UrlRegex, "<url-redacted>", "http://", "https://")
        .replaceWhenContainsAny(
            EndpointFieldRegex,
            "$1=<redacted>",
            "host=",
            "hostname=",
            "target=",
            "server=",
            "resolverEndpoint=",
            "endpoint=",
            "addr=",
            "address=",
        )

internal fun redactDiagnosticsArchiveText(value: String): String =
    redactDiagnosticsLogcat(value)
        .replaceWhenContainsAny(
            JsonTargetArrayRegex,
            "$1[\"<redacted>\"]",
            "\"affectedTargets\"",
            "\"domainHosts\"",
            "\"quicHosts\"",
        ).replaceWhenContainsAny(
            JsonEndpointFieldRegex,
            "$1<redacted>\"",
            "\"address\"",
            "\"addr\"",
            "\"bssid\"",
            "\"dhcpServer\"",
            "\"endpoint\"",
            "\"gateway\"",
            "\"host\"",
            "\"hostname\"",
            "\"ipAddress\"",
            "\"listenerAddress\"",
            "\"proxyEndpoint\"",
            "\"resolverEndpoint\"",
            "\"server\"",
            "\"ssid\"",
            "\"subnetMask\"",
            "\"target\"",
            "\"upstreamAddress\"",
        )

private fun String.replaceWhenContainsAny(
    regex: Regex,
    replacement: String,
    vararg needles: String,
): String =
    if (needles.any { needle -> contains(needle, ignoreCase = true) }) {
        replace(regex, replacement)
    } else {
        this
    }

private val AuthorizationHeaderRegex = Regex("(?i)\\b(Proxy-Authorization:|Authorization:)\\s*(?:Basic|Bearer)\\s+\\S+")
private val CredentialUrlRegex = Regex("([a-z][a-z0-9+.-]*:)//[^\\s/@:]+:[^\\s/@]+@", RegexOption.IGNORE_CASE)
private val SensitiveQueryRegex = Regex("(?i)\\b(token|auth|password|secret|key)=([^\\s&]+)")
private val BssidRegex = Regex("\\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\\b")
private val QuotedNetworkNameRegex = Regex("(?i)\\b(ssid|operator|carrier)=([\"']).*?\\2")
private val UrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private val EndpointFieldRegex =
    Regex(
        "(?i)\\b(host|hostname|target|server|resolverEndpoint|endpoint|addr|address)=" +
            "([^\\s,;\"\\\\\\]}]+)",
    )
private val JsonEndpointFieldRegex =
    Regex(
        JsonEndpointFieldKeyPattern +
            JsonEndpointFieldValuePattern,
        RegexOption.IGNORE_CASE,
    )
private val JsonTargetArrayRegex =
    Regex(
        "(\"(?:affectedTargets|domainHosts|quicHosts)\"\\s*:\\s*)\\[[^]]*]",
    )
private const val JsonEndpointFieldKeyPattern =
    "(\"(?:address|addr|bssid|dhcpServer|endpoint|gateway|host|hostname|ipAddress|" +
        "listenerAddress|proxyEndpoint|resolverEndpoint|server|ssid|subnetMask|target|" +
        "upstreamAddress)\"\\s*:\\s*\")"
private const val JsonEndpointFieldValuePattern =
    "(?!redacted|<redacted>|unavailable|unknown|none|null)(?:[^\"\\\\]|\\\\.)*\""
