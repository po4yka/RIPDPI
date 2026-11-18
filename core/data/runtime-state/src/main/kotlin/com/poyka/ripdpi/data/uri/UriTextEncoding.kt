package com.poyka.ripdpi.data.uri

import java.net.URLEncoder

internal fun encodeUriComponent(value: String): String {
    require(Charsets.UTF_8.newEncoder().canEncode(value)) { "URI field contains invalid Unicode" }
    return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
