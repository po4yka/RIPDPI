package com.poyka.ripdpi.services

import android.content.Context
import android.net.TrafficStats
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
        override fun capture(): NativeNetworkSnapshot {
            val fingerprint = fingerprintProvider.capture()
            val uid = context.applicationInfo.uid
            val txBytes = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0L
            val rxBytes = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0L
            return if (fingerprint != null) {
                fingerprintToSnapshot(fingerprint, txBytes, rxBytes)
            } else {
                NativeNetworkSnapshot(
                    transport = "none",
                    trafficTxBytes = txBytes,
                    trafficRxBytes = rxBytes,
                    capturedAtMs = System.currentTimeMillis(),
                )
            }
        }

        private fun fingerprintToSnapshot(
            fingerprint: NetworkFingerprint,
            txBytes: Long,
            rxBytes: Long,
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
                            frequencyBand = "unknown",
                            ssidHash = hashSsid(wifi.ssid),
                        )
                    },
                trafficTxBytes = txBytes,
                trafficRxBytes = rxBytes,
                capturedAtMs = System.currentTimeMillis(),
            )

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
