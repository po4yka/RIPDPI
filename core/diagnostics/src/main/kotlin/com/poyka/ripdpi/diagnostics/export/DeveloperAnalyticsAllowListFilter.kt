package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Enforces the developer-analytics.json privacy allow-list before the payload is
 * written into a diagnostics archive.
 *
 * Filtering operates on the serialized [JsonObject] so that disallowed fields are
 * completely absent from the output — not merely empty. This is required because
 * kotlinx.serialization always encodes non-nullable list/map fields even when empty.
 *
 * Allowed fields are those disclosed on DataTransparencyScreen.
 * Any field added to [DeveloperAnalyticsPayload] that is NOT in the disclosure surface
 * must be suppressed here to keep the archive within the privacy boundary.
 *
 * Disallowed fields (removed from output):
 * - `pcapManifest` — raw packet-capture metadata
 * - `breadcrumbs` — internal event trail
 * - `reproductionContext.nativeLibDigests` — binary hashes (key removed from nested object)
 * - `nativeRuntime.recentLogTail` and `nativeRuntime.lastPanicBacktrace` — crash and log data
 * - `effectiveConfigDiff` entries whose `key` is `rootModeEnabled` or `enableCmdSettings`
 * - all network-snapshot fields outside the explicit coarse-metadata allow-list
 */
internal object DeveloperAnalyticsAllowListFilter {
    private val deniedTopLevelKeys = setOf("pcapManifest", "breadcrumbs")
    private val deniedConfigDiffKeys = setOf("rootModeEnabled", "enableCmdSettings")
    private val allowedNetworkSnapshotKeys =
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

    fun filterToJson(
        payload: DeveloperAnalyticsPayload,
        json: Json,
    ): JsonObject {
        val root =
            json
                .encodeToJsonElement(DeveloperAnalyticsPayload.serializer(), payload)
                .jsonObject
                .toMutableMap()

        // Remove denied top-level keys entirely
        for (key in deniedTopLevelKeys) {
            root.remove(key)
        }

        // Strip nativeLibDigests from reproductionContext
        root["reproductionContext"]?.jsonObject?.let { repro ->
            root["reproductionContext"] = JsonObject(repro - "nativeLibDigests")
        }

        // Remove raw log content and null out the panic backtrace inside nativeRuntime.
        root["nativeRuntime"]?.jsonObject?.let { runtime ->
            root["nativeRuntime"] =
                JsonObject(
                    runtime
                        .toMutableMap()
                        .apply {
                            remove("recentLogTail")
                            this["lastPanicBacktrace"] = JsonNull
                        },
                )
        }

        // Remove denied-key entries from effectiveConfigDiff array
        root["effectiveConfigDiff"]?.jsonArray?.let { array ->
            root["effectiveConfigDiff"] =
                JsonArray(
                    array.filter { element ->
                        val entryKey =
                            runCatching {
                                element.jsonObject["key"]?.jsonPrimitive?.content
                            }.getOrNull()
                        entryKey !in deniedConfigDiffKeys
                    },
                )
        }

        root["networkSnapshots"]?.jsonArray?.let { array ->
            root["networkSnapshots"] =
                JsonArray(
                    array.map { element ->
                        val source = element.jsonObject
                        val projected =
                            source
                                .filterKeys(allowedNetworkSnapshotKeys::contains)
                                .toMutableMap()
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
        }

        return JsonObject(root)
    }
}
