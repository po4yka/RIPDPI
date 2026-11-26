package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.WarpKeyPairGenerator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Base64 Curve25519 key pair for a WireGuard-family interface. */
data class TunnelKeyPair(
    val privateKey: String,
    val publicKey: String,
) {
    override fun toString(): String = "TunnelKeyPair([REDACTED])"
}

/** Lets `:app` generate interface keys without importing `:core:engine` types. */
interface TunnelKeyPairGenerator {
    suspend fun generate(): TunnelKeyPair
}

internal class DefaultTunnelKeyPairGenerator
    @Inject
    constructor(
        private val generator: WarpKeyPairGenerator,
    ) : TunnelKeyPairGenerator {
        override suspend fun generate(): TunnelKeyPair {
            val pair = generator.generate()
            return TunnelKeyPair(pair.privateKey, pair.publicKey)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TunnelKeyPairGeneratorModule {
    @Binds
    abstract fun bindTunnelKeyPairGenerator(generator: DefaultTunnelKeyPairGenerator): TunnelKeyPairGenerator
}
