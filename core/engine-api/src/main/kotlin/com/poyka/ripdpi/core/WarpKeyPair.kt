package com.poyka.ripdpi.core

import kotlinx.serialization.Serializable

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
