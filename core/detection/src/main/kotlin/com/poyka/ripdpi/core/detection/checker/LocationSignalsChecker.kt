@file:Suppress("LongMethod", "TooManyFunctions", "ReturnCount", "MagicNumber")

package com.poyka.ripdpi.core.detection.checker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.gsm.GsmCellLocation
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object LocationSignalsChecker {
    internal data class LocationSnapshot(
        val networkMcc: String?,
        val networkCountryIso: String?,
        val networkOperatorName: String?,
        val simMcc: String?,
        val simCountryIso: String?,
        val isRoaming: Boolean?,
        val bssid: String?,
        val phonePermissionGranted: Boolean,
        val locationPermissionGranted: Boolean,
        val beaconDbCountryIso: String? = null,
        val beaconDbLookupSummary: String? = null,
        val cellTowerCandidatesCount: Int = 0,
        val wifiAccessPointCandidatesCount: Int = 0,
    )

    private const val RUSSIA_MCC = "250"
    private const val PLACEHOLDER_BSSID = "02:00:00:00:00:00"
    private const val CELL_INFO_TIMEOUT_MS = 2_000L
    private const val MAX_CELL_TOWERS = 6
    private const val MAX_WIFI_ACCESS_POINTS = 12
    private const val MAX_NR_CELL_ID = 68_719_476_735L

    fun check(context: Context): CategoryResult {
        val snapshot = collectSnapshot(context)
        return evaluate(snapshot)
    }

    private fun collectSnapshot(context: Context): LocationSnapshot {
        val phoneGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE,
            ) == PackageManager.PERMISSION_GRANTED

        val fineLocationGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        val nearbyWifiGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                ) == PackageManager.PERMISSION_GRANTED
        val locationGranted =
            fineLocationGranted && nearbyWifiGranted

        var networkMcc: String? = null
        var networkCountryIso: String? = null
        var networkOperatorName: String? = null
        var simMcc: String? = null
        var simCountryIso: String? = null
        var isRoaming: Boolean? = null

        if (phoneGranted) {
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val networkOp = tm.networkOperator
                if (!networkOp.isNullOrEmpty() && networkOp.length >= 3) {
                    networkMcc = networkOp.substring(0, 3)
                }
                networkCountryIso = tm.networkCountryIso?.takeIf { it.isNotEmpty() }
                networkOperatorName = tm.networkOperatorName?.takeIf { it.isNotEmpty() }
                val simOp = tm.simOperator
                if (!simOp.isNullOrEmpty() && simOp.length >= 3) {
                    simMcc = simOp.substring(0, 3)
                }
                simCountryIso = tm.simCountryIso?.takeIf { it.isNotEmpty() }
                isRoaming = tm.isNetworkRoaming
            } catch (_: Exception) {
            }
        }

        var bssid: String? = null
        var cellTowers = emptyList<CellTowerSignal>()
        var wifiAccessPoints = emptyList<WifiAccessPointSignal>()
        if (locationGranted) {
            try {
                bssid = getBssid(context)
                if (phoneGranted) {
                    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    cellTowers =
                        collectTelephonyManagers(context, tm)
                            .flatMap { manager -> collectCellTowers(context, manager) }
                            .distinctBy { tower ->
                                listOf(tower.radio, tower.mcc, tower.mnc, tower.areaCode, tower.cellId)
                            }.take(MAX_CELL_TOWERS)
                }
                wifiAccessPoints = collectWifiAccessPoints(context)
            } catch (_: Exception) {
            }
        }
        val beaconDbLookup =
            if (locationGranted && (cellTowers.isNotEmpty() || wifiAccessPoints.isNotEmpty())) {
                BeaconDbClient(countryResolver = { latitude, longitude ->
                    reverseGeocodeCountry(context, latitude, longitude)
                }).lookup(cellTowers, wifiAccessPoints)
            } else {
                null
            }

        return LocationSnapshot(
            networkMcc = networkMcc,
            networkCountryIso = networkCountryIso,
            networkOperatorName = networkOperatorName,
            simMcc = simMcc,
            simCountryIso = simCountryIso,
            isRoaming = isRoaming,
            bssid = bssid,
            phonePermissionGranted = phoneGranted,
            locationPermissionGranted = locationGranted,
            beaconDbCountryIso = beaconDbLookup?.countryIso,
            beaconDbLookupSummary = beaconDbLookup?.summary,
            cellTowerCandidatesCount = cellTowers.size,
            wifiAccessPointCandidatesCount = wifiAccessPoints.size,
        )
    }

    @Suppress("DEPRECATION")
    private fun getBssid(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val wifiInfo = caps.transportInfo as? WifiInfo ?: return null
            return wifiInfo.bssid
        } else {
            val wm =
                context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo ?: return null
            return info.bssid
        }
    }

    @Suppress("CyclomaticComplexMethod")
    internal fun evaluate(snapshot: LocationSnapshot): CategoryResult {
        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()
        var needsReview = false

        if (!snapshot.phonePermissionGranted) {
            findings.add(Finding("PLMN: READ_PHONE_STATE permission not granted"))
        } else if (snapshot.networkMcc == null) {
            findings.add(Finding("PLMN: SIM not detected or network unavailable"))
        } else {
            val networkCountry = snapshot.networkCountryIso?.uppercase() ?: "N/A"
            val networkIsRussia = snapshot.networkMcc == RUSSIA_MCC

            findings.add(Finding("Network operator: ${snapshot.networkOperatorName ?: "N/A"} ($networkCountry)"))
            findings.add(Finding("Network MCC: ${snapshot.networkMcc}"))
            if (networkIsRussia) {
                findings.add(Finding("network_mcc_ru:true"))
            }

            if (snapshot.simMcc != null) {
                val simCountry = snapshot.simCountryIso?.uppercase() ?: "N/A"
                findings.add(Finding("SIM MCC: ${snapshot.simMcc} ($simCountry)"))
            }

            if (snapshot.isRoaming == true) {
                findings.add(Finding("Roaming: yes"))
            } else if (snapshot.isRoaming == false) {
                findings.add(Finding("Roaming: no"))
            }

            if (networkIsRussia && snapshot.simMcc != null && snapshot.simMcc != RUSSIA_MCC) {
                findings.add(Finding("home_routed_roaming:true"))
            }

            if (!networkIsRussia) {
                val confidence =
                    if (snapshot.isRoaming == true) {
                        EvidenceConfidence.LOW
                    } else {
                        EvidenceConfidence.MEDIUM
                    }
                val desc = "Network MCC ${snapshot.networkMcc} ($networkCountry) - not Russia"
                findings.add(
                    Finding(
                        description = desc,
                        needsReview = true,
                        source = EvidenceSource.LOCATION_SIGNALS,
                        confidence = confidence,
                    ),
                )
                evidence.add(
                    EvidenceItem(
                        source = EvidenceSource.LOCATION_SIGNALS,
                        detected = true,
                        confidence = confidence,
                        description = desc,
                    ),
                )
                needsReview = true
            }
        }

        if (!snapshot.locationPermissionGranted) {
            findings.add(Finding("BSSID: permission not granted"))
        } else if (snapshot.bssid == null || snapshot.bssid == PLACEHOLDER_BSSID) {
            findings.add(Finding("BSSID: unavailable"))
        } else {
            findings.add(Finding("BSSID: ${snapshot.bssid}"))
        }

        if (snapshot.locationPermissionGranted) {
            findings.add(Finding("Cell lookup candidates: ${snapshot.cellTowerCandidatesCount}"))
            findings.add(Finding("Wi-Fi scan candidates: ${snapshot.wifiAccessPointCandidatesCount}"))
        }
        snapshot.beaconDbCountryIso?.uppercase(Locale.US)?.let { countryIso ->
            findings.add(Finding("BeaconDB country: $countryIso"))
            if (countryIso == "RU") {
                findings.add(Finding("cell_country_ru:true"))
                findings.add(Finding("location_country_ru:true"))
            }
            val networkCountry = snapshot.networkCountryIso?.uppercase(Locale.US)
            if (networkCountry != null && networkCountry != countryIso) {
                findings.add(
                    Finding(
                        description = "Location country mismatch: BeaconDB $countryIso vs network $networkCountry",
                        needsReview = true,
                        source = EvidenceSource.LOCATION_SIGNALS,
                        confidence = EvidenceConfidence.MEDIUM,
                    ),
                )
                needsReview = true
            }
        }
        snapshot.beaconDbLookupSummary?.let { summary ->
            findings.add(Finding(summary))
        }

        return CategoryResult(
            name = "Location signals",
            detected = false,
            findings = findings,
            needsReview = needsReview,
            evidence = evidence,
        )
    }

    @Suppress("MissingPermission")
    private fun collectTelephonyManagers(
        context: Context,
        defaultManager: TelephonyManager,
    ): List<TelephonyManager> {
        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                ?: return listOf(defaultManager)
        val subscriptionManagers =
            runCatching {
                subscriptionManager.activeSubscriptionInfoList
                    .orEmpty()
                    .mapNotNull { info ->
                        runCatching { defaultManager.createForSubscriptionId(info.subscriptionId) }.getOrNull()
                    }
            }.getOrDefault(emptyList())
        return (listOf(defaultManager) + subscriptionManagers).distinctBy { manager ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.subscriptionId
            } else {
                manager.hashCode()
            }
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun collectCellTowers(
        context: Context,
        tm: TelephonyManager,
    ): List<CellTowerSignal> {
        val fresh = requestFreshCellInfo(context, tm)
        val cached = runCatching { tm.allCellInfo.orEmpty() }.getOrDefault(emptyList())
        val candidates =
            (fresh + cached)
                .distinctBy { info -> info.toString() }
                .mapNotNull(::toCellTowerSignal)
                .take(MAX_CELL_TOWERS)
        if (candidates.isNotEmpty()) {
            return candidates
        }
        val legacy = legacyGsmCellTower(tm)
        return listOfNotNull(legacy).take(MAX_CELL_TOWERS)
    }

    @Suppress("MissingPermission")
    private fun requestFreshCellInfo(
        context: Context,
        tm: TelephonyManager,
    ): List<CellInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return emptyList()
        }
        val latch = CountDownLatch(1)
        var result = emptyList<CellInfo>()
        val requested =
            runCatching {
                tm.requestCellInfoUpdate(
                    context.mainExecutor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            result = cellInfo.toList()
                            latch.countDown()
                        }

                        override fun onError(
                            errorCode: Int,
                            detail: Throwable?,
                        ) {
                            latch.countDown()
                        }
                    },
                )
            }.isSuccess
        if (!requested) {
            return emptyList()
        }
        latch.await(CELL_INFO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun legacyGsmCellTower(tm: TelephonyManager): CellTowerSignal? {
        val operator = tm.networkOperator?.takeIf { it.length >= 4 && it.all(Char::isDigit) } ?: return null
        val location = runCatching { tm.cellLocation as? GsmCellLocation }.getOrNull() ?: return null
        val lac = location.lac.takeIf { it >= 0 }?.toLong() ?: return null
        val cid = location.cid.takeIf { it >= 0 }?.toLong() ?: return null
        return CellTowerSignal(
            radio = "gsm",
            mcc = operator.substring(0, 3),
            mnc = operator.substring(3),
            areaCode = lac,
            cellId = cid,
        )
    }

    private fun toCellTowerSignal(info: CellInfo): CellTowerSignal? =
        when (info) {
            is CellInfoGsm -> {
                val identity = info.cellIdentity
                CellTowerSignal(
                    radio = "gsm",
                    mcc = cellMcc(identity.mccStringCompat(), identity.mcc),
                    mnc = cellMnc(identity.mncStringCompat(), identity.mnc),
                    areaCode = identity.lac.toValidLong() ?: return null,
                    cellId = identity.cid.toValidLong() ?: return null,
                    signalStrength = info.cellSignalStrength.dbm.toSignalStrength(),
                )
            }

            is CellInfoLte -> {
                val identity = info.cellIdentity
                CellTowerSignal(
                    radio = "lte",
                    mcc = cellMcc(identity.mccStringCompat(), identity.mcc),
                    mnc = cellMnc(identity.mncStringCompat(), identity.mnc),
                    areaCode = identity.tac.toValidLong() ?: return null,
                    cellId = identity.ci.toValidLong() ?: return null,
                    signalStrength = info.cellSignalStrength.dbm.toSignalStrength(),
                )
            }

            is CellInfoWcdma -> {
                val identity = info.cellIdentity
                CellTowerSignal(
                    radio = "wcdma",
                    mcc = cellMcc(identity.mccStringCompat(), identity.mcc),
                    mnc = cellMnc(identity.mncStringCompat(), identity.mnc),
                    areaCode = identity.lac.toValidLong() ?: return null,
                    cellId = identity.cid.toValidLong() ?: return null,
                    signalStrength = info.cellSignalStrength.dbm.toSignalStrength(),
                )
            }

            else -> {
                nrCellTower(info)
            }
        }

    private fun nrCellTower(info: CellInfo): CellTowerSignal? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || info !is android.telephony.CellInfoNr) {
            return null
        }
        val identity = info.cellIdentity as? android.telephony.CellIdentityNr ?: return null
        return CellTowerSignal(
            radio = "nr",
            mcc = identity.mccString?.takeIf { it.isDigitString() } ?: return null,
            mnc = identity.mncString?.takeIf { it.isDigitString() } ?: return null,
            areaCode = identity.tac.toValidLong() ?: return null,
            cellId = identity.nci.takeIf { it in 0..MAX_NR_CELL_ID } ?: return null,
            signalStrength = info.cellSignalStrength.dbm.toSignalStrength(),
        )
    }

    @Suppress("DEPRECATION")
    private fun cellMcc(
        mccString: String?,
        legacyMcc: Int,
    ): String = mccString?.takeIf { it.isDigitString() } ?: legacyMcc.toString()

    @Suppress("DEPRECATION")
    private fun cellMnc(
        mncString: String?,
        legacyMnc: Int,
    ): String = mncString?.takeIf { it.isDigitString() } ?: legacyMnc.toString()

    private fun CellIdentityGsm.mccStringCompat(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mccString else null

    private fun CellIdentityGsm.mncStringCompat(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mncString else null

    private fun CellIdentityLte.mccStringCompat(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mccString else null

    private fun CellIdentityLte.mncStringCompat(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mncString else null

    private fun CellIdentityWcdma.mccStringCompat(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mccString else null

    private fun CellIdentityWcdma.mncStringCompat(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mncString else null

    @Suppress("MissingPermission", "DEPRECATION")
    private fun collectWifiAccessPoints(context: Context): List<WifiAccessPointSignal> {
        val wm =
            context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
        runCatching { wm.startScan() }
        val scanned =
            runCatching {
                wm.scanResults
                    ?.mapNotNull { result ->
                        result.BSSID.toWifiSignal(
                            signalStrength = result.level.toSignalStrength(),
                            frequency = result.frequency.takeIf { it > 0 },
                        )
                    }.orEmpty()
            }.getOrDefault(emptyList())
        val connected =
            runCatching {
                wm.connectionInfo?.bssid.toWifiSignal(
                    signalStrength = wm.connectionInfo?.rssi?.toSignalStrength(),
                    frequency = wm.connectionInfo?.frequency?.takeIf { it > 0 },
                )
            }.getOrNull()
        return (scanned + listOfNotNull(connected))
            .distinctBy { it.macAddress }
            .take(MAX_WIFI_ACCESS_POINTS)
    }

    private fun String?.toWifiSignal(
        signalStrength: Int?,
        frequency: Int?,
    ): WifiAccessPointSignal? {
        val mac = normalizeMacAddress(this) ?: return null
        return WifiAccessPointSignal(
            macAddress = mac,
            signalStrength = signalStrength,
            frequency = frequency,
        )
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocodeCountry(
        context: Context,
        latitude: Double,
        longitude: Double,
    ): String? =
        runCatching {
            if (!Geocoder.isPresent()) {
                null
            } else {
                Geocoder(context, Locale.US)
                    .getFromLocation(latitude, longitude, 1)
                    ?.firstOrNull()
                    ?.countryCode
            }
        }.getOrNull()

    private fun normalizeMacAddress(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.US) ?: return null
        if (normalized == PLACEHOLDER_BSSID) return null
        return normalized.takeIf { MAC_ADDRESS_REGEX.matches(it) }
    }

    private fun Int.toValidLong(): Long? = toLong().takeIf { it in 0 until Int.MAX_VALUE.toLong() }

    private fun Int.toSignalStrength(): Int? = takeIf { it in -150..0 }

    private fun String.isDigitString(): Boolean = isNotBlank() && all(Char::isDigit)

    private val MAC_ADDRESS_REGEX = Regex("^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")
}
