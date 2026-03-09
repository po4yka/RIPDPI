package com.poyka.ripdpi.data

enum class AppStatus {
    Halted,
    Running,
}

enum class Mode {
    Proxy,
    VPN,
    ;

    companion object {
        fun fromString(name: String): Mode =
            when (name) {
                "proxy" -> Proxy
                "vpn" -> VPN
                else -> throw IllegalArgumentException("Invalid mode: $name")
            }
    }
}
