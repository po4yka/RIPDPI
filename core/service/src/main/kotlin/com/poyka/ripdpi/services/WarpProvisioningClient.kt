@file:Suppress("TooGenericExceptionCaught", "ThrowsCount", "MagicNumber")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiWarpProvisioningBindings
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.Proxy
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class WarpRegisterDeviceRequest(
    val publicKey: String,
    val privateKey: String? = null,
    val installId: String = "",
    val fcmToken: String = "",
    val tos: String = Instant.now().toString(),
    val clientType: String = WarpClientTypeAndroid,
    val model: String = WarpClientModelPc,
    val locale: String = WarpClientLocale,
    val warpEnabled: Boolean = true,
)

data class WarpProvisioningResult(
    val credentials: WarpCredentials,
    val accountId: String,
    val accountType: String,
    val warpPlus: Boolean,
    val premiumData: Long,
    val quota: Long,
    val license: String? = null,
    val interfaceAddressV4: String? = null,
    val interfaceAddressV6: String? = null,
    val peerPublicKey: String,
    val endpoint: WarpEndpointCacheEntry,
    val reservedBytes: ByteArray,
)

interface WarpProvisioningClient {
    suspend fun register(
        request: WarpRegisterDeviceRequest,
        bootstrapProxy: Proxy? = null,
    ): WarpProvisioningResult

    suspend fun refresh(
        credentials: WarpCredentials,
        bootstrapProxy: Proxy? = null,
    ): WarpProvisioningResult
}

sealed class WarpProvisioningException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    class AuthFailure(
        message: String,
        cause: Throwable? = null,
    ) : WarpProvisioningException(message, cause)

    class MalformedResponse(
        message: String,
        cause: Throwable? = null,
    ) : WarpProvisioningException(message, cause)
}

@Singleton
class DefaultWarpProvisioningClient
    @Inject
    constructor(
        private val nativeBindings: RipDpiWarpProvisioningBindings,
        private val json: Json,
    ) : WarpProvisioningClient {
        private companion object {
            private const val ErrorBodyMaxLength = 256
        }

        override suspend fun register(
            request: WarpRegisterDeviceRequest,
            bootstrapProxy: Proxy?,
        ): WarpProvisioningResult =
            withContext(Dispatchers.IO) {
                executeRequest(
                    request =
                        NativeWarpProvisioningHttpRequest(
                            method = "POST",
                            url = WarpRegistrationBaseUrl,
                            headers = defaultRequestHeaders(),
                            body =
                                json.encodeToString(
                                    CloudflareWarpRegisterRequest(
                                        installId = request.installId,
                                        fcmToken = request.fcmToken,
                                        tos = request.tos,
                                        key = request.publicKey,
                                        type = request.clientType,
                                        model = request.model,
                                        locale = request.locale,
                                        warpEnabled = request.warpEnabled,
                                    ),
                                ),
                            proxy = bootstrapProxy.asNativeProxy(),
                        ),
                    privateKey = request.privateKey,
                    publicKey = request.publicKey,
                    endpointSource = "registration",
                )
            }

        override suspend fun refresh(
            credentials: WarpCredentials,
            bootstrapProxy: Proxy?,
        ): WarpProvisioningResult =
            withContext(Dispatchers.IO) {
                val deviceId = credentials.deviceId.trim()
                require(deviceId.isNotEmpty()) { "WARP credentials missing device id" }
                val token = credentials.accessToken.trim()
                require(token.isNotEmpty()) { "WARP credentials missing access token" }
                executeRequest(
                    request =
                        NativeWarpProvisioningHttpRequest(
                            method = "GET",
                            url = "$WarpRegistrationBaseUrl/$deviceId",
                            headers = defaultRequestHeaders() + (WarpAuthHeader to "Bearer $token"),
                            proxy = bootstrapProxy.asNativeProxy(),
                        ),
                    privateKey = credentials.privateKey,
                    publicKey = credentials.publicKey.orEmpty(),
                    endpointSource = "refresh",
                )
            }

        private fun executeRequest(
            request: NativeWarpProvisioningHttpRequest,
            privateKey: String?,
            publicKey: String,
            endpointSource: String,
        ): WarpProvisioningResult {
            val payload =
                nativeBindings.executeProvisioning(json.encodeToString(request))
                    ?: throw IOException("WARP provisioning bridge returned no response")
            val response =
                try {
                    json.decodeFromString(NativeWarpProvisioningHttpResponse.serializer(), payload)
                } catch (error: Exception) {
                    throw IOException("WARP provisioning bridge returned malformed response", error)
                }
            response.error?.takeIf(String::isNotBlank)?.let { message ->
                throw IOException(message)
            }
            val statusCode = response.statusCode ?: throw IOException("WARP provisioning bridge missing status code")
            val responseBody = response.body.orEmpty()
            if (statusCode !in 200..299) {
                val suffix = responseBody.take(ErrorBodyMaxLength).trim()
                val message =
                    buildString {
                        append("WARP provisioning failed with HTTP ")
                        append(statusCode)
                        if (suffix.isNotEmpty()) {
                            append(": ")
                            append(suffix)
                        }
                    }
                if (statusCode == 401 || statusCode == 403) {
                    throw WarpProvisioningException.AuthFailure(message)
                }
                throw IOException(message)
            }
            val parsed =
                try {
                    json.decodeFromString(CloudflareWarpRegistrationResponse.serializer(), responseBody)
                } catch (error: Exception) {
                    throw WarpProvisioningException.MalformedResponse(
                        "WARP provisioning returned malformed registration data",
                        error,
                    )
                }
            return parsed.toProvisioningResult(
                privateKey = privateKey,
                publicKey = publicKey,
                endpointSource = endpointSource,
            )
        }
    }

