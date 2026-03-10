package com.poyka.ripdpi.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface NetworkMetadataProvider {
    suspend fun captureSnapshot(): NetworkSnapshotModel
}

@Singleton
class AndroidNetworkMetadataProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : NetworkMetadataProvider {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override suspend fun captureSnapshot(): NetworkSnapshotModel {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val linkProperties = connectivityManager.getLinkProperties(network)
        val publicIpInfo = fetchPublicIpInfo()

        return NetworkSnapshotModel(
            transport = resolveTransport(capabilities),
            capabilities = resolveCapabilities(capabilities),
            dnsServers = linkProperties?.dnsServers?.map { it.hostAddress.orEmpty() }.orEmpty(),
            privateDnsMode = resolvePrivateDnsMode(linkProperties),
            mtu = linkProperties?.mtu?.takeIf { it > 0 },
            localAddresses = resolveLocalAddresses(linkProperties),
            publicIp = publicIpInfo?.ip,
            publicAsn = publicIpInfo?.asn,
            captivePortalDetected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            networkValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            capturedAt = System.currentTimeMillis(),
        )
    }

    private fun resolveTransport(capabilities: NetworkCapabilities?): String =
        when {
            capabilities == null -> "unavailable"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }

    private fun resolveCapabilities(capabilities: NetworkCapabilities?): List<String> {
        if (capabilities == null) {
            return emptyList()
        }
        val values = mutableListOf<String>()
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            values += "internet"
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            values += "not_metered"
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            values += "not_vpn"
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            values += "validated"
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            values += "captive_portal"
        }
        return values
    }

    private fun resolvePrivateDnsMode(linkProperties: LinkProperties?): String {
        val privateDnsServerName = linkProperties?.privateDnsServerName
        return when {
            privateDnsServerName.isNullOrBlank() -> "system"
            else -> privateDnsServerName
        }
    }

    private fun resolveLocalAddresses(linkProperties: LinkProperties?): List<String> =
        linkProperties?.linkAddresses?.map { it.address.hostAddress.orEmpty() }.orEmpty()

    private suspend fun fetchPublicIpInfo(): PublicIpInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection =
                    (URL("https://api.ipify.org?format=json").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3_000
                        readTimeout = 3_000
                        requestMethod = "GET"
                    }
                connection.inputStream.bufferedReader().use { reader ->
                    val json = Json.decodeFromString(PublicIpResponse.serializer(), reader.readText())
                    PublicIpInfo(ip = json.ip, asn = null)
                }
            }.getOrNull()
        }
}

private data class PublicIpInfo(
    val ip: String,
    val asn: String?,
)

@Serializable
private data class PublicIpResponse(
    @SerialName("ip") val ip: String,
)

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkMetadataProviderModule {
    @Binds
    @Singleton
    abstract fun bindNetworkMetadataProvider(
        provider: AndroidNetworkMetadataProvider,
    ): NetworkMetadataProvider
}
