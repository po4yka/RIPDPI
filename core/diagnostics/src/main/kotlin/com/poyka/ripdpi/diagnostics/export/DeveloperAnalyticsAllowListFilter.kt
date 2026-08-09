package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
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
            "schemaVersion",
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
    private val failureEnvelopeKeys =
        setOf("stageKey", "stageLabel", "headline", "summary", "tcpErrors", "tlsErrors", "dnsErrors", "httpErrors")
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
            "failureEnvelopes" -> projectObjectArray(value, failureEnvelopeKeys)
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
