@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ControlTier(
    val wireName: String,
) {
    @SerialName("domestic")
    DOMESTIC("domestic"),

    @SerialName("global")
    GLOBAL("global"),

    @SerialName("cdn")
    CDN("cdn"),
    ;

    companion object {
        fun fromWireName(value: String?): ControlTier? = entries.firstOrNull { it.wireName == value }
    }
}
