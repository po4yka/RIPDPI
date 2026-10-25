package com.poyka.ripdpi.data.wireguard

/**
 * Applies one AWG 3.x `[Interface]` key from a `.conf` (amneziawg-tools `config.c`).
 *
 * @throws WireGuardConfUnknownKeyException when [key] is not an AWG 3.x key.
 * @throws IllegalArgumentException when the value is malformed; the message never repeats a key value.
 */
internal fun AmneziaWgV3Parameters.withConfKey(
    key: String,
    value: String,
): AmneziaWgV3Parameters =
    when (key.lowercase()) {
        "headerprotectionkey" -> {
            require(AmneziaWgV3Parameters.isHeaderProtectionKey(value)) {
                "Invalid AmneziaWG HeaderProtectionKey: expected a base64 32-byte key"
            }
            copy(headerProtectionKey = AwgHeaderProtectionKey(value))
        }

        "contentpaddingaddition" -> {
            copy(contentPaddingAddition = parseConfRange(key, value))
        }

        "rekeyaftertime" -> {
            copy(rekeyAfterTime = parseConfRange(key, value))
        }

        "rekeytimeout" -> {
            copy(rekeyTimeout = parseConfRange(key, value))
        }

        "rejectaftertime" -> {
            copy(rejectAfterTime = parseConfRange(key, value))
        }

        "keepalivetimeout" -> {
            copy(keepaliveTimeout = parseConfRange(key, value))
        }

        "maxhandshakeattempts" -> {
            copy(maxHandshakeAttempts = parseConfRange(key, value))
        }

        "randomtrailers" -> {
            copy(randomTrailers = parseConfBool(key, value))
        }

        "disablecookies" -> {
            copy(disableCookies = parseConfBool(key, value))
        }

        else -> {
            throw WireGuardConfUnknownKeyException("Interface", key)
        }
    }

/** A `lo` or `lo-hi` range with `lo <= hi <= 65535`, returned unchanged. */
internal fun parseConfRange(
    key: String,
    value: String,
): String {
    require(AmneziaWgV3Parameters.rangeIsValid(value)) {
        "WireGuard config key '$key' expects a decimal value or lo-hi range up to 65535"
    }
    return value
}

/** amneziawg-tools `parse_bool`: `on`/`off` or a decimal number (non-zero = on); UAPI `true`/`false`. */
private fun parseConfBool(
    key: String,
    value: String,
): Boolean =
    when {
        value.equals("on", ignoreCase = true) || value.equals("true", ignoreCase = true) -> true
        value.equals("off", ignoreCase = true) || value.equals("false", ignoreCase = true) -> false
        value.isNotEmpty() && value.all(Char::isDigit) -> value.trimStart('0').isNotEmpty()
        else -> throw IllegalArgumentException("WireGuard config key '$key' expects on or off")
    }
