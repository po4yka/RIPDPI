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
            val objectValue = requireNotNull(value as? JsonObject) { "Payload policy must be an object" }
            require("quality" !in objectValue || objectValue["quality"] is JsonObject)
            val policy = RipDpiStrictJson.decodeFromJsonElement<PayloadFailoverPolicy>(value)
            require(policy.selectorTag == selectorTag)
            policy
        }
    }
}

/** Selector failover candidates after applying `ripdpi.endpoints` health. */
sealed interface EndpointHealthOrder {
    /** Usable candidates: neutral (healthy, unknown, expired, or no health) first, degraded last, declared order kept. */
    data class Candidates(
        val serverRefs: List<String>,
    ) : EndpointHealthOrder

    /** Every candidate is down with unexpired health. */
    data object NoUsableEndpoint : EndpointHealthOrder
}

/** Orders [serverRefs] by their health at [nowEpochMillis]: unexpired down is excluded, degraded is deprioritised. */
fun orderByEndpointHealth(
    serverRefs: List<String>,
    health: Map<String, EndpointHealth>,
    nowEpochMillis: Long,
): EndpointHealthOrder {
    val statusByRef = serverRefs.associateWith { health[it]?.statusAt(nowEpochMillis) }
    val usable = serverRefs.filterNot { statusByRef[it] == EndpointHealthStatus.DOWN }
    return if (usable.isEmpty() && serverRefs.isNotEmpty()) {
        EndpointHealthOrder.NoUsableEndpoint
    } else {
        EndpointHealthOrder.Candidates(usable.sortedBy { statusByRef[it] == EndpointHealthStatus.DEGRADED })
    }
}
