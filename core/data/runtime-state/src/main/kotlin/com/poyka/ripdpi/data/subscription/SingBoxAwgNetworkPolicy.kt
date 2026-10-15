package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.awg.ValidAmneziaWgMtu
import com.poyka.ripdpi.data.awg.isIpv4Cidr
import com.poyka.ripdpi.data.awg.isIpv6Cidr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Present policy must be an explicit string list; malformed data never clears saved policy. */
internal fun invalidAwgPolicyField(
    obj: JsonObject,
    peer: JsonObject,
): String? =
    when {
        !obj["address"].isDualStackHostAddresses() -> "address"
        !obj["mtu"].isDeployedMtu() -> "mtu"
        "dns" in obj && !obj["dns"].isNonblankStringList() -> "dns"
        "allowed_ips" in peer && !peer["allowed_ips"].isNonblankStringList() -> "allowed_ips"
        else -> null
    }

private fun JsonElement?.isDualStackHostAddresses(): Boolean {
    val addresses = this as? JsonArray ?: return false
    return if (addresses.size == DualStackAddressCount && addresses.isNonblankStringList()) {
        val ipv4 = (addresses[0] as JsonPrimitive).content
        val ipv6 = (addresses[1] as JsonPrimitive).content
        ipv4.endsWith("/32") && ipv4.isIpv4Cidr() &&
            ipv6.endsWith("/128") && ipv6.isIpv6Cidr() && ipv6 == ipv6.lowercase()
    } else {
        false
    }
}

private fun JsonElement?.isDeployedMtu(): Boolean {
    val primitive = this as? JsonPrimitive ?: return false
    return !primitive.isString && primitive.intOrNull?.let { it in ValidAmneziaWgMtu } == true
}

private const val DualStackAddressCount = 2

private fun JsonElement?.isNonblankStringList(): Boolean =
    (this as? JsonArray)?.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() } == true