@Suppress("ReturnCount")
internal fun reservedBytesFromClientId(clientId: String?): ByteArray {
    if (clientId.isNullOrBlank()) {
        return ByteArray(WarpReservedFieldLength)
    }
    val decoded =
        runCatching {
            java.util.Base64
                .getDecoder()
                .decode(clientId)
        }.getOrNull() ?: return ByteArray(WarpReservedFieldLength)
    return ByteArray(WarpReservedFieldLength) { index -> decoded.getOrElse(index) { 0 } }
}

private fun CloudflareWarpRegistrationResponse.toProvisioningResult(
    privateKey: String?,
    publicKey: String,
    endpointSource: String,
): WarpProvisioningResult {
    val peer =
        config.peers.firstOrNull()
            ?: throw WarpProvisioningException.MalformedResponse("WARP registration missing peer configuration")
    val endpoint =
        parseEndpoint(
            host = peer.endpoint.host,
            ipv4 = peer.endpoint.ipv4,
            ipv6 = peer.endpoint.ipv6,
            source = endpointSource,
        )
    return WarpProvisioningResult(
        credentials =
            WarpCredentials(
                deviceId = id,
                accessToken = token,
                clientId = config.clientId,
                privateKey = privateKey,
                publicKey = publicKey.ifBlank { null },
                peerPublicKey = peer.publicKey,
                interfaceAddressV4 = config.interfaceConfig.addresses.ipv4,
                interfaceAddressV6 = config.interfaceConfig.addresses.ipv6,
            ),
        accountId = account.id,
        accountType = account.accountType,
        warpPlus = account.warpPlus,
        premiumData = account.premiumData,
        quota = account.quota,
        license = account.license,
        interfaceAddressV4 = config.interfaceConfig.addresses.ipv4,
        interfaceAddressV6 = config.interfaceConfig.addresses.ipv6,
        peerPublicKey = peer.publicKey,
        endpoint = endpoint,
        reservedBytes = reservedBytesFromClientId(config.clientId),
    )
}

private fun parseEndpoint(
    host: String,
    ipv4: String? = null,
    ipv6: String? = null,
    source: String,
): WarpEndpointCacheEntry {
    val trimmedHost = host.trim()
    val separatorIndex =
        when {
            trimmedHost.startsWith("[") && trimmedHost.contains("]:") -> trimmedHost.lastIndexOf("]:")
            else -> trimmedHost.lastIndexOf(':')
        }
    if (separatorIndex < 0) {
        throw WarpProvisioningException.MalformedResponse("Invalid WARP endpoint host: $host")
    }
    val endpointHost =
        if (trimmedHost.startsWith("[") && separatorIndex > 0) {
            trimmedHost.substring(1, separatorIndex)
        } else {
            trimmedHost.substring(0, separatorIndex)
        }
    val endpointPort =
        trimmedHost
            .substring(separatorIndex + if (trimmedHost.startsWith("[") && trimmedHost.contains("]:")) 2 else 1)
            .toIntOrNull()
            ?: throw WarpProvisioningException.MalformedResponse("Invalid WARP endpoint port in $host")
    return WarpEndpointCacheEntry(
        networkScopeKey = "",
        host = endpointHost,
        ipv4 = ipv4?.trim()?.takeIf { it.isNotEmpty() },
        ipv6 = ipv6?.trim()?.takeIf { it.isNotEmpty() },
        port = endpointPort,
        source = source,
    )
}

