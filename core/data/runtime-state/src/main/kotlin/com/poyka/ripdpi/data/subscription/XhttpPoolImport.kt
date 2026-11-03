package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.MaxBoundedXhttpConnections
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Only the measured pool profile is admitted; retirement controls cannot silently disappear. */
internal fun JsonObject.xhttpMaxConnections(): Int? {
    if ("xmux" !in this) return null
    val pool = this["xmux"] as? JsonObject
    require(
        pool != null && pool.keys == setOf("maxConnections", "hMaxRequestTimes", "hMaxReusableSecs", "cMaxReuseTimes"),
    ) {
        "Unsupported XHTTP pool settings"
    }

    fun integer(key: String): Int? = (pool[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    val limit = integer("maxConnections")
    require(
        (this["mode"] as? JsonPrimitive)?.contentOrNull == "stream-up" && limit != null &&
            limit in 1..MaxBoundedXhttpConnections &&
            listOf("hMaxRequestTimes", "hMaxReusableSecs", "cMaxReuseTimes").all { integer(it) == 0 },
    ) { "Unsupported XHTTP pool settings" }
    return limit
}
