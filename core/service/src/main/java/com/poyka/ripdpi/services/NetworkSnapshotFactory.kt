@file:Suppress("CyclomaticComplexMethod", "DEPRECATION", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package com.poyka.ripdpi.services

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import com.poyka.ripdpi.data.CellularNetworkIdentityTuple
import com.poyka.ripdpi.data.NativeCellularSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeWifiSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
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
        private val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        @SuppressLint("MissingPermission")
        override fun capture(): NativeNetworkSnapshot {
            val fingerprint = fingerprintProvider.capture()
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            val uid = context.applicationInfo.uid
            val txBytes = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0L
            val rxBytes = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0L
            val capturedAtMs = System.currentTimeMillis()
            val wifi = resolveWifiSnapshot(capabilities, fingerprint?.wifi)
            val cellular = resolveCellularSnapshot(fingerprint?.cellular)
            val mtu = resolveMtu(linkProperties)
            return buildNativeNetworkSnapshot(
                fingerprint = fingerprint,
                txBytes = txBytes,
                rxBytes = rxBytes,
                wifi = wifi,
                cellular = cellular,
                mtu = mtu,
                capturedAtMs = capturedAtMs,
            )
        }

        private fun resolveMtu(linkProperties: LinkProperties?): Int? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                linkProperties?.mtu?.takeIf { it > 0 }
            } else {
                null
            }

        private fun resolveWifiSnapshot(
            capabilities: NetworkCapabilities?,
            wifiIdentity: WifiNetworkIdentityTuple?,
        ): NativeWifiSnapshot? {
            if (wifiIdentity == null) {
                return null
            }
            val wifiInfo = currentWifiInfo(capabilities)
            return NativeWifiSnapshot(
                frequencyBand = describeWifiBand(wifiInfo?.frequency),
                ssidHash = hashSsid(wifiIdentity.ssid),
                frequencyMhz = wifiInfo?.frequency?.takeIf { it > 0 },
                rssiDbm = wifiInfo?.rssi?.takeIf { it in -127..0 },
                linkSpeedMbps = wifiInfo?.linkSpeed?.takeIf { it > 0 },
                rxLinkSpeedMbps = resolveWifiRxLinkSpeed(wifiInfo),
                txLinkSpeedMbps = resolveWifiTxLinkSpeed(wifiInfo),
                channelWidth = describeWifiChannelWidth(wifiInfo?.let { invokeInt(it, "getChannelWidth") }),
                wifiStandard = describeWifiStandard(wifiInfo?.let { invokeInt(it, "getWifiStandard") }),
            )
        }

        @SuppressLint("MissingPermission")
        private fun resolveCellularSnapshot(cellularIdentity: CellularNetworkIdentityTuple?): NativeCellularSnapshot? {
            if (cellularIdentity == null) {
                return null
            }
            val telephony = telephonyManager
            val dataNetworkType =
                telephony
                    ?.let { describeMobileNetworkType(runCatching { it.dataNetworkType }.getOrNull()) }
                    ?.takeUnless { it == "unknown" }
                    ?: canonicalMobileNetworkType(cellularIdentity.dataNetworkType)
            val serviceState =
                telephony?.let { describeServiceState(runCatching { it.serviceState?.state }.getOrNull()) } ?: "unknown"
            val signalStrength = resolveSignalStrength(telephony)
            return NativeCellularSnapshot(
                generation = cellularGeneration(dataNetworkType),
                roaming =
                    cellularIdentity.roaming ?: telephony?.let { runCatching { it.isNetworkRoaming }.getOrNull() }
                        ?: false,
                operatorCode = cellularIdentity.operatorCode,
                dataNetworkType = dataNetworkType,
                serviceState = serviceState,
                carrierId = telephony?.let { invokeInt(it, "getCarrierId") }?.takeIf { it >= 0 },
                signalLevel = signalStrength?.level,
                signalDbm = resolveSignalDbm(signalStrength),
            )
        }

        private fun currentWifiInfo(capabilities: NetworkCapabilities?): WifiInfo? =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> capabilities?.transportInfo as? WifiInfo
                else -> null
            } ?: wifiManager?.connectionInfo

        private fun resolveWifiRxLinkSpeed(wifiInfo: WifiInfo?): Int? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiInfo?.rxLinkSpeedMbps?.takeIf { it > 0 }
            } else {
                null
            }

        private fun resolveWifiTxLinkSpeed(wifiInfo: WifiInfo?): Int? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiInfo?.txLinkSpeedMbps?.takeIf { it > 0 }
            } else {
                null
            }

        private fun resolveSignalStrength(telephony: TelephonyManager?): SignalStrength? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telephony?.let { runCatching { it.signalStrength }.getOrNull() }
            } else {
                null
            }

        private fun resolveSignalDbm(signalStrength: SignalStrength?): Int? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm
            } else {
                null
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

        private fun invokeInt(
            target: Any,
            methodName: String,
        ): Int? = runCatching { target.javaClass.getMethod(methodName).invoke(target) as Int }.getOrNull()
    }

