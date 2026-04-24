package com.poyka.ripdpi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class AppStatus {
    Halted,
    Running,
}

@Serializable
enum class Mode {
    @SerialName("proxy")
    Proxy,

    @SerialName("vpn")
    VPN,
    ;

    val preferenceValue: String
        get() =
            when (this) {
                Proxy -> "proxy"
                VPN -> "vpn"
            }

    companion object {
        fun fromString(name: String): Mode =
            entries.firstOrNull { it.preferenceValue.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("Invalid mode: $name")
    }
}
