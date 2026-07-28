package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.diagnostics.NetworkPathValidationEvidence
import com.poyka.ripdpi.services.network.NetworkCondition
import org.junit.Assert.assertEquals
import org.junit.Test

class MainNetworkConditionResolverTest {
    @Test
    fun `captured missing underlay is no connectivity`() {
        val evidence =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                underlayPresent = false,
                vpnPresent = true,
                vpnValidated = false,
            )

        assertEquals(NetworkCondition.NoConnectivity, resolveMainNetworkCondition(AppStatus.Running, evidence))
    }

    @Test
    fun `validated underlay with unvalidated vpn is not no connectivity`() {
        val evidence =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                underlayPresent = true,
                underlayInternet = true,
                underlayValidated = true,
                vpnPresent = true,
                vpnValidated = false,
            )

        assertEquals(NetworkCondition.Normal, resolveMainNetworkCondition(AppStatus.Running, evidence))
    }

    @Test
    fun `underlay without internet capability is no connectivity`() {
        val evidence =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                underlayPresent = true,
                underlayInternet = false,
                underlayValidated = false,
            )

        assertEquals(NetworkCondition.NoConnectivity, resolveMainNetworkCondition(AppStatus.Running, evidence))
    }

    @Test
    fun `unvalidated underlay is no connectivity`() {
        val evidence =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                underlayPresent = true,
                underlayInternet = true,
                underlayValidated = false,
            )

        assertEquals(NetworkCondition.NoConnectivity, resolveMainNetworkCondition(AppStatus.Running, evidence))
    }

    @Test
    fun `captive portal underlay is no connectivity`() {
        val evidence =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                underlayPresent = true,
                underlayInternet = true,
                underlayValidated = true,
                underlayCaptivePortal = true,
            )

        assertEquals(NetworkCondition.NoConnectivity, resolveMainNetworkCondition(AppStatus.Running, evidence))
    }

    @Test
    fun `unknown capture status does not claim outage`() {
        val evidence = NetworkPathValidationEvidence(captureStatus = "permission_unavailable")

        assertEquals(NetworkCondition.Normal, resolveMainNetworkCondition(AppStatus.Running, evidence))
    }

    @Test
    fun `reconnecting is retained when underlay is usable`() {
        val evidence =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                underlayPresent = true,
                underlayInternet = true,
                underlayValidated = true,
            )

        assertEquals(
            NetworkCondition.BlockedReconnecting,
            resolveMainNetworkCondition(AppStatus.Reconnecting, evidence),
        )
    }
}
