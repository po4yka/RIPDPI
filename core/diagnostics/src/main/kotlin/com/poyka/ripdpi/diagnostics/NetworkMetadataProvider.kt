package com.poyka.ripdpi.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

interface NetworkMetadataProvider {
    suspend fun captureSnapshot(includePublicIp: Boolean = false): NetworkSnapshotModel
}

data class PublicIpInfo(
    val ip: String,
    val asn: String?,
)

interface PublicIpInfoResolver {
    suspend fun resolve(): PublicIpInfo?
}

@Singleton
class HttpPublicIpInfoResolver
    @Inject
    constructor() : PublicIpInfoResolver {
        companion object {
            private const val PublicIpTimeoutMs = 3_000
        }

        private val lenientJson =
            Json {
                ignoreUnknownKeys = true
            }

        override suspend fun resolve(): PublicIpInfo? =
            withContext(Dispatchers.IO) {
                resolveFromIpInfo() ?: resolveFromIpify()
            }

        private fun resolveFromIpInfo(): PublicIpInfo? =
            runCatching {
                val connection =
                    (URL("https://ipinfo.io/json").openConnection() as HttpURLConnection).apply {
                        connectTimeout = PublicIpTimeoutMs
                        readTimeout = PublicIpTimeoutMs
                        requestMethod = "GET"
                    }
                connection.inputStream.bufferedReader().use { reader ->
                    val json = lenientJson.decodeFromString(IpInfoResponse.serializer(), reader.readText())
                    PublicIpInfo(ip = json.ip, asn = formatAsn(json.org))
                }
            }.getOrNull()

        private fun resolveFromIpify(): PublicIpInfo? =
            runCatching {
                val connection =
                    (URL("https://api.ipify.org?format=json").openConnection() as HttpURLConnection).apply {
                        connectTimeout = PublicIpTimeoutMs
                        readTimeout = PublicIpTimeoutMs
                        requestMethod = "GET"
                    }
                connection.inputStream.bufferedReader().use { reader ->
                    val json = lenientJson.decodeFromString(PublicIpResponse.serializer(), reader.readText())
                    PublicIpInfo(ip = json.ip, asn = null)
                }
            }.getOrNull()

        private fun formatAsn(org: String?): String? {
            if (org.isNullOrBlank()) return null
            // ipinfo.io returns org as "AS12389 Rostelecom" -- keep the full value
            // but strip the raw IP that might leak through other fields
            return org.trim().takeIf { it.startsWith("AS", ignoreCase = true) }
        }
    }

