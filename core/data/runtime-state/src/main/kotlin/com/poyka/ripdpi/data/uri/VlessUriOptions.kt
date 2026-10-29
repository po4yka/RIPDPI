package com.poyka.ripdpi.data.uri

import java.net.URLDecoder

/** Query options must be represented or explicitly match a supported fixed value. */
internal fun supportsNativeVlessUriOptions(rawQuery: String?): Boolean =
    runCatching {
        if (rawQuery.isNullOrBlank()) return@runCatching true
        val entries =
            rawQuery.split('&').map { part ->
                val separator = part.indexOf('=')
                require(separator > 0)
                val key = part.substring(0, separator)
                require(URLDecoder.decode(key, "UTF-8") == key)
                key to URLDecoder.decode(part.substring(separator + 1), "UTF-8")
            }
        require(entries.map { it.first }.distinct().size == entries.size)
        val values = entries.toMap()
        val xhttp = values["type"]?.lowercase() == "xhttp"
        val reality = values["security"]?.lowercase() == "reality" || !values["pbk"].isNullOrBlank()
        val supported =
            setOf("type", "security", "sni", "flow", "fp", "encryption", "headerType") +
                (if (xhttp) setOf("path", "host", "mode") else emptySet()) +
                (if (reality) setOf("pbk", "sid") else setOf("serverName", "allowInsecure"))
        values.keys.all { it in supported } &&
            values["encryption"]?.lowercase() in setOf(null, "none") &&
            values["headerType"]?.lowercase() in setOf(null, "none") &&
            values["allowInsecure"]?.lowercase() in setOf(null, "0", "false") &&
            (!reality || values["security"]?.lowercase() in setOf(null, "reality"))
    }.getOrDefault(false)
