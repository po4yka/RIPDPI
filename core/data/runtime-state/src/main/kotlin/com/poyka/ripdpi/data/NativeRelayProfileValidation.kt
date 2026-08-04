package com.poyka.ripdpi.data

import java.util.Base64

/**
 * Validates profiles before they are persisted as native relay records.
 *
 * Subscription and Xray import parsers are intentionally tolerant enough to show
 * a parsed preview, but activation must fail before any durable
 * group/profile/settings write when the profile cannot build a native relay
 * backend.
 *
 * @return `true` when [profile] is a native-relay profile and was validated,
 *     `false` for non-native relay profile kinds.
 */
fun validateNativeRelayProfile(profile: ProxyProfile): Boolean =
    when (profile) {
        is ProxyProfile.VlessReality -> {
            validateVlessRealityProfileFields(
                server = profile.server,
                serverPort = profile.serverPort,
                uuid = profile.uuid,
                serverName = profile.serverName,
                realityPublicKey = profile.realityPublicKey,
                realityShortId = profile.realityShortId,
            )
            if (profile.xhttpPath != null || profile.xhttpHost != null) {
                validateRelayXhttpMode(profile.xhttpMode)
            }
            true
        }

        is ProxyProfile.Vless -> {
            require(profile.server.isNotBlank()) { "VLESS requires a server hostname" }
            require(profile.serverPort in RelayPortRange) { "VLESS requires a valid server port" }
            require(!profile.serverName.isNullOrBlank()) { "VLESS requires a TLS server name" }
            require(isValidVlessUuid(profile.uuid)) { "VLESS requires a valid UUID" }
            require(profile.xhttpPath != null || profile.xhttpHost != null) {
                "VLESS native backend supports only xHTTP transport"
            }
            validateRelayXhttpMode(profile.xhttpMode)
            true
        }

        is ProxyProfile.Hysteria2 -> {
            require(profile.server.isNotBlank()) { "Hysteria2 requires a server hostname" }
            require(profile.serverPort in RelayPortRange) { "Hysteria2 requires a valid server port" }
            require(profile.password.isNotBlank()) { "Hysteria2 requires an authentication password" }
            true
        }

        is ProxyProfile.AnyTls,
        is ProxyProfile.Mieru,
        is ProxyProfile.Shadowsocks,
        is ProxyProfile.Ssh,
        is ProxyProfile.Trojan,
        -> {
            true
        }

        else -> {
            false
        }
    }

/**
 * Non-throwing variant of [validateNativeRelayProfile] for UI callers that must
 * surface an invalid import instead of crashing.
 *
 * [validateNativeRelayProfile] signals invalid field material by throwing
 * `IllegalArgumentException` (via `require`), which a coroutine caller cannot
 * branch on without a try/catch. This wraps it in a [Result] so the caller can
 * `fold` over success (carrying the is-native-relay boolean) versus failure.
 */
fun validateNativeRelayProfileResult(profile: ProxyProfile): Result<Boolean> =
    runCatching { validateNativeRelayProfile(profile) }

fun validateVlessRealityProfileFields(
    server: String,
    serverPort: Int,
    uuid: String,
    serverName: String,
    realityPublicKey: String,
    realityShortId: String,
) {
    validateVlessRealityEndpointFields(
        server = server,
        serverPort = serverPort,
        serverName = serverName,
        realityPublicKey = realityPublicKey,
        realityShortId = realityShortId,
    )
    require(isValidVlessUuid(uuid)) { "VLESS Reality requires a valid UUID" }
}

fun validateVlessRealityEndpointFields(
    server: String,
    serverPort: Int,
    serverName: String,
    realityPublicKey: String,
    realityShortId: String,
) {
    require(server.isNotBlank()) { "VLESS Reality requires a server hostname" }
    require(serverPort in RelayPortRange) { "VLESS Reality requires a valid server port" }
    require(serverName.isNotBlank()) { "VLESS Reality requires a TLS server name" }
    require(isValidRealityPublicKey(realityPublicKey)) {
        "VLESS Reality public key must be base64-encoded 32-byte X25519 key material"
    }
    require(isValidRealityShortId(realityShortId)) {
        "VLESS Reality short ID must be hex-encoded and at most 8 bytes"
    }
}

fun validateRelayXhttpMode(value: String) {
    require(isValidRelayXhttpMode(value)) {
        "xHTTP mode must be 'auto', 'stream-up', 'stream-one', or empty"
    }
}

fun isValidRelayXhttpMode(value: String): Boolean =
    when (value.trim()) {
        "",
        RelayXhttpModeAuto,
        RelayXhttpModeStreamUp,
        RelayXhttpModeStreamOne,
        -> true

        else -> false
    }

fun isValidVlessUuid(value: String?): Boolean {
    val normalized = value?.trim()?.filterNot { it == '-' } ?: return false
    return normalized.length == VlessUuidHexLength && normalized.all(Char::isHexDigit)
}

fun isValidRealityPublicKey(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return false
    }
    return listOf(
        Base64.getDecoder(),
        Base64.getUrlDecoder(),
    ).any { decoder ->
        runCatching { decoder.decode(trimmed) }
            .getOrNull()
            ?.size == RealityPublicKeyByteLength
    }
}

fun isValidRealityShortId(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return true
    }
    return trimmed.length <= RealityShortIdMaxHexLength &&
        trimmed.length % 2 == 0 &&
        trimmed.all(Char::isHexDigit)
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private val RelayPortRange = 1..65535

private const val VlessUuidHexLength = 32
private const val RealityPublicKeyByteLength = 32
private const val RealityShortIdMaxHexLength = 16
