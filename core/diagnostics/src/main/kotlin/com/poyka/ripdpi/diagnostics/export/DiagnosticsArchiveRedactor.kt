package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.CellularNetworkDetails
import com.poyka.ripdpi.diagnostics.ConnectivityAssessment
import com.poyka.ripdpi.diagnostics.ConnectivityEvidence
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.HomeReproAction
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.RuntimeComponentSummary
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
                privateDnsMode = redactPrivateDnsMode(model.privateDnsMode),
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
                cellularDetails = model.cellularDetails?.redactForArchive(),
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
                        selectedProfileName = redactDiagnosticsArchiveText(model.service.selectedProfileName),
                        chainSummary = redactDiagnosticsArchiveText(model.service.chainSummary),
                        routeGroup = redactDiagnosticsArchiveText(model.service.routeGroup),
                        lastNativeErrorHeadline =
                            redactDiagnosticsArchiveText(model.service.lastNativeErrorHeadline),
                        lastAutolearnHost = archiveTargetProjection(model.service.lastAutolearnHost) ?: "unknown",
                        lastAutolearnGroup = redactDiagnosticsArchiveText(model.service.lastAutolearnGroup),
                        lastAutolearnAction = redactDiagnosticsArchiveText(model.service.lastAutolearnAction),
                        proxy =
                            model.service.proxy
                                ?.copy(
                                    listenerAddress =
                                        model.service.proxy.listenerAddress
                                            ?.let { "redacted" },
                                    upstreamAddress =
                                        model.service.proxy.upstreamAddress
                                            ?.let { "redacted" },
                                )?.redactForArchive(),
                        tunnel =
                            model.service.tunnel
                                ?.copy(
                                    listenerAddress =
                                        model.service.tunnel.listenerAddress
                                            ?.let { "redacted" },
                                    upstreamAddress =
                                        model.service.tunnel.upstreamAddress
                                            ?.let { "redacted" },
                                )?.redactForArchive(),
                        relay =
                            model.service.relay
                                ?.copy(
                                    listenerAddress =
                                        model.service.relay.listenerAddress
                                            ?.let { "redacted" },
                                    upstreamAddress =
                                        model.service.relay.upstreamAddress
                                            ?.let { "redacted" },
                                )?.redactForArchive(),
                        warp =
                            model.service.warp
                                ?.copy(
                                    listenerAddress =
                                        model.service.warp.listenerAddress
                                            ?.let { "redacted" },
                                    upstreamAddress =
                                        model.service.warp.upstreamAddress
                                            ?.let { "redacted" },
                                )?.redactForArchive(),
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
                policySignature = archiveStableCorrelatorProjection(entity.policySignature),
                fingerprintHash = archiveFingerprintProjection(entity.fingerprintHash),
            )

        fun redact(entity: ProbeResultEntity): ProbeResultEntity =
            entity.copy(
                target = archiveTargetProjection(entity.target) ?: "redacted",
                detailJson = redactStructuredText(entity.detailJson),
            )

        fun redact(report: EngineScanReportWire?): EngineScanReportWire? =
            report?.let { value ->
                val projected =
                    projectStructuredArchiveJson(json.encodeToJsonElement(EngineScanReportWire.serializer(), value))
                json.decodeFromJsonElement(EngineScanReportWire.serializer(), projected)
            }

        fun redact(assessment: ConnectivityAssessment?): ConnectivityAssessment? =
            redactConnectivityAssessment(assessment)

        fun redact(action: HomeReproAction?): HomeReproAction? =
            action?.copy(
                label = redactDiagnosticsArchiveText(action.label),
                summary = redactDiagnosticsArchiveText(action.summary),
            )

        private fun redactStructuredText(value: String): String =
            runCatching {
                json.encodeToString(
                    JsonElement.serializer(),
                    projectStructuredArchiveJson(json.parseToJsonElement(value)),
                )
            }.getOrElse { redactDiagnosticsArchiveText(value) }

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

internal fun redactConnectivityAssessment(assessment: ConnectivityAssessment?): ConnectivityAssessment? =
    assessment?.copy(
        assessmentSummary = redactDiagnosticsArchiveText(assessment.assessmentSummary),
        rawPathEvidence = assessment.rawPathEvidence.redactForArchive(),
        inPathEvidence = assessment.inPathEvidence.redactForArchive(),
        affectedTargets = archiveTargetListProjection(assessment.affectedTargets),
        resolverAssessment =
            assessment.resolverAssessment.copy(
                mismatchTargets = archiveTargetListProjection(assessment.resolverAssessment.mismatchTargets),
                summary = redactDiagnosticsArchiveText(assessment.resolverAssessment.summary),
            ),
        serviceRuntimeAssessment =
            assessment.serviceRuntimeAssessment.copy(
                lastNativeErrorHeadline =
                    redactDiagnosticsArchiveText(assessment.serviceRuntimeAssessment.lastNativeErrorHeadline),
                summary = redactDiagnosticsArchiveText(assessment.serviceRuntimeAssessment.summary),
            ),
        recommendedNextAction = redactDiagnosticsArchiveText(assessment.recommendedNextAction),
    )

