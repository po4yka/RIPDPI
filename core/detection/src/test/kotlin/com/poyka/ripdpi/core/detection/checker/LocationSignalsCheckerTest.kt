package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.checker.LocationSignalsChecker.LocationSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSignalsCheckerTest {
    @Test
    fun `Russian MCC does not trigger review`() {
        val result =
            LocationSignalsChecker.evaluate(
                LocationSnapshot(
                    networkMcc = "250",
                    networkCountryIso = "ru",
                    networkOperatorName = "MTS",
                    simMcc = "250",
                    simCountryIso = "ru",
                    isRoaming = false,
                    bssid = null,
                    phonePermissionGranted = true,
                    locationPermissionGranted = false,
                ),
            )
        assertFalse(result.needsReview)
        assertTrue(result.findings.any { it.description.contains("network_mcc_ru:true") })
    }

    @Test
    fun `non-Russian MCC triggers review`() {
        val result =
            LocationSignalsChecker.evaluate(
                LocationSnapshot(
                    networkMcc = "262",
                    networkCountryIso = "de",
                    networkOperatorName = "T-Mobile",
                    simMcc = "250",
                    simCountryIso = "ru",
                    isRoaming = false,
                    bssid = null,
                    phonePermissionGranted = true,
                    locationPermissionGranted = false,
                ),
            )
        assertTrue(result.needsReview)
        assertTrue(result.evidence.any { it.source == EvidenceSource.LOCATION_SIGNALS })
    }

    @Test
    fun `no phone permission reports missing`() {
        val result =
            LocationSignalsChecker.evaluate(
                LocationSnapshot(
                    networkMcc = null,
                    networkCountryIso = null,
                    networkOperatorName = null,
                    simMcc = null,
                    simCountryIso = null,
                    isRoaming = null,
                    bssid = null,
                    phonePermissionGranted = false,
                    locationPermissionGranted = false,
                ),
            )
        assertFalse(result.needsReview)
        assertTrue(result.findings.any { it.description.contains("READ_PHONE_STATE") })
    }

    @Test
    fun `null MCC with permission reports SIM missing`() {
        val result =
            LocationSignalsChecker.evaluate(
                LocationSnapshot(
                    networkMcc = null,
                    networkCountryIso = null,
                    networkOperatorName = null,
                    simMcc = null,
                    simCountryIso = null,
                    isRoaming = null,
                    bssid = null,
                    phonePermissionGranted = true,
                    locationPermissionGranted = false,
                ),
            )
        assertTrue(result.findings.any { it.description.contains("SIM not detected") })
    }

    @Test
    fun `roaming with non-Russian MCC uses LOW confidence`() {
        val result =
            LocationSignalsChecker.evaluate(
                LocationSnapshot(
                    networkMcc = "460",
                    networkCountryIso = "cn",
                    networkOperatorName = "China Mobile",
                    simMcc = "250",
                    simCountryIso = "ru",
                    isRoaming = true,
                    bssid = null,
                    phonePermissionGranted = true,
                    locationPermissionGranted = false,
                ),
            )
        assertTrue(result.needsReview)
        assertTrue(result.findings.any { it.description.contains("Roaming: yes") })
    }
}
