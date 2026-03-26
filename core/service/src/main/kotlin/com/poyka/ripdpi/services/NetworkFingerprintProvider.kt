package com.poyka.ripdpi.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
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
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

internal enum class CapturedTransport {
    Wifi,
    Cellular,
    Ethernet,
    Vpn,
}

internal data class CapturedWifiIdentity(
    val ssid: String? = null,
    val bssid: String? = null,
    val gatewayIpv4: Int? = null,
)

internal data class CapturedCellularIdentity(
    val networkOperator: String? = null,
    val simOperator: String? = null,
    val carrierId: Int? = null,
    val dataNetworkType: Int = 0,
    val roaming: Boolean? = null,
)

internal data class CapturedNetworkSnapshot(
    val transports: Set<CapturedTransport>? = null,
    val networkValidated: Boolean = false,
    val captivePortalDetected: Boolean = false,
    val privateDnsServerName: String? = null,
    val dnsServers: List<String> = emptyList(),
    val wifi: CapturedWifiIdentity? = null,
    val cellular: CapturedCellularIdentity? = null,
    val metered: Boolean = false,
)

internal interface AndroidNetworkSnapshotSource {
    fun capture(): CapturedNetworkSnapshot?
}

@Singleton
internal class DefaultAndroidNetworkSnapshotSource
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : AndroidNetworkSnapshotSource {
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        private val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        @android.annotation.SuppressLint("MissingPermission")
        override fun capture(): CapturedNetworkSnapshot? {
            val network = activeNetworkOrNull() ?: return null
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val linkProperties = connectivityManager.getLinkProperties(network)
            val transports = capabilities?.let(::captureTransports)
            return CapturedNetworkSnapshot(
                transports = transports,
                networkValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                captivePortalDetected =
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
                privateDnsServerName = resolvePrivateDnsServerName(linkProperties),
                dnsServers = captureDnsServers(linkProperties),
                wifi = captureWifiIdentity(transports, capabilities),
                cellular = captureCellularIdentity(transports),
                metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            )
        }

        @android.annotation.SuppressLint("MissingPermission")
        private fun activeNetworkOrNull() =
            if (hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
                connectivityManager.activeNetwork
            } else {
                null
            }

        private fun resolvePrivateDnsServerName(linkProperties: LinkProperties?): String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                linkProperties?.privateDnsServerName
            } else {
                null
            }

        private fun captureTransports(capabilities: NetworkCapabilities): Set<CapturedTransport> =
            buildSet {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    add(CapturedTransport.Wifi)
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    add(CapturedTransport.Cellular)
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    add(CapturedTransport.Ethernet)
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    add(CapturedTransport.Vpn)
                }
            }

        private fun captureDnsServers(linkProperties: LinkProperties?): List<String> =
            linkProperties
                ?.dnsServers
                .orEmpty()
                .mapNotNull { it.hostAddress?.trim()?.takeIf(String::isNotEmpty) }
                .sorted()

        private fun captureWifiIdentity(
            transports: Set<CapturedTransport>?,
            capabilities: NetworkCapabilities?,
        ): CapturedWifiIdentity? {
            if (transports?.contains(CapturedTransport.Wifi) != true) {
                return null
            }
            val wifiInfo =
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> capabilities?.transportInfo as? WifiInfo
                    else -> null
                } ?: currentWifiManagerConnectionInfo()
            val gateway = currentWifiGatewayIpv4()
            return CapturedWifiIdentity(
                ssid = wifiInfo?.ssid,
                bssid = wifiInfo?.bssid,
                gatewayIpv4 = gateway,
            )
        }

        @Suppress("DEPRECATION")
        @android.annotation.SuppressLint("MissingPermission")
        private fun currentWifiManagerConnectionInfo(): WifiInfo? {
            if (!hasWifiStatePermission()) {
                return null
            }
            return runCatching { wifiManager?.connectionInfo }.getOrNull()
        }

        @Suppress("DEPRECATION")
        @android.annotation.SuppressLint("MissingPermission")
        private fun currentWifiGatewayIpv4(): Int? {
            if (!hasWifiStatePermission()) {
                return null
            }
            return runCatching { wifiManager?.dhcpInfo?.gateway?.takeIf { it != 0 } }.getOrNull()
        }

        @android.annotation.SuppressLint("MissingPermission")
        private fun captureCellularIdentity(transports: Set<CapturedTransport>?): CapturedCellularIdentity? {
            if (transports?.contains(CapturedTransport.Cellular) != true) {
                return null
            }
            val telephony = telephonyManager ?: return CapturedCellularIdentity()
            val canReadPhoneState = hasPhoneStatePermission()
            return CapturedCellularIdentity(
                networkOperator = telephony.networkOperator,
                simOperator = telephony.simOperator,
                carrierId = invokeInt(telephony, "getCarrierId")?.takeIf { it >= 0 },
                dataNetworkType =
                    if (canReadPhoneState) {
                        runCatching { telephony.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
                    } else {
                        TelephonyManager.NETWORK_TYPE_UNKNOWN
                    },
                roaming =
                    if (canReadPhoneState) {
                        runCatching { telephony.isNetworkRoaming }.getOrNull()
                    } else {
                        null
                    },
            )
        }

        private fun invokeInt(
            target: Any,
            methodName: String,
        ): Int? = runCatching { target.javaClass.getMethod(methodName).invoke(target) as Int }.getOrNull()

        private fun hasPhoneStatePermission(): Boolean =
            hasPermission(Manifest.permission.READ_PHONE_STATE) ||
                hasPermission("android.permission.READ_BASIC_PHONE_STATE")

        private fun hasWifiStatePermission(): Boolean = hasPermission(Manifest.permission.ACCESS_WIFI_STATE)

        private fun hasPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

internal class NetworkFingerprintMapper
    @Inject
    constructor() {
        fun map(snapshot: CapturedNetworkSnapshot): NetworkFingerprint =
            NetworkFingerprint(
                transport = resolveTransport(snapshot.transports),
                networkValidated = snapshot.networkValidated,
                captivePortalDetected = snapshot.captivePortalDetected,
                privateDnsMode = resolvePrivateDnsMode(snapshot.privateDnsServerName),
                dnsServers =
                    snapshot.dnsServers
                        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                        .sorted(),
                wifi = resolveWifiIdentity(snapshot),
                cellular = resolveCellularIdentity(snapshot),
                metered = snapshot.metered,
            )

        private fun resolveTransport(transports: Set<CapturedTransport>?): String =
            when {
                transports == null -> "unknown"
                transports.contains(CapturedTransport.Wifi) -> "wifi"
                transports.contains(CapturedTransport.Cellular) -> "cellular"
                transports.contains(CapturedTransport.Ethernet) -> "ethernet"
                transports.contains(CapturedTransport.Vpn) -> "vpn"
                else -> "other"
            }

        private fun resolvePrivateDnsMode(privateDnsServerName: String?): String =
            privateDnsServerName?.trim()?.takeIf { it.isNotEmpty() } ?: "system"

        private fun resolveWifiIdentity(snapshot: CapturedNetworkSnapshot): WifiNetworkIdentityTuple? {
            if (snapshot.transports?.contains(CapturedTransport.Wifi) != true) {
                return null
            }
            return WifiNetworkIdentityTuple(
                ssid = sanitizeWifiValue(snapshot.wifi?.ssid),
                bssid = sanitizeWifiValue(snapshot.wifi?.bssid),
                gateway =
                    snapshot.wifi
                        ?.gatewayIpv4
                        ?.let(::intToIpv4Address)
                        ?.lowercase(Locale.US)
                        ?: "unknown",
            )
        }

        private fun resolveCellularIdentity(snapshot: CapturedNetworkSnapshot): CellularNetworkIdentityTuple? {
            if (snapshot.transports?.contains(CapturedTransport.Cellular) != true) {
                return null
            }
            val cellular = snapshot.cellular ?: return CellularNetworkIdentityTuple()
            return CellularNetworkIdentityTuple(
                operatorCode = sanitizeTelephonyValue(cellular.networkOperator),
                simOperatorCode = sanitizeTelephonyValue(cellular.simOperator),
                carrierId = cellular.carrierId?.takeIf { it >= 0 },
                dataNetworkType = describeMobileNetworkType(cellular.dataNetworkType),
                roaming = cellular.roaming,
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

        @Suppress("DEPRECATION")
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

@Singleton
internal class AndroidNetworkFingerprintProvider
    @Inject
    constructor(
        private val snapshotSource: AndroidNetworkSnapshotSource,
        private val mapper: NetworkFingerprintMapper,
    ) : NetworkFingerprintProvider {
        override fun capture(): NetworkFingerprint? =
            try {
                snapshotSource.capture()?.let(mapper::map)
            } catch (error: Exception) {
                logcat(LogPriority.WARN) {
                    "Unable to capture network fingerprint\n${error.asLog()}"
                }
                null
            }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkFingerprintBindingsModule {
    @Binds
    @Singleton
    internal abstract fun bindNetworkSnapshotSource(
        source: DefaultAndroidNetworkSnapshotSource,
    ): AndroidNetworkSnapshotSource

    @Binds
    @Singleton
    internal abstract fun bindNetworkFingerprintProvider(
        provider: AndroidNetworkFingerprintProvider,
    ): NetworkFingerprintProvider
}