private fun ConnectivityEvidence.redactForArchive(): ConnectivityEvidence =
    copy(
        controls = archiveTargetListProjection(controls),
        affectedTargets = archiveTargetListProjection(affectedTargets),
    )

private fun RuntimeComponentSummary.redactForArchive(): RuntimeComponentSummary =
    copy(
        lastError = redactDiagnosticsArchiveText(lastError),
        lastFailureClass = redactDiagnosticsArchiveText(lastFailureClass),
    )

internal fun archiveTargetProjection(value: String?): String? = value?.let { "redacted" }

internal fun archiveTargetListProjection(values: List<String>): List<String> =
    if (values.isEmpty()) emptyList() else listOf("redacted(${values.size})")

internal fun archiveStableCorrelatorProjection(value: String?): String? = value?.let { "redacted" }

internal fun TelemetrySampleEntity.redactForArchive(): TelemetrySampleEntity =
    copy(
        publicIp = archiveTargetProjection(publicIp),
        telemetryNetworkFingerprintHash = archiveFingerprintProjection(telemetryNetworkFingerprintHash),
        proxyTelemetryMessage = proxyTelemetryMessage?.let(::redactDiagnosticsArchiveText),
        relayTelemetryMessage = relayTelemetryMessage?.let(::redactDiagnosticsArchiveText),
        warpTelemetryMessage = warpTelemetryMessage?.let(::redactDiagnosticsArchiveText),
        tunnelTelemetryMessage = tunnelTelemetryMessage?.let(::redactDiagnosticsArchiveText),
        lastFailureClass = lastFailureClass?.let(::redactDiagnosticsArchiveText),
        lastFallbackAction = lastFallbackAction?.let(::redactDiagnosticsArchiveText),
        resolverEndpoint = archiveTargetProjection(resolverEndpoint),
        resolverFallbackReason = resolverFallbackReason?.let(::redactDiagnosticsArchiveText),
        networkHandoverClass = networkHandoverClass?.let(::redactDiagnosticsArchiveText),
        networkHandoverState = networkHandoverState?.let(::redactDiagnosticsArchiveText),
    )

private fun projectStructuredArchiveJson(
    element: JsonElement,
    fieldName: String? = null,
): JsonElement =
    when {
        element is JsonNull -> {
            element
        }

        fieldName.isArchiveSensitiveScalarField() -> {
            JsonPrimitive("redacted")
        }

        fieldName.isArchiveSensitiveListField() && element is JsonArray -> {
            if (element.isEmpty()) {
                JsonArray(
                    emptyList(),
                )
            } else {
                JsonArray(listOf(JsonPrimitive("redacted(${element.size})")))
            }
        }

        fieldName in ArchiveStableCorrelatorFields -> {
            if (element is JsonPrimitive) JsonPrimitive("redacted") else JsonNull
        }

        element is JsonObject -> {
            val declaredField = (element["key"] as? JsonPrimitive)?.content
            JsonObject(
                element.mapValues { (key, value) ->
                    if (key == "value" && declaredField.isArchiveSensitiveField()) {
                        JsonPrimitive("redacted")
                    } else {
                        projectStructuredArchiveJson(value, key)
                    }
                },
            )
        }

        element is JsonArray -> {
            JsonArray(element.map { projectStructuredArchiveJson(it, fieldName) })
        }

        element is JsonPrimitive && element.isString -> {
            JsonPrimitive(redactDiagnosticsArchiveText(element.content))
        }

        else -> {
            element
        }
    }

private fun String?.isArchiveSensitiveField(): Boolean =
    isArchiveSensitiveScalarField() || isArchiveSensitiveListField() || this in ArchiveStableCorrelatorFields

private fun String?.isArchiveSensitiveScalarField(): Boolean {
    val normalized = this?.lowercase() ?: return false
    return this in ArchiveSensitiveScalarFields ||
        ArchiveSensitiveScalarSuffixes.any(normalized::endsWith)
}

private fun String?.isArchiveSensitiveListField(): Boolean {
    val normalized = this?.lowercase() ?: return false
    return this in ArchiveSensitiveListFields ||
        ArchiveSensitiveListSuffixes.any(normalized::endsWith)
}

private val ArchiveSensitiveScalarFields =
    setOf(
        "address",
        "addr",
        "authority",
        "bssid",
        "dhcpServer",
        "domain",
        "endpoint",
        "gateway",
        "host",
        "hostname",
        "ipAddress",
        "listenerAddress",
        "operatorOrSsid",
        "proxyConfigJson",
        "proxyEndpoint",
        "recommendedProxyConfigJson",
        "resolverEndpoint",
        "server",
        "sni",
        "ssid",
        "subnetMask",
        "target",
        "upstreamAddress",
        "url",
        "uri",
        "selectedDnscryptPublicKey",
    )

