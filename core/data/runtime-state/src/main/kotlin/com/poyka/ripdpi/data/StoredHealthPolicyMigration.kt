package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import com.poyka.ripdpi.serialization.RipDpiStrictJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One-time local catalogue upgrade; external bundle decoding never calls this migration. */
fun migrateStoredHealthPolicy(stored: String): String {
    var changed = false
    val groups =
        RipDpiJson.parseToJsonElement(stored).jsonArray.map { element ->
            val group = element.jsonObject
            val failover = group["failover"] as? JsonObject ?: return@map element
            val policy = failover["payloadPolicy"] as? JsonObject ?: return@map element
            if (policy["schema_version"]?.jsonPrimitive?.intOrNull != 1) return@map element
            require("quality" !in policy) { "Stored schema-1 policy must be health-only" }
            require(
                policy.getValue("probes").jsonArray.all {
                    it.jsonObject
                        .getValue("expected_bytes")
                        .jsonPrimitive.intOrNull in LegacyMinimumPayloadBytes..LegacyMaximumPayloadBytes
                },
            ) { "Stored schema-1 payload size is invalid" }
            val upgraded = JsonObject(policy + ("schema_version" to JsonPrimitive(2)))
            // All remaining health fields have identical invariants in schema 2.
            RipDpiStrictJson.decodeFromJsonElement(PayloadFailoverPolicy.serializer(), upgraded)
            changed = true
            JsonObject(group + ("failover" to JsonObject(failover + ("payloadPolicy" to upgraded))))
        }
    return if (changed) JsonArray(groups).toString() else stored
}

private const val LegacyMinimumPayloadBytes = 32_768
private const val LegacyMaximumPayloadBytes = 65_536
