package com.poyka.ripdpi.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.CellularNetworkIdentityTuple
import com.poyka.ripdpi.data.NativeCellularSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeWifiSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.NetworkMetadataCaptureScope
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val MinValidRssiDbm = -127
private const val MaxValidRssiDbm = 0
private const val MinimumLinkSpeedMbps = 1
private val WifiBandRanges =
    listOf(
        2400..2500 to "2.4ghz",
        4900..5900 to "5ghz",
        5925..7125 to "6ghz",
    )
private val WifiChannelWidthLabels =
    mapOf(
        0 to "20 MHz",
        1 to "40 MHz",
        2 to "80 MHz",
        3 to "160 MHz",
        4 to "80+80 MHz",
        5 to "320 MHz",
    )
private val WifiStandardLabels =
    mapOf(
        1 to "legacy",
        4 to "802.11n",
        5 to "802.11ac",
        6 to "802.11ax",
        7 to "802.11ad",
        8 to "802.11be",
    )

@Suppress("DEPRECATION", "InlinedApi")
private val MobileNetworkTypeLabels =
    mapOf(
        TelephonyManager.NETWORK_TYPE_GPRS to "GPRS",
        TelephonyManager.NETWORK_TYPE_EDGE to "EDGE",
        TelephonyManager.NETWORK_TYPE_UMTS to "UMTS",
        TelephonyManager.NETWORK_TYPE_CDMA to "CDMA",
        TelephonyManager.NETWORK_TYPE_EVDO_0 to "EVDO_0",
        TelephonyManager.NETWORK_TYPE_EVDO_A to "EVDO_A",
        TelephonyManager.NETWORK_TYPE_1xRTT to "1xRTT",
        TelephonyManager.NETWORK_TYPE_HSDPA to "HSDPA",
        TelephonyManager.NETWORK_TYPE_HSUPA to "HSUPA",
        TelephonyManager.NETWORK_TYPE_HSPA to "HSPA",
        TelephonyManager.NETWORK_TYPE_IDEN to "IDEN",
        TelephonyManager.NETWORK_TYPE_EVDO_B to "EVDO_B",
        TelephonyManager.NETWORK_TYPE_LTE to "LTE",
        TelephonyManager.NETWORK_TYPE_EHRPD to "EHRPD",
        TelephonyManager.NETWORK_TYPE_HSPAP to "HSPAP",
        TelephonyManager.NETWORK_TYPE_GSM to "GSM",
        TelephonyManager.NETWORK_TYPE_TD_SCDMA to "TD_SCDMA",
        TelephonyManager.NETWORK_TYPE_IWLAN to "IWLAN",
        TelephonyManager.NETWORK_TYPE_NR to "NR",
    )
private val CanonicalMobileNetworkTypeLabels =
    MobileNetworkTypeLabels.values.associateBy { it.lowercase() } +
        mapOf("unknown" to "unknown", "" to "unknown")

