package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.PayloadFailoverPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/** The optional extension is strict: presence never falls back to weaker URL-test semantics. */
internal object PayloadFailoverImport {
    private val strictJson = Json { ignoreUnknownKeys = false }

    fun parse(
        payload: String,
        selectorTag: String?,
    ): PayloadFailoverPolicy? {
        val root = strictJson.parseToJsonElement(payload) as? JsonObject ?: return null
        val extension = root["ripdpi"] as? JsonObject ?: return null
        val value = extension["payload_failover"] ?: return null
        require(
            (extension["schema_version"] as? JsonPrimitive)?.intOrNull ==
                SingBoxSubscriptionParser.RIPDPI_SCHEMA_VERSION,
        )
        val policy = strictJson.decodeFromJsonElement<PayloadFailoverPolicy>(value)
        require(policy.selectorTag == selectorTag)
        return policy
    }
}