private fun defaultRequestHeaders(): Map<String, String> =
    linkedMapOf(
        "User-Agent" to WarpProvisioningUserAgent,
        "CF-Client-Version" to WarpProvisioningClientVersion,
        "Content-Type" to WarpJsonMediaType,
    )

private fun Proxy?.asNativeProxy(): NativeWarpProvisioningProxyConfig? {
    val socketAddress = (this?.address() as? java.net.InetSocketAddress) ?: return null
    return NativeWarpProvisioningProxyConfig(
        host = socketAddress.hostString,
        port = socketAddress.port,
    )
}

@Serializable
private data class NativeWarpProvisioningHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
    val proxy: NativeWarpProvisioningProxyConfig? = null,
)

@Serializable
private data class NativeWarpProvisioningProxyConfig(
    val host: String,
    val port: Int,
)

@Serializable
private data class NativeWarpProvisioningHttpResponse(
    val statusCode: Int? = null,
    val body: String? = null,
    val error: String? = null,
)

@Serializable
private data class CloudflareWarpRegisterRequest(
    @SerialName("install_id")
    val installId: String,
    @SerialName("fcm_token")
    val fcmToken: String,
    val tos: String,
    val key: String,
    val type: String,
    val model: String,
    val locale: String,
    @SerialName("warp_enabled")
    val warpEnabled: Boolean,
)

@Serializable
private data class CloudflareWarpRegistrationResponse(
    val id: String,
    val token: String,
    val account: CloudflareWarpAccount,
    val config: CloudflareWarpConfig,
)

@Serializable
private data class CloudflareWarpAccount(
    val id: String,
    @SerialName("account_type")
    val accountType: String,
    @SerialName("warp_plus")
    val warpPlus: Boolean,
    @SerialName("premium_data")
    val premiumData: Long = 0L,
    val quota: Long = 0L,
    val license: String? = null,
)

@Serializable
private data class CloudflareWarpConfig(
    @SerialName("client_id")
    val clientId: String? = null,
    @SerialName("interface")
    val interfaceConfig: CloudflareWarpInterfaceConfig,
    val peers: List<CloudflareWarpPeer>,
)

@Serializable
private data class CloudflareWarpInterfaceConfig(
    val addresses: CloudflareWarpAddresses,
)

@Serializable
private data class CloudflareWarpAddresses(
    @SerialName("v4")
    val ipv4: String? = null,
    @SerialName("v6")
    val ipv6: String? = null,
)

@Serializable
private data class CloudflareWarpPeer(
    @SerialName("public_key")
    val publicKey: String,
    val endpoint: CloudflareWarpPeerEndpoint,
)

@Serializable
private data class CloudflareWarpPeerEndpoint(
    val host: String,
    @SerialName("v4")
    val ipv4: String? = null,
    @SerialName("v6")
    val ipv6: String? = null,
)

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WarpProvisioningClientModule {
    @Binds
    @Singleton
    abstract fun bindWarpProvisioningClient(client: DefaultWarpProvisioningClient): WarpProvisioningClient
}

@Module
@InstallIn(SingletonComponent::class)
internal object WarpProvisioningNetworkModule {
    @Provides
    @Singleton
    fun provideWarpProvisioningJson(): Json = Json { ignoreUnknownKeys = true }
}

internal const val WarpRegistrationBaseUrl = "https://api.cloudflareclient.com/v0a4005/reg"
internal const val WarpProvisioningUserAgent = "okhttp/3.12.1"
internal const val WarpProvisioningClientVersion = "a-6.30-3596"
internal const val WarpClientTypeAndroid = "Android"
internal const val WarpClientModelPc = "PC"
internal val WarpClientLocale: String = Locale.US.toLanguageTag().replace('-', '_')
internal const val WarpAuthHeader = "Authorization"
internal const val WarpReservedFieldLength = 3
internal const val WarpJsonMediaType = "application/json; charset=UTF-8"
