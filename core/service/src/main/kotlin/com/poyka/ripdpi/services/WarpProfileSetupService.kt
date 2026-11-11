package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.WarpKeyPairGenerator
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface WarpProfileSetupService {
    suspend fun registerConsumerFree(displayName: String)

    suspend fun importZeroTrustProfile(document: String)
}

@Singleton
class DefaultWarpProfileSetupService
    @Inject
    constructor(
        private val enrollment: WarpEnrollmentOrchestrator,
        private val keyPairGenerator: WarpKeyPairGenerator,
    ) : WarpProfileSetupService {
        override suspend fun registerConsumerFree(displayName: String) {
            require(displayName.isNotBlank()) { "WARP profile name is required" }
            val keyPair = keyPairGenerator.generate()
            currentCoroutineContext().ensureActive()
            enrollment.registerConsumerFree(
                displayName = displayName,
                request = WarpRegisterDeviceRequest(publicKey = keyPair.publicKey, privateKey = keyPair.privateKey),
                profileId = newProfileId(),
            )
        }

        override suspend fun importZeroTrustProfile(document: String) {
            require(
                document.length <= MaxDocumentBytes && document.toByteArray(Charsets.UTF_8).size <= MaxDocumentBytes,
            ) {
                "WARP profile document is too large"
            }
            val imported =
                try {
                    Json.decodeFromString(WarpSetupDocument.serializer(), document)
                } catch (_: SerializationException) {
                    // Decoder messages may include a private key or token from the input.
                    throw IllegalArgumentException("Invalid WARP profile document")
                }
            require(imported.schemaVersion == DocumentSchemaVersion) { "Unsupported WARP profile document version" }
            currentCoroutineContext().ensureActive()
            enrollment.importZeroTrustProfile(
                WarpZeroTrustImportRequest(
                    profileId = imported.profileId ?: newProfileId(),
                    displayName = imported.displayName,
                    organization = imported.organization,
                    deviceId = imported.deviceId,
                    accessToken = imported.accessToken,
                    refreshToken = imported.refreshToken,
                    clientId = imported.clientId,
                    privateKey = imported.privateKey,
                    publicKey = imported.publicKey,
                    peerPublicKey = imported.peerPublicKey,
                    interfaceAddressV4 = imported.interfaceAddressV4,
                    interfaceAddressV6 = imported.interfaceAddressV6,
                    endpoint =
                        WarpEndpointCacheEntry(
                            networkScopeKey = GlobalWarpEndpointScopeKey,
                            host = imported.endpoint.host,
                            ipv4 = imported.endpoint.ipv4,
                            ipv6 = imported.endpoint.ipv6,
                            port = imported.endpoint.port,
                            source = "zero_trust_import",
                        ),
                ),
            )
        }

        private fun newProfileId(): String = "warp-${UUID.randomUUID()}"

        private companion object {
            const val DocumentSchemaVersion = 1
            const val MaxDocumentBytes = 64 * 1024
        }
    }

@Serializable
private class WarpSetupDocument(
    val schemaVersion: Int,
    val profileId: String? = null,
    val displayName: String,
    val organization: String,
    val deviceId: String,
    val accessToken: String,
    val privateKey: String,
    val publicKey: String,
    val peerPublicKey: String,
    val interfaceAddressV4: String,
    val endpoint: WarpSetupDocumentEndpoint,
    val clientId: String? = null,
    val interfaceAddressV6: String? = null,
    val refreshToken: String? = null,
)

@Serializable
private class WarpSetupDocumentEndpoint(
    val host: String,
    val port: Int,
    val ipv4: String? = null,
    val ipv6: String? = null,
)

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WarpProfileSetupModule {
    @Binds
    abstract fun bindSetupService(service: DefaultWarpProfileSetupService): WarpProfileSetupService
}
