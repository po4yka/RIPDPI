package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.serialization.RipDpiStrictJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/** The optional extension is strict: presence never falls back to weaker URL-test semantics. */
internal object PayloadFailoverImport {
    fun parse(
        payload: String,
        selectorTag: String?,
    ): PayloadFailoverPolicy? {
        val root = RipDpiStrictJson.parseToJsonElement(payload) as? JsonObject
        val extension = root?.get("ripdpi") as? JsonObject ?: return null
        return extension["payload_failover"]?.let { value ->
            require(
                (extension["schema_version"] as? JsonPrimitive)?.intOrNull ==
                    SingBoxSubscriptionParser.RIPDPI_SCHEMA_VERSION,
            )
            val policy = RipDpiStrictJson.decodeFromJsonElement<PayloadFailoverPolicy>(value)
            require(policy.selectorTag == selectorTag)
            policy
        }
    }
}
