package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters
import com.poyka.ripdpi.data.wireguard.AwgHeaderProtectionKey

/** `.conf` range key -> `amneziawg://` query key, in template order. */
private val RangeQueryKeys =
    mapOf(
        "ContentPaddingAddition" to "content_padding_addition",
        "RekeyAfterTime" to "rekey_after_time",
        "RekeyTimeout" to "rekey_timeout",
        "RejectAfterTime" to "reject_after_time",
        "KeepaliveTimeout" to "keepalive_timeout",
        "MaxHandshakeAttempts" to "max_handshake_attempts",
    )

/** Query keys added for AWG 3.x and the `.conf` fields a share link now carries. */
internal val AmneziaWgUriAwg3Keys: Set<String> =
    setOf("address", "persistent_keepalive", "header_protection_key", "random_trailers", "disable_cookies") +
        RangeQueryKeys.values

/** The set AWG 3.x keys as query parameters; unset keys are omitted. */
internal fun AmneziaWgV3Parameters.toUriParams(): List<Pair<String, String>> =
    buildList {
        if (headerProtectionKey.isSet) add("header_protection_key" to headerProtectionKey.value)
        ranges.filter { it.second.isNotEmpty() }.forEach { (name, value) ->
            add(RangeQueryKeys.getValue(name) to value)
        }
        if (randomTrailers) add("random_trailers" to "on")
        if (disableCookies) add("disable_cookies" to "on")
    }

/** The AWG 3.x keys of a share link, or `null` when a flag is not `on`/`off`. Values are validated later. */
internal fun parseUriAwg3(params: Map<String, String>): AmneziaWgV3Parameters? {
    val flags = listOf("random_trailers", "disable_cookies").map { params[it] ?: "off" }
    if (flags.any { it != "on" && it != "off" }) return null
    return AmneziaWgV3Parameters(
        headerProtectionKey = AwgHeaderProtectionKey(params["header_protection_key"].orEmpty()),
        contentPaddingAddition = params["content_padding_addition"].orEmpty(),
        rekeyAfterTime = params["rekey_after_time"].orEmpty(),
        rekeyTimeout = params["rekey_timeout"].orEmpty(),
        rejectAfterTime = params["reject_after_time"].orEmpty(),
        keepaliveTimeout = params["keepalive_timeout"].orEmpty(),
        maxHandshakeAttempts = params["max_handshake_attempts"].orEmpty(),
        randomTrailers = flags[0] == "on",
        disableCookies = flags[1] == "on",
    )
}

/** `persistent_keepalive` bounds; [high] is null for a fixed interval. */
internal class UriKeepalive(
    val low: Int?,
    val high: Int?,
)

/** Parses `persistent_keepalive=lo[-hi]`; absent = no keepalive; malformed or `0-N` = `null`. */
internal fun parseUriKeepalive(value: String?): UriKeepalive? {
    if (value == null) return UriKeepalive(null, null)
    return AmneziaWgV3Parameters.parsePersistentKeepalive(value)?.let { (low, high) -> UriKeepalive(low, high) }
}
