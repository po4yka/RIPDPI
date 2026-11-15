package com.poyka.ripdpi.data.uri

import java.net.URLEncoder

/** Normalize query names before scheme parsers interpret security fields; leave value encoding intact. */
internal fun normalizeProxyUriQuery(uri: String): String {
    val queryStart = uri.indexOf('?')
    val fragmentStart = uri.indexOf('#')
    if (queryStart < 0 || (fragmentStart >= 0 && fragmentStart < queryStart)) return uri
    val queryEnd = fragmentStart.takeIf { it >= 0 } ?: uri.length
    val query = uri.substring(queryStart + 1, queryEnd)
    if (query.isEmpty()) return uri
    val repeatedBindings = uri.substringBefore("://").equals("mierus", ignoreCase = true)
    val names = mutableSetOf<String>()
    val normalized =
        query.split('&').joinToString("&") { part ->
            val separator = part.indexOf('=')
            val rawName = if (separator < 0) part else part.substring(0, separator)
            val decodedName = requireNotNull(ProxyUriCodec.rfc3986PercentDecode(rawName))
            val name = if (decodedName.equals("pinSHA256", ignoreCase = true)) "pinSHA256" else decodedName
            val rawValue = if (separator < 0) null else part.substring(separator + 1)
            if (!repeatedBindings || name !in setOf("port", "protocol")) {
                require(names.add(name)) { "Repeated URI query parameter" }
            }
            val encodedName = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            if (rawValue == null) encodedName else "$encodedName=$rawValue"
        }
    return uri.substring(0, queryStart + 1) + normalized + uri.substring(queryEnd)
}
