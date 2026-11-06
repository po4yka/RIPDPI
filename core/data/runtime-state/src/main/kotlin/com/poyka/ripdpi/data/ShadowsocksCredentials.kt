package com.poyka.ripdpi.data

import java.util.Base64

/** The native relay accepts six AEAD methods and no stream ciphers. */
fun isSupportedShadowsocksMethod(method: String): Boolean = shadowsocksKeyBytes(method.trim().lowercase()) != null

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

private fun shadowsocksKeyBytes(method: String): Int? =
    when (method) {
        "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305" -> 0
        "2022-blake3-aes-128-gcm" -> ShadowsocksAes128KeyBytes
        "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305" -> ShadowsocksAes256KeyBytes
        else -> null
    }

private const val ShadowsocksAes128KeyBytes = 16
private const val ShadowsocksAes256KeyBytes = 32
