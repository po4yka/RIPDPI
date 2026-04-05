package com.poyka.ripdpi.services

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
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
    suspend fun register(request: WarpRegisterDeviceRequest): WarpProvisioningResult

    suspend fun refresh(credentials: WarpCredentials): WarpProvisioningResult
}

@Singleton
class DefaultWarpProvisioningClient
    @Inject
    constructor(
        private val tlsClientFactory: OwnedTlsClientFactory,
        private val json: Json,
    ) : WarpProvisioningClient {
        override suspend fun register(request: WarpRegisterDeviceRequest): WarpProvisioningResult =
            withContext(Dispatchers.IO) {
                val httpRequest =
                    Request
                        .Builder()
                        .url(WarpRegistrationBaseUrl)
                        .post(
                            json
                                .encodeToString(
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
                                ).toRequestBody(WarpJsonMediaType),
                        ).applyDefaultHeaders()
                        .build()
                executeRequest(
                    request = httpRequest,
                    privateKey = request.privateKey,
                    publicKey = request.publicKey,
                    endpointSource = "registration",
                )
            }

        override suspend fun refresh(credentials: WarpCredentials): WarpProvisioningResult =
            withContext(Dispatchers.IO) {
                val deviceId = credentials.deviceId.trim()
                require(deviceId.isNotEmpty()) { "WARP credentials missing device id" }
                val token = credentials.accessToken.trim()
                require(token.isNotEmpty()) { "WARP credentials missing access token" }
                val httpRequest =
                    Request
                        .Builder()
                        .url("$WarpRegistrationBaseUrl/$deviceId")
                        .get()
                        .applyDefaultHeaders()
                        .header(WarpAuthHeader, "Bearer $token")
                        .build()
                executeRequest(
                    request = httpRequest,
                    privateKey = credentials.privateKey,
                    publicKey = credentials.publicKey.orEmpty(),
                    endpointSource = "refresh",
                )
            }

        private fun executeRequest(
            request: Request,
            privateKey: String?,
            publicKey: String,
            endpointSource: String,
        ): WarpProvisioningResult {
            val client = createProvisioningClient()
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val suffix = body.take(256).trim()
                    throw IOException(
                        buildString {
                            append("WARP provisioning failed with HTTP ")
                            append(response.code)
                            if (suffix.isNotEmpty()) {
                                append(": ")
                                append(suffix)
                            }
                        },
                    )
                }
                val parsed = json.decodeFromString(CloudflareWarpRegistrationResponse.serializer(), body)
                return parsed.toProvisioningResult(
                    privateKey = privateKey,
                    publicKey = publicKey,
                    endpointSource = endpointSource,
                )
            }
        }

        private fun createProvisioningClient(): OkHttpClient =
            tlsClientFactory.create(
                forcedTlsVersions = listOf(TlsVersion.TLS_1_2),
            ) {
                connectTimeout(20, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                callTimeout(45, TimeUnit.SECONDS)
            }
    }

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
    val peer = config.peers.firstOrNull() ?: throw IOException("WARP registration missing peer configuration")
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
    require(separatorIndex >= 0) { "Invalid WARP endpoint host: $host" }
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
            ?: throw IOException("Invalid WARP endpoint port in $host")
    return WarpEndpointCacheEntry(
        networkScopeKey = "",
        host = endpointHost,
        ipv4 = ipv4?.trim()?.takeIf { it.isNotEmpty() },
        ipv6 = ipv6?.trim()?.takeIf { it.isNotEmpty() },
        port = endpointPort,
        source = source,
    )
}

private fun Request.Builder.applyDefaultHeaders(): Request.Builder =
    header("User-Agent", WarpProvisioningUserAgent)
        .header("CF-Client-Version", WarpProvisioningClientVersion)
        .header("Content-Type", WarpJsonMediaType.toString())

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
private val WarpJsonMediaType = "application/json; charset=UTF-8".toMediaType()
