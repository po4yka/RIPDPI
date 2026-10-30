package com.poyka.ripdpi.data.wireguard

import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * AmneziaWG 3.x `HeaderProtectionKey`: a base64 32-byte key. [toString] never shows the value,
 * so data classes that hold it stay safe to log. Serializes as the plain base64 string.
 */
@Serializable
@JvmInline
value class AwgHeaderProtectionKey(
    val value: String,
) {
    val isSet: Boolean get() = value.isNotEmpty()

    override fun toString(): String = if (value.isEmpty()) "" else "<redacted>"

    companion object {
        val None = AwgHeaderProtectionKey("")
    }
}

/**
 * AmneziaWG 3.x (amneziawg-go v3.1) `[Interface]` keys. The defaults mean "not set" and keep the
 * AWG 1.x/2.0 wire behavior. Field names are the camelCase keys of the native `AwgV3Config`
 * (`amnezia.v3` in the AmneziaWG runtime JSON). Ranges are `lo` or `lo-hi` decimal text.
 * See `docs/native/amneziawg-3x-wire-semantics.md`.
 */
@Serializable
data class AmneziaWgV3Parameters(
    val headerProtectionKey: AwgHeaderProtectionKey = AwgHeaderProtectionKey.None,
    val contentPaddingAddition: String = "",
    val rekeyAfterTime: String = "",
    val rekeyTimeout: String = "",
    val rejectAfterTime: String = "",
    val keepaliveTimeout: String = "",
    val maxHandshakeAttempts: String = "",
    val randomTrailers: Boolean = false,
    val disableCookies: Boolean = false,
) {
    val isSet: Boolean get() = this != AmneziaWgV3Parameters()

    /** The range keys with their `.conf` names, in template order. */
    val ranges: List<Pair<String, String>>
        get() =
            listOf(
                "ContentPaddingAddition" to contentPaddingAddition,
                "RekeyAfterTime" to rekeyAfterTime,
                "RekeyTimeout" to rekeyTimeout,
                "RejectAfterTime" to rejectAfterTime,
                "KeepaliveTimeout" to keepaliveTimeout,
                "MaxHandshakeAttempts" to maxHandshakeAttempts,
            )

    /**
     * Rejects malformed 3.x values. [sizePadding] is `S1..S4` (null = 0): header protection takes
     * its nonce from the first 12 prefix bytes, so each must be at least 12 when the key is set.
     * Messages name the key, never the key material.
     */
    fun requireValid(sizePadding: List<Int?>) {
        require(!headerProtectionKey.isSet || isHeaderProtectionKey(headerProtectionKey.value)) {
            "AmneziaWG HeaderProtectionKey must be a base64 32-byte key"
        }
        if (headerProtectionEnabled) {
            sizePadding.forEachIndexed { index, size ->
                require((size ?: 0) >= HeaderNonceBytes) {
                    "AmneziaWG header protection requires S${index + 1} >= $HeaderNonceBytes"
                }
            }
        }
        ranges.forEach { (name, value) ->
            require(value.isEmpty() || rangeIsValid(value)) {
                "AmneziaWG $name must be a decimal value or lo-hi range up to $MaximumRange"
            }
        }
    }

    /** False for an unset or all-zero key, which amneziawg-go treats as "off". */
    val headerProtectionEnabled: Boolean
        get() = headerProtectionKey.isSet && decodeKey(headerProtectionKey.value)?.any { it != 0.toByte() } == true

    companion object {
        const val HeaderNonceBytes = 12
        const val MaximumRange = 65_535
        private const val KeyBytes = 32
        private val Decimal = Regex("0|[1-9][0-9]*")

        /** `lo` or `lo-hi` with `lo <= hi <= 65535`, like amneziawg-tools `u16_range_from_string`. */
        fun rangeIsValid(value: String): Boolean {
            val bounds =
                value
                    .split('-')
                    .takeIf { parts -> parts.size in 1..2 && parts.all { Decimal.matches(it) } }
                    ?.map { it.toIntOrNull() ?: Int.MAX_VALUE }
            return bounds != null && bounds.last() <= MaximumRange && bounds.first() <= bounds.last()
        }

        /** Keepalive `0`/`0-0` disables it; nonzero ranges must start at one or more. */
        fun parsePersistentKeepalive(value: String): Pair<Int, Int?>? {
            if (!rangeIsValid(value)) return null
            val bounds = value.split('-').map(String::toInt)
            val low = bounds.first()
            val high = bounds.last()
            return if (low == 0 && high != 0) null else low to high.takeIf { it != low }
        }

        fun isHeaderProtectionKey(value: String): Boolean = decodeKey(value) != null

        private fun decodeKey(value: String): ByteArray? =
            runCatching { Base64.getDecoder().decode(value) }.getOrNull()?.takeIf { it.size == KeyBytes }
    }
}
