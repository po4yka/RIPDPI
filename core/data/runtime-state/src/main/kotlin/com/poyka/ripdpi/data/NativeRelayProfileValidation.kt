package com.poyka.ripdpi.data

import java.util.Base64

internal const val MaxBoundedXhttpConnections = 4
internal const val Hysteria2SalamanderMinKeyBytes = 4

/** Capability-only check used before import previews and durable writes. */
fun nativeVlessProfileRejection(profile: ProxyProfile): String? =
    when (profile) {
        is ProxyProfile.VlessReality -> {
            NativeVlessCapabilities.rejection(
                RelaySecurityLayerReality,
                if (profile.xhttpPath != null ||
                    profile.xhttpHost != null
                ) {
                    RelayVlessTransportXhttp
                } else {
                    RelayVlessTransportRealityTcp
                },
                profile.flow,
                profile.xhttpMode,
            )
        }

        is ProxyProfile.Vless -> {
            NativeVlessCapabilities.rejection(
                RelaySecurityLayerTls,
                if (profile.xhttpPath != null ||
                    profile.xhttpHost != null
                ) {
                    RelayVlessTransportXhttp
                } else {
                    RelayVlessTransportRealityTcp
                },
                profile.flow,
                profile.xhttpMode,
            )
        }

        else -> {
            null
        }
    }

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
            requireSupportedXhttpPool(
                maxConnections = profile.xhttpMaxConnections,
                mode = profile.xhttpMode,
                path = profile.xhttpPath,
                host = profile.xhttpHost,
            )
            require(nativeVlessProfileRejection(profile) == null) { nativeVlessProfileRejection(profile).orEmpty() }
            true
        }

        is ProxyProfile.Vless -> {
            require(profile.server.isNotBlank()) { "VLESS requires a server hostname" }
            require(profile.serverPort in RelayPortRange) { "VLESS requires a valid server port" }
            require(!profile.serverName.isNullOrBlank()) { "VLESS requires a TLS server name" }
            require(isValidVlessUuid(profile.uuid)) { "VLESS requires a valid UUID" }
            requireSupportedXhttpPool(
                maxConnections = profile.xhttpMaxConnections,
                mode = profile.xhttpMode,
                path = profile.xhttpPath,
                host = profile.xhttpHost,
            )
            require(nativeVlessProfileRejection(profile) == null) { nativeVlessProfileRejection(profile).orEmpty() }
            true
        }

        is ProxyProfile.Hysteria2 -> {
            require(profile.server.isNotBlank()) { "Hysteria2 requires a server hostname" }
            require(profile.serverPort in RelayPortRange) { "Hysteria2 requires a valid server port" }
            require(profile.password.isNotBlank()) { "Hysteria2 requires an authentication password" }
            val obfsKey = profile.obfsPassword.orEmpty()
            require(obfsKey.isBlank() || obfsKey.toByteArray().size >= Hysteria2SalamanderMinKeyBytes) {
                "Hysteria2 Salamander key must be at least 4 bytes"
            }
            true
        }

        is ProxyProfile.Naive -> {
            require(profile.server.isNotBlank()) { "NaiveProxy requires a server hostname" }
            require(profile.serverPort in RelayPortRange) { "NaiveProxy requires a valid server port" }
            require(profile.username.isNotBlank()) { "NaiveProxy requires a username" }
            require(
                profile.concurrency in 1..MaxNaiveConcurrency,
            ) { "NaiveProxy concurrency must be 1 to $MaxNaiveConcurrency" }
            require(profile.serverPinSha256.isEmpty() || (!profile.quic && profile.concurrency == 1)) {
                "NaiveProxy HTTP/3 and concurrency cannot use a certificate pin"
            }
            true
        }

        is ProxyProfile.Shadowsocks -> {
            require(profile.server.isNotBlank()) { "Shadowsocks requires a server hostname" }
            require(profile.serverPort in RelayPortRange) { "Shadowsocks requires a valid server port" }
            require(isValidShadowsocksCredentials(profile.method, profile.password)) {
                "Shadowsocks requires a supported method and valid key material"
            }
            true
        }

        is ProxyProfile.Mieru -> {
            validateMieruProfileFields(profile)
            true
        }

        is ProxyProfile.Ssh -> {
            validateSshProfile(profile)
        }

        is ProxyProfile.AnyTls,
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
    value.trim().ifEmpty { RelayXhttpModeAuto } in NativeVlessCapabilities.xhttpModes(RelaySecurityLayerReality)

// Each validator accepts exactly what reaches Rust: the id is passed unchanged and native parse_uuid
// (Xray rule) takes a 32 or 36 byte UUID, or 1..=30 UTF-8 bytes that it maps to a UUIDv5. The relay config
// normalization trims the Reality key and short ID.
fun isValidVlessUuid(value: String?): Boolean =
    value != null &&
        when (value.toByteArray(Charsets.UTF_8).size) {
            in VlessUuidMinTextBytes..VlessUuidMaxTextBytes -> VlessUuidPattern.matches(value)
            in 1..VlessUuidMaxMappedBytes -> true
            else -> false
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

// A 32 or 36 byte id must be the simple (32 hex) or hyphenated 8-4-4-4-12 form.
private const val VlessUuidMinTextBytes = 32
private const val VlessUuidMaxTextBytes = 36
private const val VlessUuidMaxMappedBytes = 30
private val VlessUuidPattern = Regex("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
private const val RealityPublicKeyByteLength = 32
private const val RealityShortIdMaxHexLength = 16

private fun validateMieruProfileFields(profile: ProxyProfile.Mieru) {
    require(profile.server.isNotBlank()) { "Mieru requires a server hostname" }
    require(profile.serverPort in RelayPortRange) { "Mieru requires a valid server port" }
    require(isValidRelayMieruUsername(profile.username)) { "Mieru requires a user name of at most 64 bytes" }
    require(profile.password.isNotBlank()) { "Mieru requires a password" }
    require(profile.protocol == RelayMieruProtocolTcp) { "Mieru supports only the TCP carrier" }
}

private fun requireSupportedXhttpPool(
    maxConnections: Int?,
    mode: String,
    path: String?,
    host: String?,
) {
    if (maxConnections == null) return
    require(
        maxConnections in 1..MaxBoundedXhttpConnections &&
            mode == RelayXhttpModeStreamUp &&
            (path != null || host != null),
    ) { "Unsupported XHTTP pool settings" }
}