@Singleton
class AndroidNetworkMetadataProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val publicIpInfoResolver: PublicIpInfoResolver,
    ) : NetworkMetadataProvider {
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        @SuppressLint("MissingPermission")
        override suspend fun captureSnapshot(includePublicIp: Boolean): NetworkSnapshotModel {
            val network = activeNetworkOrNull()
            val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
            val linkProperties = network?.let(connectivityManager::getLinkProperties)
            val publicIpInfo = if (includePublicIp) publicIpInfoResolver.resolve() else null

            return NetworkSnapshotModel(
                transport = resolveTransport(capabilities),
                capabilities = resolveCapabilities(capabilities),
                dnsServers = linkProperties?.dnsServers?.map { it.hostAddress.orEmpty() }.orEmpty(),
                privateDnsMode = resolvePrivateDnsMode(linkProperties),
                mtu = resolveMtu(linkProperties),
                localAddresses = resolveLocalAddresses(linkProperties),
                publicIp = publicIpInfo?.ip,
                publicAsn = publicIpInfo?.asn,
                captivePortalDetected =
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
                networkValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                wifiDetails = resolveWifiDetails(capabilities),
                cellularDetails = resolveCellularDetails(capabilities),
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

        @SuppressLint("MissingPermission")
        private fun activeNetworkOrNull() =
            if (hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
                connectivityManager.activeNetwork
            } else {
                null
            }

        private fun resolvePrivateDnsMode(linkProperties: LinkProperties?): String {
            val privateDnsServerName =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    linkProperties?.privateDnsServerName
                } else {
                    null
                }
            return when {
                privateDnsServerName.isNullOrBlank() -> "system"
                else -> privateDnsServerName
            }
        }

        private fun resolveMtu(linkProperties: LinkProperties?): Int? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                linkProperties?.mtu?.takeIf { it > 0 }
            } else {
                null
            }

        private fun resolveLocalAddresses(linkProperties: LinkProperties?): List<String> =
            linkProperties?.linkAddresses?.map { it.address.hostAddress.orEmpty() }.orEmpty()

        @Suppress("DEPRECATION") // connectionInfo/dhcpInfo: no modern replacement; API 29+ path already preferred
        private fun resolveWifiDetails(capabilities: NetworkCapabilities?): WifiNetworkDetails? {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                return null
            }
            val wifiInfo =
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> capabilities.transportInfo as? WifiInfo
                    else -> null
                } ?: wifiManager?.connectionInfo
            val dhcpInfo = wifiManager?.dhcpInfo
            return WifiNetworkDetails(
                ssid = sanitizeWifiValue(wifiInfo?.ssid),
                bssid = sanitizeWifiValue(wifiInfo?.bssid),
                hiddenSsid = wifiInfo?.hiddenSSID,
                frequencyMhz = wifiInfo?.frequency?.takeIf { it > 0 },
                band = describeWifiBand(wifiInfo?.frequency),
                channelWidth = describeWifiChannelWidth(wifiInfo),
                wifiStandard = describeWifiStandard(wifiInfo),
                rssiDbm = wifiInfo?.rssi?.takeIf { it in RssiMinDbm..RssiMaxDbm },
                linkSpeedMbps = wifiInfo?.linkSpeed?.takeIf { it > 0 },
                rxLinkSpeedMbps =
                    if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.Q
                    ) {
                        wifiInfo?.rxLinkSpeedMbps?.takeIf { it > 0 }
                    } else {
                        null
                    },
                txLinkSpeedMbps =
                    if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.Q
                    ) {
                        wifiInfo?.txLinkSpeedMbps?.takeIf { it > 0 }
                    } else {
                        null
                    },
                networkId = wifiInfo?.networkId?.takeIf { it >= 0 },
                isPasspoint = wifiInfo?.let { invokeBoolean(it, "isPasspointAp") },
                isOsuAp = wifiInfo?.let { invokeBoolean(it, "isOsuAp") },
                gateway = dhcpInfo?.gateway?.takeIf { it != 0 }?.let(::intToIpv4Address),
                dhcpServer = dhcpInfo?.serverAddress?.takeIf { it != 0 }?.let(::intToIpv4Address),
                ipAddress = dhcpInfo?.ipAddress?.takeIf { it != 0 }?.let(::intToIpv4Address),
                subnetMask = dhcpInfo?.netmask?.takeIf { it != 0 }?.let(::intToIpv4Address),
                leaseDurationSeconds = dhcpInfo?.leaseDuration?.takeIf { it > 0 },
            )
        }

        @SuppressLint("MissingPermission")
        private fun resolveCellularDetails(capabilities: NetworkCapabilities?): CellularNetworkDetails? {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) != true) return null
            val telephony = telephonyManager ?: return CellularNetworkDetails()
            return resolveCellularDetailsFromTelephony(telephony)
        }

        @SuppressLint("MissingPermission")
        private fun resolveCellularDetailsFromTelephony(telephony: TelephonyManager): CellularNetworkDetails {
            val canReadPhoneState = hasPhoneStatePermission()
            val canReadServiceState = hasServiceStatePermission()
            val serviceState =
                if (canReadServiceState) {
                    runCatching { telephony.serviceState }.getOrNull()
                } else {
                    null
                }
            val signalStrength =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && canReadPhoneState) {
                    runCatching { telephony.signalStrength }.getOrNull()
                } else {
                    null
                }
            return CellularNetworkDetails(
                carrierName =
                    sanitizeTelephonyValue(
                        simCarrierNameOrNull(telephony) ?: telephony.networkOperatorName,
                    ),
                simOperatorName = sanitizeTelephonyValue(telephony.simOperatorName),
                networkOperatorName = sanitizeTelephonyValue(telephony.networkOperatorName),
                networkCountryIso = sanitizeTelephonyValue(telephony.networkCountryIso),
                simCountryIso = sanitizeTelephonyValue(telephony.simCountryIso),
                operatorCode = sanitizeTelephonyValue(telephony.networkOperator),
                simOperatorCode = sanitizeTelephonyValue(telephony.simOperator),
                dataNetworkType = describeMobileNetworkType(readDataNetworkType(telephony, canReadPhoneState)),
                voiceNetworkType = describeMobileNetworkType(readVoiceNetworkType(telephony, canReadPhoneState)),
                dataState = describeDataState(telephony.dataState),
                serviceState = describeServiceState(serviceState),
                isNetworkRoaming = runCatching { telephony.isNetworkRoaming }.getOrNull(),
                carrierId = invokeInt(telephony, "getCarrierId")?.takeIf { it >= 0 },
                simCarrierId = invokeInt(telephony, "getSimCarrierId")?.takeIf { it >= 0 },
                signalLevel = signalStrength?.level,
                signalDbm = resolveSignalDbm(signalStrength),
            )
        }

        private fun simCarrierNameOrNull(telephony: TelephonyManager): String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telephony.simCarrierIdName?.toString()
            } else {
                null
            }

        @SuppressLint("MissingPermission")
        private fun readDataNetworkType(
            telephony: TelephonyManager,
            canReadPhoneState: Boolean,
        ): Int =
            if (canReadPhoneState) {
                runCatching { telephony.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
            } else {
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            }

        @SuppressLint("MissingPermission")
        private fun readVoiceNetworkType(
            telephony: TelephonyManager,
            canReadPhoneState: Boolean,
        ): Int =
            if (canReadPhoneState) {
                runCatching { telephony.voiceNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
            } else {
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            }

        private fun resolveSignalDbm(signalStrength: android.telephony.SignalStrength?): Int? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm
            } else {
                null
            }

        private fun sanitizeWifiValue(value: String?): String {
            val normalized = value?.trim()?.removePrefix("\"")?.removeSuffix("\"")
            return when {
                normalized.isNullOrBlank() -> "unknown"
                normalized.equals("<unknown ssid>", ignoreCase = true) -> "unknown"
                normalized == "02:00:00:00:00:00" -> "unknown"
                else -> normalized
            }
        }

        private fun sanitizeTelephonyValue(value: String?): String =
            value?.trim()?.takeIf { it.isNotBlank() } ?: "unknown"

        private fun describeWifiBand(frequencyMhz: Int?): String =
            when {
                frequencyMhz == null -> "unknown"
                frequencyMhz in WifiBand24GhzMin..WifiBand24GhzMax -> "2.4 GHz"
                frequencyMhz in WifiBand5GhzMin..WifiBand5GhzMax -> "5 GHz"
                frequencyMhz in WifiBand6GhzMin..WifiBand6GhzMax -> "6 GHz"
                else -> "$frequencyMhz MHz"
            }

        private fun describeWifiChannelWidth(wifiInfo: WifiInfo?): String {
            val width = wifiInfo?.let { invokeInt(it, "getChannelWidth") } ?: return "unknown"
            return when (width) {
                WifiChannelWidth20Mhz -> "20 MHz"
                WifiChannelWidth40Mhz -> "40 MHz"
                WifiChannelWidth80Mhz -> "80 MHz"
                WifiChannelWidth160Mhz -> "160 MHz"
                WifiChannelWidth80Plus80Mhz -> "80+80 MHz"
                WifiChannelWidth320Mhz -> "320 MHz"
                else -> "unknown"
            }
        }

        private fun describeWifiStandard(wifiInfo: WifiInfo?): String {
            val standard = wifiInfo?.let { invokeInt(it, "getWifiStandard") } ?: return "unknown"
            return when (standard) {
                WifiStandardLegacy -> "legacy"
                WifiStandard80211n -> "802.11n"
                WifiStandard80211ac -> "802.11ac"
                WifiStandard80211ax -> "802.11ax"
                WifiStandard80211ad -> "802.11ad"
                WifiStandard80211be -> "802.11be"
                else -> "unknown"
            }
        }

        private fun invokeBoolean(
            target: Any,
            methodName: String,
        ): Boolean? = runCatching { target.javaClass.getMethod(methodName).invoke(target) as? Boolean }.getOrNull()

        private fun invokeInt(
            target: Any,
            methodName: String,
        ): Int? = runCatching { target.javaClass.getMethod(methodName).invoke(target) as Int }.getOrNull()

        private fun hasPhoneStatePermission(): Boolean =
            hasPermission(Manifest.permission.READ_PHONE_STATE) ||
                hasPermission("android.permission.READ_BASIC_PHONE_STATE")

        private fun hasServiceStatePermission(): Boolean =
            hasPermission(Manifest.permission.READ_PHONE_STATE) &&
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

        private fun hasPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        @Suppress("DEPRECATION") // legacy CDMA/2G/3G constants: no replacement, networks are EOL
        private fun describeMobileNetworkType(type: Int): String =
            when (type) {
                TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
                TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
                TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
                TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
                TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
                TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
                TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
                TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
                TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
                TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
                TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
                TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
                TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
                TelephonyManager.NETWORK_TYPE_NR -> "NR"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown"
                else -> type.toString()
            }

        private fun describeDataState(dataState: Int): String =
            when (dataState) {
                TelephonyManager.DATA_CONNECTED -> "connected"
                TelephonyManager.DATA_CONNECTING -> "connecting"
                TelephonyManager.DATA_DISCONNECTED -> "disconnected"
                TelephonyManager.DATA_SUSPENDED -> "suspended"
                else -> "unknown"
            }

        private fun describeServiceState(state: ServiceState?): String =
            when (state?.state) {
                ServiceState.STATE_IN_SERVICE -> "in_service"
                ServiceState.STATE_OUT_OF_SERVICE -> "out_of_service"
                ServiceState.STATE_EMERGENCY_ONLY -> "emergency_only"
                ServiceState.STATE_POWER_OFF -> "power_off"
                else -> "unknown"
            }

        private fun intToIpv4Address(value: Int): String =
            InetAddress
                .getByAddress(
                    byteArrayOf(
                        (value and ByteMask).toByte(),
                        ((value shr BitsPerByte) and ByteMask).toByte(),
                        ((value shr TwoBytesShift) and ByteMask).toByte(),
                        ((value shr ThreeBytesShift) and ByteMask).toByte(),
                    ),
                ).hostAddress
                .orEmpty()

        companion object {
            // RSSI validity range
            private const val RssiMinDbm = -127
            private const val RssiMaxDbm = 0

            // WiFi frequency bands (MHz)
            private const val WifiBand24GhzMin = 2400
            private const val WifiBand24GhzMax = 2500
            private const val WifiBand5GhzMin = 4900
            private const val WifiBand5GhzMax = 5900
            private const val WifiBand6GhzMin = 5925
            private const val WifiBand6GhzMax = 7125

            // WiFi channel width codes (WifiInfo.CHANNEL_WIDTH_*)
            private const val WifiChannelWidth20Mhz = 0
            private const val WifiChannelWidth40Mhz = 1
            private const val WifiChannelWidth80Mhz = 2
            private const val WifiChannelWidth160Mhz = 3
            private const val WifiChannelWidth80Plus80Mhz = 4
            private const val WifiChannelWidth320Mhz = 5

            // WiFi standard codes (WifiInfo.WIFI_STANDARD_*)
            private const val WifiStandardLegacy = 1
            private const val WifiStandard80211n = 4
            private const val WifiStandard80211ac = 5
            private const val WifiStandard80211ax = 6
            private const val WifiStandard80211ad = 7
            private const val WifiStandard80211be = 8

            // IPv4 byte-extraction masks and shifts
            private const val ByteMask = 0xff
            private const val BitsPerByte = 8
            private const val TwoBytesShift = 16
            private const val ThreeBytesShift = 24
        }
    }

@Serializable
private data class PublicIpResponse(
    @SerialName("ip") val ip: String,
)

@Serializable
private data class IpInfoResponse(
    @SerialName("ip") val ip: String,
    @SerialName("org") val org: String? = null,
)

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkMetadataProviderModule {
    @Binds
    @Singleton
    abstract fun bindNetworkMetadataProvider(provider: AndroidNetworkMetadataProvider): NetworkMetadataProvider
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PublicIpInfoResolverModule {
    @Binds
    @Singleton
    abstract fun bindPublicIpInfoResolver(resolver: HttpPublicIpInfoResolver): PublicIpInfoResolver
}
