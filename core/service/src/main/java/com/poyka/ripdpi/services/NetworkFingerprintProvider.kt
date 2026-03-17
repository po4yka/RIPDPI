package com.poyka.ripdpi.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import com.poyka.ripdpi.data.CellularNetworkIdentityTuple
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.net.InetAddress
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidNetworkFingerprintProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : NetworkFingerprintProvider {
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        private val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        override fun capture(): NetworkFingerprint? {
            val network = connectivityManager.activeNetwork ?: return null
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val linkProperties = connectivityManager.getLinkProperties(network)
            return NetworkFingerprint(
                transport = resolveTransport(capabilities),
                networkValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                captivePortalDetected =
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
                privateDnsMode = resolvePrivateDnsMode(linkProperties),
                dnsServers =
                    linkProperties
                        ?.dnsServers
                        .orEmpty()
                        .mapNotNull { it.hostAddress?.trim() }
                        .sorted(),
                wifi = resolveWifiIdentity(capabilities),
                cellular = resolveCellularIdentity(capabilities),
                metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            )
        }

        private fun resolveTransport(capabilities: NetworkCapabilities?): String =
            when {
                capabilities == null -> "unknown"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }

        private fun resolvePrivateDnsMode(linkProperties: LinkProperties?): String =
            linkProperties?.privateDnsServerName?.trim()?.takeIf { it.isNotEmpty() } ?: "system"

        private fun resolveWifiIdentity(capabilities: NetworkCapabilities?): WifiNetworkIdentityTuple? {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                return null
            }
            val wifiInfo =
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> capabilities.transportInfo as? WifiInfo
                    else -> null
                } ?: wifiManager?.connectionInfo
            val dhcpInfo = wifiManager?.dhcpInfo
            return WifiNetworkIdentityTuple(
                ssid = sanitizeWifiValue(wifiInfo?.ssid),
                bssid = sanitizeWifiValue(wifiInfo?.bssid),
                gateway =
                    dhcpInfo
                        ?.gateway
                        ?.takeIf { it != 0 }
                        ?.let(::intToIpv4Address)
                        ?.lowercase(Locale.US)
                        ?: "unknown",
            )
        }

        private fun resolveCellularIdentity(capabilities: NetworkCapabilities?): CellularNetworkIdentityTuple? {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) != true) {
                return null
            }
            val telephony = telephonyManager ?: return CellularNetworkIdentityTuple()
            return CellularNetworkIdentityTuple(
                operatorCode = sanitizeTelephonyValue(telephony.networkOperator),
                simOperatorCode = sanitizeTelephonyValue(telephony.simOperator),
                carrierId = invokeInt(telephony, "getCarrierId")?.takeIf { it >= 0 },
                dataNetworkType = describeMobileNetworkType(telephony.dataNetworkType),
                roaming = runCatching { telephony.isNetworkRoaming }.getOrNull(),
            )
        }

        private fun sanitizeWifiValue(value: String?): String {
            val normalized = value?.trim()?.removePrefix("\"")?.removeSuffix("\"")
            return when {
                normalized.isNullOrBlank() -> "unknown"
                normalized.equals("<unknown ssid>", ignoreCase = true) -> "unknown"
                normalized == "02:00:00:00:00:00" -> "unknown"
                else -> normalized.lowercase(Locale.US)
            }
        }

        private fun sanitizeTelephonyValue(value: String?): String =
            value?.trim()?.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: "unknown"

        private fun invokeInt(
            target: Any,
            methodName: String,
        ): Int? = runCatching { target.javaClass.getMethod(methodName).invoke(target) as Int }.getOrNull()

        private fun describeMobileNetworkType(type: Int): String =
            when (type) {
                TelephonyManager.NETWORK_TYPE_GPRS -> "gprs"
                TelephonyManager.NETWORK_TYPE_EDGE -> "edge"
                TelephonyManager.NETWORK_TYPE_UMTS -> "umts"
                TelephonyManager.NETWORK_TYPE_CDMA -> "cdma"
                TelephonyManager.NETWORK_TYPE_EVDO_0 -> "evdo_0"
                TelephonyManager.NETWORK_TYPE_EVDO_A -> "evdo_a"
                TelephonyManager.NETWORK_TYPE_1xRTT -> "1xrtt"
                TelephonyManager.NETWORK_TYPE_HSDPA -> "hsdpa"
                TelephonyManager.NETWORK_TYPE_HSUPA -> "hsupa"
                TelephonyManager.NETWORK_TYPE_HSPA -> "hspa"
                TelephonyManager.NETWORK_TYPE_IDEN -> "iden"
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "evdo_b"
                TelephonyManager.NETWORK_TYPE_LTE -> "lte"
                TelephonyManager.NETWORK_TYPE_EHRPD -> "ehrpd"
                TelephonyManager.NETWORK_TYPE_HSPAP -> "hspap"
                TelephonyManager.NETWORK_TYPE_GSM -> "gsm"
                TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "td_scdma"
                TelephonyManager.NETWORK_TYPE_IWLAN -> "iwlan"
                TelephonyManager.NETWORK_TYPE_NR -> "nr"
                else -> "unknown"
            }

        private fun intToIpv4Address(value: Int): String =
            InetAddress
                .getByAddress(
                    byteArrayOf(
                        (value and 0xff).toByte(),
                        ((value shr 8) and 0xff).toByte(),
                        ((value shr 16) and 0xff).toByte(),
                        ((value shr 24) and 0xff).toByte(),
                    ),
                ).hostAddress
                .orEmpty()
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkFingerprintProviderModule {
    @Binds
    @Singleton
    abstract fun bindNetworkFingerprintProvider(
        provider: AndroidNetworkFingerprintProvider,
    ): NetworkFingerprintProvider
}
