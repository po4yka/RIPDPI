@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a control lives, so one blocked provider or a broken international link is not a broken network. */
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
