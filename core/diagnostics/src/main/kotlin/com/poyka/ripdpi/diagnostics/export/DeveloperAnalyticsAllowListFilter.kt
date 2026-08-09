package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsSchemaVersion
import com.poyka.ripdpi.diagnostics.DeveloperFailureFactField
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Builds the disclosed developer-analytics surface from nested positive projections. */
internal object DeveloperAnalyticsAllowListFilter {
    private val topLevelKeys =
        setOf(
            "generatedAtIsoUtc",
            "stageTimings",
            "failureEnvelopes",
            "reproductionContext",
            "nativeRuntime",
            "effectiveConfigDiff",
            "networkSnapshots",
            "deviceState",
            "baselineDelta",
            "notes",
        )
    private val stageTimingKeys =
        setOf("stageKey", "wallClockMs", "cpuMs", "dnsMs", "tcpHandshakeMs", "tlsHandshakeMs", "ttfbMs", "notes")
    private val reproductionKeys =
        setOf(
            "appVersionName",
            "appVersionCode",
            "buildCommit",
            "buildFlavor",
            "buildType",
            "buildTimestampIsoUtc",
            "nativeLibVersion",
            "kotlinVersion",
            "rustToolchain",
            "ndkVersion",
            "cargoProfile",
        )
    private val nativeRuntimeKeys = setOf("openFileDescriptors", "threadCount", "virtualMemoryKb", "residentSetKb")
    private val allowedConfigKeys =
        setOf(
            "desyncMode",
            "dnsMode",
            "fullTunnelMode",
            "entropyMode",
            "tlsFingerprintProfile",
            "webrtcProtectionEnabled",
            "strategyEvolution",
            "proxyPort",
        )
    private val configEntryKeys = setOf("key", "defaultValue", "actualValue")
    private val networkSnapshotKeys =
        setOf(
            "stageKey",
            "capturedAtIsoUtc",
            "transport",
            "dnsServers",
            "signalStrengthDbm",
            "cellularLevel",
            "linkDownstreamKbps",
            "linkUpstreamKbps",
            "captivePortalDetected",
            "meteredNetwork",
            "vpnActive",
            "mtu",
        )
    private val deviceStateKeys =
        setOf(
            "androidSdk",
            "abi",
            "batteryPercent",
            "batteryCharging",
            "thermalStatus",
            "dozeModeActive",
            "powerSaveActive",
            "appStandbyBucket",
            "lowMemory",
        )
    private val baselineDeltaKeys = setOf("baselineClass", "baselineVersion", "comparisons")
    private val baselineMetricKeys = setOf("metric", "userValue", "baselineMedian", "verdict")

    fun filterToJson(
        payload: DeveloperAnalyticsPayload,
        json: Json,
    ): JsonObject {
        val source = json.encodeToJsonElement(DeveloperAnalyticsPayload.serializer(), payload).jsonObject
        return JsonObject(
            buildMap {
                put("schemaVersion", JsonPrimitive(DeveloperAnalyticsSchemaVersion))
                topLevelKeys.forEach { key ->
                    val value = source[key] ?: return@forEach
                    put(key, projectTopLevel(key, value))
                }
            },
        )
    }

    private fun projectTopLevel(
        key: String,
        value: JsonElement,
    ): JsonElement =
        when (key) {
            "stageTimings" -> projectObjectArray(value, stageTimingKeys)
            "failureEnvelopes" -> DeveloperFailureEnvelopeProjection.project(value)
            "reproductionContext" -> projectNullableObject(value, reproductionKeys)
            "nativeRuntime" -> projectNullableObject(value, nativeRuntimeKeys)
            "effectiveConfigDiff" -> projectConfigDiff(value)
            "networkSnapshots" -> projectNetworkSnapshots(value)
            "deviceState" -> projectNullableObject(value, deviceStateKeys)
            "baselineDelta" -> projectBaselineDelta(value)
            else -> redactStrings(value)
        }

