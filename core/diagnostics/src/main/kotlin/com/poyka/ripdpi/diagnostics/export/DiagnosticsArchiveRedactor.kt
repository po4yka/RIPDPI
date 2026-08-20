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
import com.poyka.ripdpi.diagnostics.sanitizeVpnRouteEvidenceForArchive
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
                pathValidation = model.pathValidation?.sanitizeVpnRouteEvidenceForArchive(),
            )

        fun redact(model: DiagnosticContextModel): DiagnosticContextModel =
            model.copy(
                device =
                    model.device.copy(
                        manufacturer = "redacted",
                        model = "redacted",
                        locale = "redacted",
                        timezone = "redacted",
                    ),
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
                message = redactDiagnosticsArchiveText(entity.message),
                runtimeId = entity.runtimeId?.let(::redactDiagnosticsArchiveText),
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
                json
                    .decodeFromJsonElement(EngineScanReportWire.serializer(), projected)
                    .projectStrategyEvidenceForArchive()
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

        fieldName.isArchiveSensitiveScalarField() && element is JsonPrimitive -> {
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
    return this !in ArchiveNonSensitiveScalarFields &&
        (this in ArchiveSensitiveScalarFields || ArchiveSensitiveScalarSuffixes.any(normalized::endsWith))
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
        "authToken",
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
        "password",
        "proxyConfigJson",
        "proxyEndpoint",
        "recommendedProxyConfigJson",
        "resolverEndpoint",
        "server",
        "secret",
        "sni",
        "ssid",
        "subnetMask",
        "target",
        "token",
        "upstreamAddress",
        "url",
        "uri",
        "selectedDnscryptPublicKey",
    )

private val ArchiveNonSensitiveScalarFields =
    setOf(
        "activePathAuthority",
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
        .replaceWhenContainsAny(PemTailRegex, "$1<pem-redacted>", "-----END")
        .replaceWhenContainsAny(AuthorizationHeaderRegex, "$1 redacted", "authorization:")
        .replaceWhenContainsAny(CredentialUrlRegex, "$1//redacted@", "://")
        .replaceWhenContainsAny(SensitiveQueryRegex, "$1=redacted", "=")
        .replaceWhenContainsAny(BssidRegex, "redacted-bssid", ":")
        .replaceWhenContainsAny(QuotedNetworkNameRegex, "$1=\"redacted\"", "ssid=", "operator=", "carrier=")
        .replaceWhenContainsAny(UnquotedNetworkNameRegex, "$1=redacted", "ssid", "operator", "carrier")
        .replace(RustIpv4AddrRegex, "<ip-redacted>")
        .replace(Ipv4Regex, "<ip-redacted>")
        .replace(Ipv6Regex, "<ip-redacted>")

internal fun redactDiagnosticsLogcat(value: String): String =
    redactDiagnosticsFreeText(value)
        .replaceWhenContainsAny(UrlRegex, "<url-redacted>", "http://", "https://")
        .let(::redactDiagnosticsPaths)
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
    Regex(
        "-----BEGIN [^-\\r\\n]+-----.*?(?:-----END [^-\\r\\n]+-----|\\z)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
private const val PemFullBase64LinePattern = "[A-Za-z0-9+/]{8,}={0,2}"
private const val PemShortBase64FragmentPattern = "[A-Za-z0-9+/]{1,7}={0,2}"
private const val SensitivePemEndLabelPattern =
    "[A-Z0-9 ]*(?:CERTIFICATE(?: REQUEST)?|PRIVATE KEY|PUBLIC KEY)[A-Z0-9 ]*"
private const val AndroidLogcatLinePrefixPattern =
    "(?:\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+" +
        "(?:(?:\\d+\\s+){2}[VDIWEF]\\s+[^:\\r\\n]+|[VDIWEF]/[^:\\r\\n]+):\\s*|" +
        "[VDIWEF]/[^()\\r\\n]+\\(\\s*\\d+\\):\\s*)"

// Fail closed: base64-alphabet lines immediately before a sensitive END marker are secret material,
// even when a fragment could also be read as an ordinary word.
private val PemTailRegex =
    Regex(
        "(?m)(^|\\r?\\n)(?:(?:" +
            "(?:$AndroidLogcatLinePrefixPattern)?$PemShortBase64FragmentPattern[ \\t]*\\r?\\n" +
            "(?:(?:$AndroidLogcatLinePrefixPattern)?$PemFullBase64LinePattern[ \\t]*\\r?\\n)*" +
            "(?:(?:$AndroidLogcatLinePrefixPattern)?$PemShortBase64FragmentPattern[ \\t]*\\r?\\n)?|" +
            "(?:(?:$AndroidLogcatLinePrefixPattern)?$PemFullBase64LinePattern[ \\t]*\\r?\\n)+" +
            "(?:(?:$AndroidLogcatLinePrefixPattern)?$PemShortBase64FragmentPattern[ \\t]*\\r?\\n)?" +
            ")" +
            "(?:$AndroidLogcatLinePrefixPattern)?-----END $SensitivePemEndLabelPattern-----)",
        RegexOption.IGNORE_CASE,
    )
private val CredentialUrlRegex = Regex("([a-z][a-z0-9+.-]*:)//[^\\s/@:]+:[^\\s/@]+@", RegexOption.IGNORE_CASE)
private val SensitiveQueryRegex = Regex("(?i)\\b(token|auth|password|secret|key)=([^\\s&]+)")
private val BssidRegex = Regex("\\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\\b")
private val QuotedNetworkNameRegex = Regex("(?i)\\b(ssid|operator|carrier)=([\"']).*?\\2")
private val UnquotedNetworkNameRegex = Regex("(?im)\\b(ssid|operator|carrier)\\s*=\\s*[^,;\\r\\n]+")
private val UrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private const val Ipv4OctetPattern = "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)"
private val RustIpv4AddrRegex =
    Regex("(?i)\\bIpv4Addr\\(\\[\\s*$Ipv4OctetPattern(?:\\s*,\\s*$Ipv4OctetPattern){3}\\s*\\]\\)")
private val Ipv4Regex =
    Regex("(?<![A-Za-z0-9])$Ipv4OctetPattern(?:\\.$Ipv4OctetPattern){3}(?![A-Za-z0-9])")
private val Ipv6Regex =
    Regex(
        "(?i)(?<![A-Za-z0-9_:])(?:" +
            "(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}|" +
            "(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?::" +
            "(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?" +
            ")(?![A-Za-z0-9_:])",
    )
private val DnsNameRegex =
    Regex(
        "(?iu)(?<![\\p{L}\\p{N}_-])" +
            "(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?[.\\u3002\\uFF0E\\uFF61])+" +
            "(?:xn--[a-z0-9-]{2,59}|[\\p{L}]{2,63})(?![\\p{L}\\p{N}_-])",
    )
private val LogicalLineContentRegex = Regex("[^\\r\\n]+")
private val KnownAppLogcatPrefixRegex =
    Regex(
        "^(?:\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+" +
            "(?:(?:\\d+\\s+){2}[VDIWEF]\\s+(?:ripdpi|ripdpi-native)|" +
            "[VDIWEF]/(?:ripdpi|ripdpi-native)):\\s*|" +
            "[VDIWEF]/(?:ripdpi|ripdpi-native)\\(\\s*\\d+\\):\\s*)",
        RegexOption.IGNORE_CASE,
    )
private val SemanticPathKeys = setOf("file", "path", "filepath")
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

private fun redactDiagnosticsPaths(value: String): String =
    LogicalLineContentRegex.replace(value) { match -> redactDiagnosticsPathLine(match.value) }

// Ambiguous absolute paths have no reliable boundary once spaces, punctuation, or escape layers are allowed.
// Fail closed per logical line, retaining only a strict known-app Android logcat prefix when present.
private fun redactDiagnosticsPathLine(line: String): String {
    val semanticRedaction = redactSemanticQuotedPathValues(line)
    if (!semanticRedaction.malformed && !containsResidualAbsolutePath(semanticRedaction.value)) {
        return semanticRedaction.value
    }
    val logcatPrefix = KnownAppLogcatPrefixRegex.find(line)?.value.orEmpty()
    return logcatPrefix + "<path-redacted>"
}

private data class SemanticPathRedaction(
    val value: String,
    val malformed: Boolean,
)

private fun redactSemanticQuotedPathValues(line: String): SemanticPathRedaction {
    val redacted = StringBuilder(line.length)
    var copiedUntil = 0
    var searchFrom = 0
    while (searchFrom < line.length) {
        val keyStart = line.indexOf('"', searchFrom)
        val keyEnd = if (keyStart >= 0) findQuotedStringEnd(line, keyStart + 1) else null
        if (keyStart < 0 || keyEnd == null) {
            searchFrom = line.length
        } else {
            val key = line.substring(keyStart + 1, keyEnd).lowercase()
            val colon = line.skipWhitespaceFrom(keyEnd + 1)
            val valueQuote = line.skipWhitespaceFrom(colon + 1)
            val isSemanticPathValue =
                key in SemanticPathKeys && line.getOrNull(colon) == ':' && line.getOrNull(valueQuote) == '"'
            if (isSemanticPathValue) {
                val valueEnd =
                    findQuotedStringEnd(line, valueQuote + 1)
                        ?: return SemanticPathRedaction(line, malformed = true)
                redacted.append(line, copiedUntil, valueQuote + 1)
                redacted.append("<path-redacted>")
                copiedUntil = valueEnd
                searchFrom = valueEnd + 1
            } else {
                searchFrom = keyEnd + 1
            }
        }
    }
    redacted.append(line, copiedUntil, line.length)
    return SemanticPathRedaction(redacted.toString(), malformed = false)
}

private fun findQuotedStringEnd(
    value: String,
    start: Int,
): Int? {
    var escaped = false
    for (index in start until value.length) {
        when {
            escaped -> escaped = false
            value[index] == '\\' -> escaped = true
            value[index] == '"' -> return index
        }
    }
    return null
}

private fun String.skipWhitespaceFrom(start: Int): Int {
    var index = start
    while (index < length && this[index].isWhitespace()) index += 1
    return index
}

private fun containsResidualAbsolutePath(line: String): Boolean =
    line.containsPercentEncodedPathSeparator() ||
        line.indices.any { index ->
            val character = line[index]
            // A backslash may be a Windows separator, a JSON escape layer, or the introducer for
            // a case-insensitive Unicode slash/backslash escape. None is safe to preserve verbatim.
            (character == '/' && line.isPathBoundaryBefore(index)) ||
                line.hasDrivePathStartAt(index) ||
                character == '\\'
        }

private fun String.containsPercentEncodedPathSeparator(): Boolean =
    indices.any { index -> this[index] == '%' && hasPercentEncodedPathSeparatorAt(index) }

private fun String.hasPercentEncodedPathSeparatorAt(percentIndex: Int): Boolean {
    var encodedByteStart = percentIndex + 1
    while (regionMatches(encodedByteStart, "25", 0, 2, ignoreCase = true)) encodedByteStart += 2
    return regionMatches(encodedByteStart, "2f", 0, 2, ignoreCase = true) ||
        regionMatches(encodedByteStart, "5c", 0, 2, ignoreCase = true)
}

private fun String.isPathBoundaryBefore(index: Int): Boolean =
    index == 0 || (!this[index - 1].isLetterOrDigit() && this[index - 1] != '_')

private fun String.hasDrivePathStartAt(index: Int): Boolean =
    isPathBoundaryBefore(index) &&
        getOrNull(index)?.isLetter() == true &&
        getOrNull(index + 1) == ':' &&
        (getOrNull(index + 2) == '/' || getOrNull(index + 2) == '\\')
