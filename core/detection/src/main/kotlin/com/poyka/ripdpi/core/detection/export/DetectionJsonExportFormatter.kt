@file:Suppress("TooManyFunctions")

package com.poyka.ripdpi.core.detection.export

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.VerdictNarrative
import com.poyka.ripdpi.core.detection.VerdictNarrativeBuilder
import com.poyka.ripdpi.core.detection.VerdictNarrativeRow
import com.poyka.ripdpi.core.detection.consensus.IpConsensusChannel
import com.poyka.ripdpi.core.detection.privacy.DetectionPrivacyMask
import com.poyka.ripdpi.core.detection.probe.XrayOutboundSummary
import com.poyka.ripdpi.serialization.RipDpiPrettyJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object DetectionJsonExportFormatter {
    private val json =
        RipDpiPrettyJson

    fun format(
        result: DetectionCheckResult,
        metadata: DetectionExportMetadata,
    ): String {
        val context = JsonExportContext(metadata.privacyMode)
        val narrative = result.verdictNarrative ?: VerdictNarrativeBuilder.build(result)
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "meta" to meta(metadata, context),
                    "verdict" to verdict(result, narrative, context),
                    "results" to results(result, context),
                    "ipConsensus" to ipConsensus(result, context),
                    "tunProbeDiagnostics" to JsonObject(mapOf("available" to JsonPrimitive(false))),
                ),
            ),
        )
    }

    private fun meta(
        metadata: DetectionExportMetadata,
        context: JsonExportContext,
    ): JsonObject =
        JsonObject(
            mapOf(
                "formatVersion" to JsonPrimitive(1),
                "timestamp" to context.string(metadata.timestamp),
                "appVersion" to context.string(metadata.appVersion),
                "buildType" to context.string(metadata.buildType),
                "privacyMode" to JsonPrimitive(metadata.privacyMode),
            ),
        )

    private fun verdict(
        result: DetectionCheckResult,
        narrative: VerdictNarrative,
        context: JsonExportContext,
    ): JsonObject =
        JsonObject(
            mapOf(
                "value" to JsonPrimitive(result.verdict.name),
                "status" to JsonPrimitive(result.verdict.name.lowercase()),
                "explanation" to context.string(result.verdictExplanation?.summary),
                "ruleApplied" to context.string(result.verdictExplanation?.ruleApplied),
                "appliedScopes" to
                    JsonArray(
                        result.verdictExplanation
                            ?.appliedScopes
                            .orEmpty()
                            .map { JsonPrimitive(it.name) },
                    ),
                "uniqueSignalCount" to JsonPrimitive(result.verdictExplanation?.uniqueSignalCount ?: 0),
                "exposureStatus" to JsonPrimitive(narrative.exposureStatus.name),
                "meaning" to JsonArray(listOf(context.string(narrative.exposureStatus.name.lowercase()))),
                "discovered" to JsonArray(narrative.discoveredRows.map { it.toJson(context) }),
                "reasons" to JsonArray(narrative.reasonRows.map { it.toJson(context) }),
                "homeRoutedRoaming" to JsonPrimitive(narrative.homeRoutedRoamingNote != null),
                "homeRoutedRoamingNote" to context.string(narrative.homeRoutedRoamingNote),
                "roamingDiagnostics" to roamingDiagnostics(result, context),
            ),
        )

    private fun results(
        result: DetectionCheckResult,
        context: JsonExportContext,
    ): JsonObject =
        JsonObject(
            mapOf(
                "geoIp" to category(result.geoIp, context),
                "ipComparison" to category(result.ipComparison?.category, context),
                "cdnPulling" to category(result.cdnPulling?.category, context),
                "directSigns" to category(result.directSigns, context),
                "indirectSigns" to category(result.indirectSigns, context),
                "nativeSigns" to category(result.nativeSigns?.category, context),
                "icmpSpoofing" to category(result.icmpSpoofing?.category, context),
                "rttTriangulation" to category(result.rttTriangulation?.category, context),
                "callTransport" to category(result.callTransport?.category, context),
                "locationSignals" to category(result.locationSignals, context),
                "dnsLeak" to category(result.dnsLeak, context),
                "webRtcLeak" to category(result.webRtcLeak, context),
                "tlsFingerprint" to category(result.tlsFingerprint, context),
                "timingAnalysis" to category(result.timingAnalysis, context),
                "bypass" to bypass(result.bypassResult, context),
            ),
        )

    private fun category(
        category: CategoryResult?,
        context: JsonExportContext,
    ): JsonObject =
        JsonObject(
            mapOf(
                "detected" to JsonPrimitive(category?.detected ?: false),
                "needsReview" to JsonPrimitive(category?.needsReview ?: false),
                "hasError" to JsonPrimitive(category == null),
                "findings" to JsonArray(category?.findings.orEmpty().map { it.toJson(context) }),
                "evidence" to JsonArray(category?.evidence.orEmpty().map { it.toJson(context) }),
                "matchedApps" to JsonArray(category?.matchedApps.orEmpty().map { context.string(it.appName) }),
                "activeApps" to JsonArray(category?.activeApps.orEmpty().map { context.string(it.serviceName) }),
            ),
        )

    private fun bypass(
        bypass: BypassResult,
        context: JsonExportContext,
    ): JsonObject =
        JsonObject(
            mapOf(
                "detected" to JsonPrimitive(bypass.detected),
                "needsReview" to JsonPrimitive(bypass.needsReview),
                "proxyEndpoint" to context.string(bypass.proxyEndpoint?.let { "${it.type}:${it.host}:${it.port}" }),
                "directIp" to context.string(bypass.directIp),
                "proxyIp" to context.string(bypass.proxyIp),
                "mtProtoReachable" to JsonPrimitive(bypass.mtProtoReachable),
                "stunReflexiveAddresses" to JsonArray(bypass.stunReflexiveAddresses.map(context::string)),
                "findings" to JsonArray(bypass.findings.map { it.toJson(context) }),
                "evidence" to JsonArray(bypass.evidence.map { it.toJson(context) }),
                "xrayOutbounds" to
                    JsonArray(
                        bypass.xrayApiScanResult
                            ?.outbounds
                            .orEmpty()
                            .map { it.toJson(context) },
                    ),
            ),
        )

    private fun ipConsensus(
        result: DetectionCheckResult,
        context: JsonExportContext,
    ): JsonObject =
        JsonObject(
            IpConsensusChannel.entries.associate { channel ->
                channel.name to
                    JsonArray(
                        result.ipConsensus
                            ?.observedIps
                            ?.get(channel)
                            .orEmpty()
                            .map(context::string),
                    )
            },
        )

    private fun roamingDiagnostics(
        result: DetectionCheckResult,
        context: JsonExportContext,
    ): JsonObject =
        JsonObject(
            mapOf(
                "findings" to JsonArray(result.locationSignals.findings.map { context.string(it.description) }),
            ),
        )

    private fun Finding.toJson(context: JsonExportContext): JsonObject =
        JsonObject(
            mapOf(
                "description" to context.string(description),
                "detected" to JsonPrimitive(detected),
                "needsReview" to JsonPrimitive(needsReview),
                "source" to context.string(source?.name),
                "confidence" to context.string(confidence?.name),
            ),
        )

    private fun EvidenceItem.toJson(context: JsonExportContext): JsonObject =
        JsonObject(
            mapOf(
                "source" to JsonPrimitive(source.name),
                "detected" to JsonPrimitive(detected),
                "confidence" to JsonPrimitive(confidence.name),
                "description" to context.string(description),
                "family" to context.string(family),
                "packageName" to context.string(packageName),
                "scope" to JsonPrimitive(scope.name),
            ),
        )

    private fun VerdictNarrativeRow.toJson(context: JsonExportContext): JsonObject =
        JsonObject(
            mapOf(
                "label" to context.string(label),
                "value" to context.string(value),
                "exposureStatus" to JsonPrimitive(exposureStatus.name),
            ),
        )

    private fun XrayOutboundSummary.toJson(context: JsonExportContext): JsonObject =
        JsonObject(
            mapOf(
                "tag" to context.string(tag),
                "protocol" to context.string(protocolName),
                "address" to context.string(address),
                "port" to (port?.let(::JsonPrimitive) ?: JsonNull),
                "sni" to context.string(sni),
                "senderSettingsType" to context.string(senderSettingsType),
                "proxySettingsType" to context.string(proxySettingsType),
                "uuidPresent" to JsonPrimitive(!uuid.isNullOrBlank()),
                "publicKeyPresent" to JsonPrimitive(!publicKey.isNullOrBlank()),
            ),
        )

    private class JsonExportContext(
        private val privacyMode: Boolean,
    ) {
        fun string(value: String?): JsonElement =
            value
                ?.let { DetectionPrivacyMask.maskIpsInText(it, enabled = privacyMode) }
                ?.let(::JsonPrimitive)
                ?: JsonNull
    }
}
