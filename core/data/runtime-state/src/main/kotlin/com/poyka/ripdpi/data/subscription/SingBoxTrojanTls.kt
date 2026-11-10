package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The native Trojan client is TLS-only with certificate verification always on, so a sing-box
 * `tls` block that disables TLS, enables REALITY or sets `insecure` cannot become a native profile.
 */
internal fun JsonObject?.rejectedByNativeTrojan(): Boolean =
    this != null &&
        (
            flag("enabled") == false ||
                (get("reality") as? JsonObject)?.flag("enabled") == true ||
                flag("insecure") == true
        )

private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
