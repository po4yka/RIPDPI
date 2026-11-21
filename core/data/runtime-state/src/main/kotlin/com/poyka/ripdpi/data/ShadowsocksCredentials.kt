package com.poyka.ripdpi.data

import java.util.Base64

/** The native relay accepts six AEAD methods and no stream ciphers. */
fun isSupportedShadowsocksMethod(method: String): Boolean = shadowsocksKeyBytes(method.trim().lowercase()) != null

/** Only SS2022 methods carry UDP; legacy AEAD methods are TCP-only. */
fun isShadowsocks2022Method(method: String?): Boolean =
    method?.trim()?.lowercase()?.startsWith("2022-blake3-") == true &&
        isSupportedShadowsocksMethod(method)

/** Match the native key parser before saving or activating a relay. */
fun isValidShadowsocksCredentials(
    method: String?,
    password: String?,
): Boolean {
    val normalizedMethod = method?.trim()?.lowercase()
    val keyBytes = normalizedMethod?.let(::shadowsocksKeyBytes)
    if (keyBytes == null || password.isNullOrBlank()) return false
    val keys =
        if (normalizedMethod == "2022-blake3-chacha20-poly1305") {
            listOf(password)
        } else {
            password.split(':')
        }
    return keyBytes == 0 ||
        keys.all { encoded ->
            runCatching {
                val key = Base64.getDecoder().decode(encoded.trimEnd('='))
                // Upstream accepts absent or partial padding, but requires zero trailing bits.
                key.size == keyBytes && Base64.getEncoder().encodeToString(key).startsWith(encoded)
            }.getOrDefault(false)
        }
}

/** The AEAD methods the native relay accepts, with their key size in bytes (0 = password-derived). */
private val shadowsocksKeyBytesByMethod: Map<String, Int> =
    linkedMapOf(
        "aes-128-gcm" to 0,
        "aes-256-gcm" to 0,
        "chacha20-ietf-poly1305" to 0,
        "2022-blake3-aes-128-gcm" to ShadowsocksAes128KeyBytes,
        "2022-blake3-aes-256-gcm" to ShadowsocksAes256KeyBytes,
        "2022-blake3-chacha20-poly1305" to ShadowsocksAes256KeyBytes,
    )

/** Every method [isSupportedShadowsocksMethod] accepts, in display order. */
val SupportedShadowsocksMethods: List<String> = shadowsocksKeyBytesByMethod.keys.toList()

private fun shadowsocksKeyBytes(method: String): Int? = shadowsocksKeyBytesByMethod[method]

private const val ShadowsocksAes128KeyBytes = 16
private const val ShadowsocksAes256KeyBytes = 32
