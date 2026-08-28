package com.poyka.ripdpi.core

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Reads only loopback readiness settings; never retains profile secrets. */
internal data class XrayBridgeConfig(
    val inboundPort: Int,
) {
    companion object {
        fun parse(json: String): XrayBridgeConfig {
            val root = RipDpiJson.parseToJsonElement(json) as JsonObject
            val inbounds = root["inbounds"] as? JsonArray ?: error("Xray inbound is missing")
            val inbound =
                inbounds.filterIsInstance<JsonObject>().single {
                    (it["protocol"] as? JsonPrimitive)?.contentOrNull == "socks"
                }
            check((inbound["listen"] as? JsonPrimitive)?.contentOrNull == "127.0.0.1")
            val port = (inbound["port"] as? JsonPrimitive)?.intOrNull ?: error("Xray inbound port is missing")
            check(port in 1..65535)
            return XrayBridgeConfig(port)
        }
    }
}
