package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.PayloadFailoverPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

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
        val policy = strictJson.decodeFromJsonElement<PayloadFailoverPolicy>(value)
        require(policy.selectorTag == selectorTag)
        return policy
    }
}
