package com.poyka.ripdpi.data.subscription

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.poyka.ripdpi.data.Hysteria2PortHopping
import com.poyka.ripdpi.data.hysteria2PortHopping
import com.poyka.ripdpi.data.parseHysteria2HopDuration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun singBoxHysteria2PortHopping(outbound: JsonObject): Hysteria2PortHopping? {
    val ports =
        outbound["server_ports"]?.let { value ->
            val values = (value as? JsonArray)?.toList() ?: listOf(value)
            require(values.isNotEmpty()) { "Hysteria hopping port list is empty" }
            values.joinToString(",") { element ->
                val text =
                    requireNotNull((element as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                        "Hysteria hopping port list must contain strings"
                    }
                require(text.matches(SingBoxPortRange)) { "Hysteria hopping port range is invalid" }
                text.replace(':', '-')
            }
        }
    val interval = outbound.hoppingString("hop_interval")?.let(::parseHysteria2HopDuration)
    val maximum = outbound.hoppingString("hop_interval_max")?.let(::parseHysteria2HopDuration)
    require(maximum == null || interval != null) { "Hysteria randomized hopping requires both intervals" }
    return hysteria2PortHopping(ports, interval, maximum)
}

internal fun bundleHysteria2PortHopping(extras: JsonObject): Hysteria2PortHopping? {
    val element = extras["port_hopping"] ?: return null
    val hopping = requireNotNull(element as? JsonObject) { "Hysteria hopping metadata must be an object" }
    require(hopping.keys == setOf("ports", "interval")) { "Hysteria hopping metadata fields are invalid" }
    val ports = requireNotNull(hopping.hoppingString("ports")) { "Hysteria hopping metadata requires ports" }
    val interval =
        requireNotNull(hopping.hoppingString("interval")) { "Hysteria hopping metadata requires an interval" }
    // The producer contract permits one range with either separator.
    require(ports.matches(BundlePortRange)) { "Hysteria hopping metadata range is invalid" }
    return hysteria2PortHopping(ports.replace(':', '-'), parseHysteria2HopDuration(interval))
}

internal fun clashHysteria2PortHopping(node: YamlMap): Hysteria2PortHopping? {
    fun scalar(key: String): String? {
        val value =
            node.entries.entries
                .firstOrNull { it.key.content == key }
                ?.value ?: return null
        return requireNotNull((value as? YamlScalar)?.content) { "Hysteria hopping fields must be scalar values" }
    }
    val ports = scalar("ports")?.replace('/', ',')
    val cadence = scalar("hop-interval")
    val intervals = cadence?.split('-')
    require(intervals == null || intervals.size in 1..2) { "Hysteria hopping interval range is invalid" }
    val minimum = intervals?.first()?.let(::clashHopSeconds)
    val maximum = intervals?.takeIf { it.size == 2 }?.last()?.let(::clashHopSeconds)
    return hysteria2PortHopping(ports, minimum, maximum)
}

private fun clashHopSeconds(value: String): Long {
    require(value.matches(ClashHopSeconds)) { "Hysteria hopping interval must use seconds" }
    return parseHysteria2HopDuration("${value}s")
}

private fun JsonObject.hoppingString(name: String): String? {
    val value = get(name) ?: return null
    return requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "Hysteria hopping fields must contain strings"
    }
}

private val SingBoxPortRange = Regex("[0-9]{1,5}(?::[0-9]{1,5})?")
private val BundlePortRange = Regex("[0-9]{1,5}[:-][0-9]{1,5}")
private val ClashHopSeconds = Regex("[0-9]+(?:\\.[0-9]+)?")
