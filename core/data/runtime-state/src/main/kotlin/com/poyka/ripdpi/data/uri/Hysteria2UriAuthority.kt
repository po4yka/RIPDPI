package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.Hysteria2PortHopping
import com.poyka.ripdpi.data.hysteria2PortHopping
import com.poyka.ripdpi.data.parseHysteria2HopDuration
import java.net.URI
import java.net.URLDecoder

internal data class Hysteria2UriAuthority(
    val uri: URI,
    val portHopping: Hysteria2PortHopping?,
)

/** Normalize only the multi-port authority before Java URI parses the remaining URI components. */
internal fun parseHysteria2UriAuthority(raw: String): Hysteria2UriAuthority {
    require(raw.length <= MaximumHysteriaUriLength) { "Hysteria URI exceeds the size limit" }
    val schemeEnd = raw.indexOf("://")
    require(schemeEnd > 0) { "Hysteria URI requires an authority" }
    val authorityStart = schemeEnd + 3
    val authorityEnd = raw.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).takeIf { it >= 0 } ?: raw.length
    val authority = raw.substring(authorityStart, authorityEnd)
    require(authority.count { it == '@' } == 1) { "Hysteria URI requires unambiguous authentication" }
    val userInfo = authority.substringBefore('@')
    val endpoint = authority.substringAfter('@')
    val hostEnd =
        if (endpoint.startsWith('[')) {
            endpoint.indexOf(']').also { require(it > 1) { "Hysteria URI has an invalid IPv6 host" } } + 1
        } else {
            endpoint.indexOf(':').takeIf { it >= 0 } ?: endpoint.length
        }
    val host = endpoint.substring(0, hostEnd)
    require(host.isNotBlank() && '%' !in host) { "Hysteria URI has an invalid host" }
    val suffix = endpoint.substring(hostEnd)
    require(suffix.isEmpty() || suffix.startsWith(':')) { "Hysteria URI has an invalid endpoint" }
    val portText = if (suffix.isEmpty()) DefaultHysteriaPort else suffix.substring(1)
    val ports = requireNotNull(hysteria2PortHopping(portText))
    val parsed =
        URI(raw.substring(0, authorityStart) + "$userInfo@$host:${ports.firstPort}" + raw.substring(authorityEnd))
    require(
        !parsed.host.isNullOrBlank() && parsed.path in listOf("", "/"),
    ) { "Hysteria URI has an invalid host or path" }
    val intervals = hoppingQuery(parsed.rawQuery)
    val hopping =
        if (',' in portText || '-' in portText || intervals.isNotEmpty()) {
            val fixed = intervals["hopInterval"]
            val minimum = intervals["minHopInterval"]
            val maximum = intervals["maxHopInterval"]
            require(
                fixed == null || (minimum == null && maximum == null),
            ) { "Hysteria hopping interval modes conflict" }
            require((minimum == null) == (maximum == null)) { "Hysteria randomized hopping requires both intervals" }
            hysteria2PortHopping(ports.ports, (fixed ?: minimum)?.let(::uriHopDuration), maximum?.let(::uriHopDuration))
        } else {
            null
        }
    return Hysteria2UriAuthority(parsed, hopping)
}

private fun hoppingQuery(query: String?): Map<String, String> {
    val result = mutableMapOf<String, String>()
    query?.split('&')?.forEach { component ->
        val name = URLDecoder.decode(component.substringBefore('='), "UTF-8")
        require(name != "mport") { "Hysteria URI uses an unsupported hopping parameter" }
        if (name in HysteriaHopQueryNames) {
            require('=' in component && name !in result) { "Hysteria hopping interval is missing or repeated" }
            result[name] = URLDecoder.decode(component.substringAfter('='), "UTF-8")
        }
    }
    return result
}

/** Numeric seconds are the existing application URI extension; unit strings retain exact precision. */
private fun uriHopDuration(raw: String): Long =
    parseHysteria2HopDuration(if (raw.matches(WholeSeconds)) "${raw}s" else raw)

private const val MaximumHysteriaUriLength = 2 * 1024 * 1024
private const val DefaultHysteriaPort = "443"
private val HysteriaHopQueryNames = setOf("hopInterval", "minHopInterval", "maxHopInterval")
private val WholeSeconds = Regex("[0-9]+")