private val ArchiveSensitiveListFields =
    setOf(
        "affectedTargets",
        "controls",
        "dnsServers",
        "domainHosts",
        "localAddresses",
        "mismatchTargets",
        "quicHosts",
        "targets",
    )

private val ArchiveSensitiveScalarSuffixes =
    setOf(
        "address",
        "authority",
        "domain",
        "endpoint",
        "host",
        "ip",
        "path",
        "publickey",
        "server",
        "servername",
        "sni",
        "url",
        "uri",
    )

private val ArchiveSensitiveListSuffixes =
    setOf("addresses", "domains", "endpoints", "hosts", "ips", "servers", "targets", "urls", "uris")

private val ArchiveStableCorrelatorFields =
    setOf(
        "commandLineArgsHash",
        "effectiveStrategySignature",
        "fingerprintHash",
        "networkScope",
        "policySignature",
        "strategySignature",
        "telemetryNetworkFingerprintHash",
    )

private fun CellularNetworkDetails.redactForArchive(): CellularNetworkDetails =
    copy(
        carrierName = carrierName.redactCellularIdentity(),
        simOperatorName = simOperatorName.redactCellularIdentity(),
        networkOperatorName = networkOperatorName.redactCellularIdentity(),
        operatorCode = operatorCode.redactCellularIdentity(),
        simOperatorCode = simOperatorCode.redactCellularIdentity(),
        carrierId = null,
        simCarrierId = null,
    )

private fun String.redactCellularIdentity(): String = if (equals("unknown", ignoreCase = true)) this else "redacted"

internal fun redactPrivateDnsMode(value: String): String =
    when (val normalized = value.trim().lowercase()) {
        "system", "off", "none", "opportunistic", "strict", "unknown", "unavailable" -> normalized
        "" -> "unknown"
        else -> "strict"
    }

private const val UndecodableArchivePayloadMarker = "{\"redactionStatus\":\"payload_decode_failed\"}"

internal fun redactDiagnosticsFreeText(value: String): String =
    value
        .replaceWhenContainsAny(PemBlockRegex, "<pem-redacted>", "-----BEGIN")
        .replaceWhenContainsAny(AuthorizationHeaderRegex, "$1 redacted", "authorization:")
        .replaceWhenContainsAny(CredentialUrlRegex, "$1//redacted@", "://")
        .replaceWhenContainsAny(SensitiveQueryRegex, "$1=redacted", "=")
        .replaceWhenContainsAny(BssidRegex, "redacted-bssid", ":")
        .replaceWhenContainsAny(QuotedNetworkNameRegex, "$1=\"redacted\"", "ssid=", "operator=", "carrier=")
        .replaceWhenContainsAny(UnquotedNetworkNameRegex, "$1=redacted", "ssid", "operator", "carrier")
        .replace(Ipv4Regex, "<ip-redacted>")
        .replace(Ipv6Regex, "<ip-redacted>")

internal fun redactDiagnosticsLogcat(value: String): String =
    redactDiagnosticsFreeText(value)
        .replaceWhenContainsAny(UrlRegex, "<url-redacted>", "http://", "https://")
        .replace(WindowsPathRegex, "<path-redacted>")
        .replace(UnixPathRegex, "<path-redacted>")
        .replace(DnsNameRegex, "<host-redacted>")
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
private val PemBlockRegex =
    Regex("-----BEGIN [^-]+-----.*?-----END [^-]+-----", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val CredentialUrlRegex = Regex("([a-z][a-z0-9+.-]*:)//[^\\s/@:]+:[^\\s/@]+@", RegexOption.IGNORE_CASE)
private val SensitiveQueryRegex = Regex("(?i)\\b(token|auth|password|secret|key)=([^\\s&]+)")
private val BssidRegex = Regex("\\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\\b")
private val QuotedNetworkNameRegex = Regex("(?i)\\b(ssid|operator|carrier)=([\"']).*?\\2")
private val UnquotedNetworkNameRegex = Regex("(?im)\\b(ssid|operator|carrier)\\s*=\\s*[^,;\\r\\n]+")
private val UrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private val Ipv4Regex =
    Regex("(?<![A-Za-z0-9])(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?![A-Za-z0-9])")
private val Ipv6Regex = Regex("(?i)(?<![A-Za-z0-9])(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}(?![A-Za-z0-9])")
private val DnsNameRegex =
    Regex("(?i)\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}\\b")
private val UnixPathRegex = Regex("(?<![A-Za-z0-9])/(?:[^\\s,;:\"'<>]+/)*[^\\s,;:\"'<>]+")
private val WindowsPathRegex = Regex("(?i)\\b[A-Z]:\\\\(?:[^\\s,;:\"'<>]+\\\\)*[^\\s,;:\"'<>]+")
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
