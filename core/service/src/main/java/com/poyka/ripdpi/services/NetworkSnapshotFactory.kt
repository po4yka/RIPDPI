package com.poyka.ripdpi.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.poyka.ripdpi.data.NativeCellularSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeWifiSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkSnapshotFactory
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val fingerprintProvider: NetworkFingerprintProvider,
    ) : NativeNetworkSnapshotProvider {
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        override fun capture(): NativeNetworkSnapshot {
            val fingerprint = fingerprintProvider.capture()
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            val uid = context.applicationInfo.uid
            val txBytes = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0L
            val rxBytes = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0L
            val capturedAtMs = System.currentTimeMillis()
            val wifiFrequencyBand = resolveWifiFrequencyBand(capabilities)
            val mtu = resolveMtu(linkProperties)
            return if (fingerprint != null) {
                fingerprintToSnapshot(
                    fingerprint = fingerprint,
                    txBytes = txBytes,
                    rxBytes = rxBytes,
                    wifiFrequencyBand = wifiFrequencyBand,
                    mtu = mtu,
                    capturedAtMs = capturedAtMs,
                )
            } else {
                NativeNetworkSnapshot(
                    transport = "none",
                    mtu = mtu,
                    trafficTxBytes = txBytes,
                    trafficRxBytes = rxBytes,
                    capturedAtMs = capturedAtMs,
                )
            }
        }

        private fun fingerprintToSnapshot(
            fingerprint: NetworkFingerprint,
            txBytes: Long,
            rxBytes: Long,
            wifiFrequencyBand: String,
            mtu: Int?,
            capturedAtMs: Long,
        ): NativeNetworkSnapshot =
            NativeNetworkSnapshot(
                transport = fingerprint.transport,
                validated = fingerprint.networkValidated,
                captivePortal = fingerprint.captivePortalDetected,
                metered = fingerprint.metered,
                privateDnsMode = fingerprint.privateDnsMode,
                dnsServers = fingerprint.dnsServers,
                cellular =
                    fingerprint.cellular?.let { cell ->
                        NativeCellularSnapshot(
                            generation = cellularGeneration(cell.dataNetworkType),
                            roaming = cell.roaming ?: false,
                            operatorCode = cell.operatorCode,
                        )
                    },
                wifi =
                    fingerprint.wifi?.let { wifi ->
                        NativeWifiSnapshot(
                            frequencyBand = wifiFrequencyBand,
                            ssidHash = hashSsid(wifi.ssid),
                        )
                    },
                mtu = mtu,
                trafficTxBytes = txBytes,
                trafficRxBytes = rxBytes,
                capturedAtMs = capturedAtMs,
            )

        private fun resolveWifiFrequencyBand(capabilities: NetworkCapabilities?): String {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                return "unknown"
            }
            val wifiInfo =
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> capabilities.transportInfo as? WifiInfo
                    else -> null
                } ?: wifiManager?.connectionInfo
            return describeWifiBand(wifiInfo?.frequency)
        }

        private fun resolveMtu(linkProperties: LinkProperties?): Int? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                linkProperties?.mtu?.takeIf { it > 0 }
            } else {
                null
            }

        private fun describeWifiBand(frequencyMhz: Int?): String =
            when (frequencyMhz) {
                in 2400..2500 -> "2.4ghz"
                in 4900..5900 -> "5ghz"
                in 5925..7125 -> "6ghz"
                else -> "unknown"
            }

        private fun cellularGeneration(dataNetworkType: String): String =
            when (dataNetworkType) {
                "gprs", "edge", "cdma", "1xrtt", "iden", "gsm" -> "2g"

                "umts", "evdo_0", "evdo_a", "evdo_b", "hsdpa", "hsupa", "hspa",
                "hspap", "ehrpd", "td_scdma", "iwlan",
                -> "3g"

                "lte" -> "4g"

                "nr" -> "5g"

                else -> "unknown"
            }

        private fun hashSsid(ssid: String): String {
            if (ssid.isBlank() || ssid == "unknown") return ""
            val bytes = MessageDigest.getInstance("SHA-256").digest(ssid.toByteArray())
            return buildString(bytes.size * 2) {
                bytes.forEach { byte ->
                    append(((byte.toInt() shr 4) and 0xF).toString(16))
                    append((byte.toInt() and 0xF).toString(16))
                }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class NativeNetworkSnapshotProviderModule {
    @Binds
    @Singleton
    abstract fun bindNativeNetworkSnapshotProvider(factory: NetworkSnapshotFactory): NativeNetworkSnapshotProvider
}
