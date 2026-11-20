package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.subscription.DnsMorphBridge
import com.poyka.ripdpi.data.subscription.DnsMorphFetcher
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

fun interface NativeDnsMorphFetcherBindings {
    fun fetch(requestJson: String): String?
}

class NativeDnsMorphFetcherNativeBindings
    @Inject
    constructor() : NativeDnsMorphFetcherBindings {
        override fun fetch(requestJson: String): String? {
            RipDpiNativeLoader.ensureLoaded()
            return jniFetch(requestJson)
        }

        private external fun jniFetch(requestJson: String): String?
    }

/** Runs the native DNS-Morph client, which protects its socket and verifies the bridge signature. */
class NativeDnsMorphFetcher
    @Inject
    constructor(
        private val bindings: NativeDnsMorphFetcherBindings,
    ) : DnsMorphFetcher {
        // shortcut: the native call is not cancellable; it ends within its 90 s deadline. Add a cancel handle if a caller needs to stop sooner.
        override suspend fun fetch(bridge: DnsMorphBridge): ByteArray =
            withContext(Dispatchers.IO) {
                val request = NativeDnsMorphRequest(bridge.address, bridge.port, bridge.zone, bridge.publicKey)
                val reply =
                    try {
                        bindings.fetch(RipDpiJson.encodeToString(NativeDnsMorphRequest.serializer(), request))
                    } catch (error: UnsatisfiedLinkError) {
                        throw IOException("native DNS-Morph bridge is unavailable", error)
                    } ?: throw IOException("native DNS-Morph bridge returned no response")
                val response =
                    try {
                        RipDpiJson.decodeFromString(NativeDnsMorphResponse.serializer(), reply)
                    } catch (error: SerializationException) {
                        throw IOException("native DNS-Morph bridge returned malformed JSON", error)
                    }
                response.error?.let { throw IOException(it) }
                val encoded = response.payloadBase64 ?: throw IOException("native DNS-Morph bridge returned no payload")
                try {
                    Base64.getDecoder().decode(encoded)
                } catch (error: IllegalArgumentException) {
                    throw IOException("native DNS-Morph bridge returned invalid payload encoding", error)
                }
            }
    }

@Serializable
private data class NativeDnsMorphRequest(
    val address: String,
    val port: Int,
    val zone: String,
    val publicKey: String,
)

@Serializable
private data class NativeDnsMorphResponse(
    val payloadBase64: String? = null,
    val error: String? = null,
)

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NativeDnsMorphFetcherModule {
    @Binds
    @Singleton
    abstract fun bindDnsMorphFetcher(fetcher: NativeDnsMorphFetcher): DnsMorphFetcher

    @Binds
    abstract fun bindNativeDnsMorphFetcherBindings(
        bindings: NativeDnsMorphFetcherNativeBindings,
    ): NativeDnsMorphFetcherBindings
}
