package com.poyka.ripdpi.data

internal fun validateTuicProfile(profile: ProxyProfile.Tuic): Boolean {
    require(profile.server.isNotBlank()) { "TUIC requires a server hostname" }
    require(profile.serverPort in TuicPortRange) { "TUIC requires a valid server port" }
    require((profile.serverName ?: profile.server).isNotBlank()) { "TUIC requires a TLS server name" }
    require(isValidTuicUuid(profile.uuid)) { "TUIC requires a valid UUID" }
    require(profile.password.isNotBlank()) { "TUIC requires an authentication password" }
    require(profile.congestionControl.trim().lowercase() in setOf("bbr", "cubic", "new_reno", "newreno")) {
        "TUIC congestion control is unsupported"
    }
    require(
        profile.alpn.isEmpty() ||
            profile.alpn.split(',').all { it.trim().toByteArray(Charsets.UTF_8).size in TuicAlpnByteLengthRange },
    ) { "TUIC ALPN entries must be 1-255 bytes" }
    return true
}

private val TuicPortRange = 1..65535
private val TuicAlpnByteLengthRange = 1..255