    private fun projectConfigDiff(value: JsonElement): JsonElement =
        JsonArray(
            value.jsonArray.mapNotNull { element ->
                val source = element as? JsonObject ?: return@mapNotNull null
                val key = source["key"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (key !in allowedConfigKeys) return@mapNotNull null
                projectObject(source, configEntryKeys)
            },
        )

    private fun projectNetworkSnapshots(value: JsonElement): JsonElement =
        JsonArray(
            value.jsonArray.mapNotNull { element ->
                val source = element as? JsonObject ?: return@mapNotNull null
                val projected = projectObject(source, networkSnapshotKeys).toMutableMap()
                source["dnsServers"]?.jsonArray?.let { servers ->
                    projected["dnsServers"] =
                        if (servers.isEmpty()) {
                            JsonArray(emptyList())
                        } else {
                            JsonArray(listOf(JsonPrimitive("redacted(${servers.size})")))
                        }
                }
                JsonObject(projected)
            },
        )

    private fun projectBaselineDelta(value: JsonElement): JsonElement =
        when (value) {
            is JsonNull -> {
                value
            }

            is JsonObject -> {
                val projected = projectObject(value, baselineDeltaKeys).toMutableMap()
                value["comparisons"]?.let { comparisons ->
                    projected["comparisons"] = projectObjectArray(comparisons, baselineMetricKeys)
                }
                JsonObject(projected)
            }

            else -> {
                JsonNull
            }
        }

    private fun projectNullableObject(
        value: JsonElement,
        keys: Set<String>,
    ): JsonElement = if (value is JsonNull) value else projectObject(value.jsonObject, keys)

    private fun projectObjectArray(
        value: JsonElement,
        keys: Set<String>,
    ): JsonArray =
        JsonArray(
            value.jsonArray.mapNotNull { element ->
                (element as? JsonObject)?.let { projectObject(it, keys) }
            },
        )

    private fun projectObject(
        source: JsonObject,
        keys: Set<String>,
    ): JsonObject =
        JsonObject(
            buildMap {
                keys.forEach { key -> source[key]?.let { value -> put(key, redactStrings(value)) } }
            },
        )

    private fun redactStrings(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> {
                JsonObject(value.mapValues { (_, nested) -> redactStrings(nested) })
            }

            is JsonArray -> {
                JsonArray(value.map(::redactStrings))
            }

            is JsonPrimitive -> {
                if (value.isString) JsonPrimitive(redactDiagnosticsArchiveText(value.content)) else value
            }
        }
}

private object DeveloperFailureEnvelopeProjection {
    private const val StageKeyGroupIndex = 1
    private const val FieldPathGroupIndex = 3
    private const val FieldValueGroupIndex = 4
    private val referencePaths =
        mapOf(
            "tcpErrors" to
                setOf(
                    "tcp/status",
                    "domain/transportFailure",
                    "service/endpointStatus",
                    "service/endpointFailure",
                    "circumvention/handshakeStatus",
                    "circumvention/handshakeFailure",
                    "strategy/status",
                    "strategy/transportFailure",
                ),
            "tlsErrors" to
                setOf(
                    "domain/tls13Status",
                    "domain/tls12Status",
                    "domain/tlsEchStatus",
                    "strategy/tlsEchStatus",
                ),
            "dnsErrors" to setOf("dns/status"),
            "httpErrors" to
                setOf(
                    "domain/httpStatus",
                    "service/bootstrapStatus",
                    "service/mediaStatus",
                    "circumvention/bootstrapStatus",
                    "strategy/status",
                    "throughput/status",
                ),
            "quicErrors" to
                setOf(
                    "quic/status",
                    "quic/transportFailure",
                    "service/quicStatus",
                    "service/quicFailure",
                    "strategy/status",
                    "strategy/transportFailure",
                ),
        )
    private val allowedValuesByPath =
        DeveloperFailureFactField.entries.associate { field -> field.reportPath to field.allowedWireValues }
    private val stageKeyPattern = Regex("[a-z0-9_]+")
    private val referencePattern =
        Regex("stages/([a-z0-9_]+)/report\\.json#/observations/(0|[1-9][0-9]*)/([^=]+)=([A-Z][A-Z0-9_]*)")

    fun project(value: JsonElement): JsonArray =
        JsonArray(
            value.jsonArray.mapNotNull { element ->
                val source = element as? JsonObject ?: return@mapNotNull null
                val stageKey = (source["stageKey"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                stageKey.takeIf(stageKeyPattern::matches)?.let { projectEnvelope(source, it) }
            },
        )

    private fun projectEnvelope(
        source: JsonObject,
        stageKey: String,
    ): JsonObject {
        val errors =
            referencePaths.mapValues { (key, paths) ->
                val references = source[key] as? JsonArray ?: JsonArray(emptyList())
                JsonArray(
                    references
                        .filterIsInstance<JsonPrimitive>()
                        .map { it.content }
                        .filter { isTypedReference(it, stageKey, paths) }
                        .map(::JsonPrimitive),
                )
            }
        val factCount = errors.values.sumOf(JsonArray::size)
        return JsonObject(
            buildMap {
                put("stageKey", JsonPrimitive(stageKey))
                put("stageLabel", JsonPrimitive(stageKey))
                put("headline", JsonPrimitive("Typed probe failures recorded"))
                put(
                    "summary",
                    JsonPrimitive("$factCount typed failure facts; see referenced stage report observations."),
                )
                putAll(errors)
            },
        )
    }

    private fun isTypedReference(
        reference: String,
        stageKey: String,
        allowedPaths: Set<String>,
    ): Boolean {
        val match = referencePattern.matchEntire(reference)
        if (match == null || match.groupValues[StageKeyGroupIndex] != stageKey) return false
        val fieldPath = match.groupValues[FieldPathGroupIndex]
        val fieldValue = match.groupValues[FieldValueGroupIndex]
        return fieldPath in allowedPaths && fieldValue in allowedValuesByPath[fieldPath].orEmpty()
    }
}
