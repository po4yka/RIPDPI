package com.poyka.ripdpi.core.detection.checker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding

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
    )

    private const val RUSSIA_MCC = "250"
    private const val PLACEHOLDER_BSSID = "02:00:00:00:00:00"

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

        val locationPermission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        val locationGranted =
            ContextCompat.checkSelfPermission(
                context,
                locationPermission,
            ) == PackageManager.PERMISSION_GRANTED

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
        if (locationGranted) {
            try {
                bssid = getBssid(context)
            } catch (_: Exception) {
            }
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

        return CategoryResult(
            name = "Location signals",
            detected = false,
            findings = findings,
            needsReview = needsReview,
            evidence = evidence,
        )
    }
}