@Singleton
class NetworkSnapshotFactory
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val fingerprintProvider: NetworkFingerprintProvider,
        private val snapshotScope: NetworkMetadataCaptureScope,
    ) : NativeNetworkSnapshotProvider {
        private val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        private val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        override fun capture(): NativeNetworkSnapshot {
            val uid = context.applicationInfo.uid
            val unavailable =
                NativeNetworkSnapshot(
                    transport = "unknown",
                    trafficTxBytes = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0L,
                    trafficRxBytes = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0L,
                    capturedAtMs = System.currentTimeMillis(),
                )
            val capture = snapshotScope.begin() ?: return unavailable
            val fingerprint = capture.network?.let { fingerprintProvider.capture() }
            return if (capture.network != null && fingerprint == null) {
                unavailable
            } else {
                buildNativeNetworkSnapshot(
                    fingerprint = fingerprint,
                    txBytes = unavailable.trafficTxBytes,
                    rxBytes = unavailable.trafficRxBytes,
                    wifi = resolveWifiSnapshot(capture.capabilities, fingerprint?.wifi),
                    cellular = resolveCellularSnapshot(fingerprint?.cellular),
                    mtu = resolveMtu(capture.links),
                    capturedAtMs = unavailable.capturedAtMs,
                ).takeIf { capture.isCurrent() } ?: unavailable
            }
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
                rssiDbm = wifiInfo?.rssi?.takeIf { it in MinValidRssiDbm..MaxValidRssiDbm },
                linkSpeedMbps = wifiInfo?.linkSpeed?.takeIf { it >= MinimumLinkSpeedMbps },
                rxLinkSpeedMbps = resolveWifiRxLinkSpeed(wifiInfo),
                txLinkSpeedMbps = resolveWifiTxLinkSpeed(wifiInfo),
                channelWidth = describeWifiChannelWidth(wifiInfo?.let { invokeInt(it, "getChannelWidth") }),
                wifiStandard = describeWifiStandard(wifiInfo?.let { invokeInt(it, "getWifiStandard") }),
            )
        }

        @android.annotation.SuppressLint("MissingPermission")
        private fun resolveCellularSnapshot(cellularIdentity: CellularNetworkIdentityTuple?): NativeCellularSnapshot? {
            if (cellularIdentity == null) {
                return null
            }
            val telephony = telephonyManager
            val canReadPhoneState = hasPhoneStatePermission()
            val dataNetworkType =
                telephony
                    ?.takeIf { canReadPhoneState }
                    ?.let { describeMobileNetworkType(runCatching { it.dataNetworkType }.getOrNull()) }
                    ?.takeUnless { it == "unknown" }
                    ?: canonicalMobileNetworkType(cellularIdentity.dataNetworkType)
            val serviceState =
                telephony
                    ?.takeIf { hasServiceStatePermission() }
                    ?.let { describeServiceState(runCatching { it.serviceState?.state }.getOrNull()) }
                    ?: "unknown"
            val signalStrength = resolveSignalStrength(telephony, canReadPhoneState)
            return NativeCellularSnapshot(
                generation = cellularGeneration(dataNetworkType),
                roaming =
                    cellularIdentity.roaming ?: telephony
                        ?.takeIf { canReadPhoneState }
                        ?.let { runCatching { it.isNetworkRoaming }.getOrNull() }
                        ?: false,
                operatorCode = cellularIdentity.operatorCode,
                dataNetworkType = dataNetworkType,
                serviceState = serviceState,
                carrierId =
                    telephony
                        ?.takeIf { canReadPhoneState }
                        ?.let { invokeInt(it, "getCarrierId") }
                        ?.takeIf { it >= 0 },
                signalLevel = signalStrength?.level,
                signalDbm = resolveSignalDbm(signalStrength),
            )
        }

        private fun currentWifiInfo(capabilities: NetworkCapabilities?): WifiInfo? =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> capabilities?.transportInfo as? WifiInfo
                else -> null
            } ?: currentWifiManagerConnectionInfo()

        @Suppress("DEPRECATION")
        @android.annotation.SuppressLint("MissingPermission")
        private fun currentWifiManagerConnectionInfo(): WifiInfo? {
            if (!hasWifiStatePermission()) {
                return null
            }
            return runCatching { wifiManager?.connectionInfo }.getOrNull()
        }

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

        private fun resolveSignalStrength(
            telephony: TelephonyManager?,
            canReadPhoneState: Boolean,
        ): SignalStrength? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && canReadPhoneState) {
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
            return bytes.toHexString()
        }

        private fun invokeInt(
            target: Any,
            methodName: String,
        ): Int? = runCatching { target.javaClass.getMethod(methodName).invoke(target) as Int }.getOrNull()

        private fun hasPhoneStatePermission(): Boolean =
            hasPermission(Manifest.permission.READ_PHONE_STATE) ||
                hasPermission("android.permission.READ_BASIC_PHONE_STATE")

        private fun hasWifiStatePermission(): Boolean = hasPermission(Manifest.permission.ACCESS_WIFI_STATE)

        private fun hasServiceStatePermission(): Boolean =
            hasPermission(Manifest.permission.READ_PHONE_STATE) &&
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

        private fun hasPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
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
    WifiBandRanges
        .firstOrNull { (range, _) -> frequencyMhz in range }
        ?.second
        ?: "unknown"

internal fun describeWifiChannelWidth(channelWidth: Int?): String = WifiChannelWidthLabels[channelWidth] ?: "unknown"

internal fun describeWifiStandard(standard: Int?): String = WifiStandardLabels[standard] ?: "unknown"

internal fun describeMobileNetworkType(type: Int?): String =
    type?.let(MobileNetworkTypeLabels::get) ?: type?.toString() ?: "unknown"

internal fun describeServiceState(state: Int?): String =
    when (state) {
        ServiceState.STATE_IN_SERVICE -> "in_service"
        ServiceState.STATE_OUT_OF_SERVICE -> "out_of_service"
        ServiceState.STATE_EMERGENCY_ONLY -> "emergency_only"
        ServiceState.STATE_POWER_OFF -> "power_off"
        else -> "unknown"
    }

internal fun canonicalMobileNetworkType(rawValue: String?): String =
    rawValue
        ?.trim()
        ?.lowercase()
        ?.let(CanonicalMobileNetworkTypeLabels::get)
        ?: rawValue?.trim()?.ifBlank { "unknown" }
        ?: "unknown"

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
