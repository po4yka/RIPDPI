package com.poyka.ripdpi.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

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

@Singleton
class NativeWarpKeyPairGenerator
    @Inject
    constructor() : WarpKeyPairGenerator {
        override suspend fun generate(): WarpKeyPair =
            withContext(Dispatchers.IO) {
                val payload =
                    RipDpiWarpKeyPairNativeBindings.generate()
                        ?: throw IOException("Could not generate WARP keys")
                Json.decodeFromString(WarpKeyPair.serializer(), payload)
            }
    }

internal object RipDpiWarpKeyPairNativeBindings {
    fun generate(): String? {
        RipDpiWarpNativeLoader.ensureLoaded()
        return jniGenerateKeyPair()
    }

    private external fun jniGenerateKeyPair(): String?
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WarpKeyPairModule {
    @Binds
    abstract fun bindGenerator(generator: NativeWarpKeyPairGenerator): WarpKeyPairGenerator
}
