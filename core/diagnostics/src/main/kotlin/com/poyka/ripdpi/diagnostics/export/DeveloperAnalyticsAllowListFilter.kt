package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * - `nativeRuntime.lastPanicBacktrace` — crash data (key set to null in nested object)
 * - `effectiveConfigDiff` entries whose `key` is `rootModeEnabled` or `enableCmdSettings`
 */
internal object DeveloperAnalyticsAllowListFilter {
    private val deniedTopLevelKeys = setOf("pcapManifest", "breadcrumbs")
    private val deniedConfigDiffKeys = setOf("rootModeEnabled", "enableCmdSettings")

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

        // Null out lastPanicBacktrace inside nativeRuntime
        root["nativeRuntime"]?.jsonObject?.let { runtime ->
            root["nativeRuntime"] =
                JsonObject(
                    runtime.toMutableMap().apply { this["lastPanicBacktrace"] = JsonNull },
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

        return JsonObject(root)
    }
}
