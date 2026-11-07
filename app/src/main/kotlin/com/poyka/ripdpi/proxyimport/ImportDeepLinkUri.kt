package com.poyka.ripdpi.proxyimport

import java.net.URI
import java.net.URLDecoder

/** Keep client remarks lenient while URI validates the query and percent escapes. */
internal fun parseImportDeepLinkUri(value: String): URI? =
    runCatching {
        val fragmentIndex = value.indexOf('#')
        val body = value.substringBefore('#').replace(" ", "%20")
        val fragment =
            if (fragmentIndex < 0) {
                ""
            } else {
                val decoded = URLDecoder.decode(value.substring(fragmentIndex + 1).replace("+", "%2B"), "UTF-8")
                URI(null, null, decoded).toASCIIString()
            }
        URI(body + fragment)
    }.getOrNull()

/** Import query parameters use form encoding and keep the first value of each name. */
internal fun URI.importQueryParameters(): Map<String, String> =
    buildMap {
        for (pair in rawQuery.orEmpty().split('&')) {
            val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            if (key.isNotEmpty()) putIfAbsent(key, value)
        }
    }
