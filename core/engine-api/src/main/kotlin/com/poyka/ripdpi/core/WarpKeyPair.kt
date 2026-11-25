package com.poyka.ripdpi.core

import kotlinx.serialization.Serializable

/** A base64 WireGuard X25519 key pair; WARP registration and the AmneziaWG editor both use it. */
@Serializable
data class WarpKeyPair(
    val privateKey: String,
    val publicKey: String,
) {
    override fun toString(): String = "WarpKeyPair([REDACTED])"
}

interface WarpKeyPairGenerator {
    suspend fun generate(): WarpKeyPair
}