internal fun buildNativeNetworkSnapshot(
    fingerprint: NetworkFingerprint?,
    txBytes: Long,
    rxBytes: Long,
    wifi: NativeWifiSnapshot?,
    cellular: NativeCellularSnapshot?,
    mtu: Int?,
    capturedAtMs: Long,
): NativeNetworkSnapshot =
    if (fingerprint == null) {
        NativeNetworkSnapshot(
            transport = "none",
            mtu = mtu,
            trafficTxBytes = txBytes,
            trafficRxBytes = rxBytes,
            capturedAtMs = capturedAtMs,
        )
    } else {
        NativeNetworkSnapshot(
            transport = fingerprint.transport,
            validated = fingerprint.networkValidated,
            captivePortal = fingerprint.captivePortalDetected,
            metered = fingerprint.metered,
            privateDnsMode = fingerprint.privateDnsMode,
            dnsServers = fingerprint.dnsServers,
            cellular = cellular,
            wifi = wifi,
            mtu = mtu,
            trafficTxBytes = txBytes,
            trafficRxBytes = rxBytes,
            capturedAtMs = capturedAtMs,
        )
    }

internal fun describeWifiBand(frequencyMhz: Int?): String =
    when (frequencyMhz) {
        in 2400..2500 -> "2.4ghz"
        in 4900..5900 -> "5ghz"
        in 5925..7125 -> "6ghz"
        else -> "unknown"
    }

internal fun describeWifiChannelWidth(channelWidth: Int?): String =
    when (channelWidth) {
        0 -> "20 MHz"
        1 -> "40 MHz"
        2 -> "80 MHz"
        3 -> "160 MHz"
        4 -> "80+80 MHz"
        5 -> "320 MHz"
        else -> "unknown"
    }

internal fun describeWifiStandard(standard: Int?): String =
    when (standard) {
        1 -> "legacy"
        4 -> "802.11n"
        5 -> "802.11ac"
        6 -> "802.11ax"
        7 -> "802.11ad"
        8 -> "802.11be"
        else -> "unknown"
    }

internal fun describeMobileNetworkType(type: Int?): String =
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
        TelephonyManager.NETWORK_TYPE_UNKNOWN, null -> "unknown"
        else -> type.toString()
    }

internal fun describeServiceState(state: Int?): String =
    when (state) {
        ServiceState.STATE_IN_SERVICE -> "in_service"
        ServiceState.STATE_OUT_OF_SERVICE -> "out_of_service"
        ServiceState.STATE_EMERGENCY_ONLY -> "emergency_only"
        ServiceState.STATE_POWER_OFF -> "power_off"
        else -> "unknown"
    }

internal fun canonicalMobileNetworkType(rawValue: String?): String =
    when (rawValue?.trim()?.lowercase()) {
        "gprs" -> "GPRS"
        "edge" -> "EDGE"
        "umts" -> "UMTS"
        "cdma" -> "CDMA"
        "evdo_0" -> "EVDO_0"
        "evdo_a" -> "EVDO_A"
        "1xrtt" -> "1xRTT"
        "hsdpa" -> "HSDPA"
        "hsupa" -> "HSUPA"
        "hspa" -> "HSPA"
        "iden" -> "IDEN"
        "evdo_b" -> "EVDO_B"
        "lte" -> "LTE"
        "ehrpd" -> "EHRPD"
        "hspap" -> "HSPAP"
        "gsm" -> "GSM"
        "td_scdma" -> "TD_SCDMA"
        "iwlan" -> "IWLAN"
        "nr" -> "NR"
        "unknown", "", null -> "unknown"
        else -> rawValue.trim().ifBlank { "unknown" }
    }

internal fun cellularGeneration(dataNetworkType: String): String =
    when (canonicalMobileNetworkType(dataNetworkType).lowercase()) {
        "gprs", "edge", "cdma", "1xrtt", "iden", "gsm" -> "2g"
        "umts", "evdo_0", "evdo_a", "evdo_b", "hsdpa", "hsupa", "hspa", "hspap", "ehrpd", "td_scdma", "iwlan" -> "3g"
        "lte" -> "4g"
        "nr" -> "5g"
        else -> "unknown"
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class NativeNetworkSnapshotProviderModule {
    @Binds
    @Singleton
    abstract fun bindNativeNetworkSnapshotProvider(factory: NetworkSnapshotFactory): NativeNetworkSnapshotProvider
}
