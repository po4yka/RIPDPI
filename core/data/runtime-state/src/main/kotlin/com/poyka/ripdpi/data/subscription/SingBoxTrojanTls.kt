package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The native Trojan client is TLS-only with certificate verification always on. In sing-box the
 * trojan `tls` block is optional and `enabled` defaults to false, so only an explicitly enabled,
 * verified, non-REALITY block can become a native profile.
 */
internal fun JsonObject?.isVerifiedNativeTrojanTls(): Boolean =
    this != null &&
        flag("enabled") == true &&
        (get("reality") as? JsonObject)?.flag("enabled") != true &&
        flag("insecure") != true

private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
