package com.poyka.ripdpi.services

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SoBindToDeviceUidPolicyEligibilityTest {
    @Test
    fun `android 9 is ineligible and marks the probe not supported without running it`() {
        var probeCalls = 0
        val eligibility =
            SoBindToDeviceUidPolicyEligibility.forTest(
                sdkInt = Build.VERSION_CODES.P,
                kernelRelease = "6.1.99-android14-11-gki",
                probe = {
                    probeCalls += 1
                    BindToDeviceProbeOutcome.Supported
                },
            )

        assertFalse(eligibility.isEligible())
        assertEquals("6.1", eligibility.qualification().kernelMajorMinorBand)
        assertEquals("not_supported", eligibility.qualification().unprivilegedBindToDevice)
        assertEquals(0, probeCalls)
    }

    @Test
    fun `android 10 accepts a successful capability probe on an old kernel`() {
        val eligibility = eligibility(Build.VERSION_CODES.Q, "4.19.157-android10", BindToDeviceProbeOutcome.Supported)

        assertTrue(eligibility.isEligible())
        assertEquals("4.19", eligibility.qualification().kernelMajorMinorBand)
        assertEquals("supported", eligibility.qualification().unprivilegedBindToDevice)
    }

    @Test
    fun `android 10 rejects a denied capability probe on an old kernel`() {
        val eligibility =
            eligibility(Build.VERSION_CODES.Q, "4.19.157-android10", BindToDeviceProbeOutcome.PermissionDenied)

        assertFalse(eligibility.isEligible())
        assertEquals("permission_denied", eligibility.qualification().unprivilegedBindToDevice)
    }

    @Test
    fun `permission denied is authoritative on android 12 with a modern kernel`() {
        val eligibility =
            eligibility(Build.VERSION_CODES.S, "6.1.99-android14", BindToDeviceProbeOutcome.PermissionDenied)

        assertFalse(eligibility.isEligible())
        assertEquals("6.1", eligibility.qualification().kernelMajorMinorBand)
        assertEquals("permission_denied", eligibility.qualification().unprivilegedBindToDevice)
    }

    @Test
    fun `JNI bridge failure refuses policy qualification on android 12 with a modern kernel`() {
        val eligibility = eligibility(Build.VERSION_CODES.S, "6.1.99-android14", BindToDeviceProbeOutcome.BridgeFailure)

        assertThrows(IllegalStateException::class.java) { eligibility.isEligible() }
        assertEquals("bridge_failure", eligibility.qualification().unprivilegedBindToDevice)
    }

    @Test
    fun `native panic sentinel maps to bridge failure`() {
        assertEquals(BindToDeviceProbeOutcome.BridgeFailure, (-1).toProbeOutcome())
    }

    @Test
    fun `android 10 accepts kernel 5_7 when the probe is unavailable`() {
        val eligibility = eligibility(Build.VERSION_CODES.Q, "5.7.0-android", BindToDeviceProbeOutcome.Unavailable)

        assertTrue(eligibility.isEligible())
        assertEquals("5.7", eligibility.qualification().kernelMajorMinorBand)
    }

    @Test
    fun `android 10 rejects kernel 5_6 when the probe is unavailable`() {
        assertFalse(
            eligibility(Build.VERSION_CODES.Q, "5.6.19-android", BindToDeviceProbeOutcome.Unavailable).isEligible(),
        )
    }

    @Test
    fun `android 12 accepts an unreadable kernel when the probe is unavailable`() {
        val eligibility =
            eligibility(Build.VERSION_CODES.S, "not-a-kernel-release", BindToDeviceProbeOutcome.Unavailable)

        assertTrue(eligibility.isEligible())
        assertEquals("unknown", eligibility.qualification().kernelMajorMinorBand)
    }

    @Test
    fun `android 11 rejects missing and malformed kernel releases when the probe is unavailable`() {
        listOf(null, "", "not-a-kernel-release").forEach { release ->
            assertFalse(eligibility(Build.VERSION_CODES.R, release, BindToDeviceProbeOutcome.Unavailable).isEligible())
        }
    }

    @Test
    fun `android 12 accepts missing blank and malformed kernel releases when the probe is unavailable`() {
        listOf(null, "", "not-a-kernel-release").forEach { release ->
            assertTrue(eligibility(Build.VERSION_CODES.S, release, BindToDeviceProbeOutcome.Unavailable).isEligible())
        }
    }

    @Test
    fun `kernel parser rejects a version with leading garbage`() {
        val eligibility =
            eligibility(Build.VERSION_CODES.R, "release-6.1.99-android", BindToDeviceProbeOutcome.Unavailable)

        assertFalse(eligibility.isEligible())
        assertEquals("unknown", eligibility.qualification().kernelMajorMinorBand)
    }

    @Test
    fun `capability evidence is cached for the process singleton`() {
        var probeCalls = 0
        val eligibility =
            SoBindToDeviceUidPolicyEligibility.forTest(
                sdkInt = Build.VERSION_CODES.Q,
                kernelRelease = "4.19.157-android10",
                probe = {
                    probeCalls += 1
                    BindToDeviceProbeOutcome.Supported
                },
            )

        assertTrue(eligibility.isEligible())
        assertEquals("supported", eligibility.qualification().unprivilegedBindToDevice)
        assertEquals(1, probeCalls)
    }

    @Test
    fun `unexpected native probe failure is not disguised as unavailable`() {
        val eligibility =
            SoBindToDeviceUidPolicyEligibility.forTest(
                sdkInt = Build.VERSION_CODES.Q,
                kernelRelease = "6.1.99-android14",
                probe = { throw IllegalStateException("contained native failure") },
            )

        assertThrows(IllegalStateException::class.java) { eligibility.qualification() }
    }

    @Test
    fun `privacy projection rejects raw kernel and arbitrary probe values`() {
        val safe =
            RemoteDeviceUidPolicyQualification(
                kernelMajorMinorBand = "6.1.99-android14-secret",
                unprivilegedBindToDevice = "errno=13 interface=wlan0",
                uidResolvedCount = -1,
                uidUnresolvedCount = -2,
                policyDecisionDeniedTcpCount = -3,
                policyDecisionDeniedUdpCount = -4,
                policyDecisionDeniedOtherCount = -5,
            ).privacySafe()

        assertEquals("unknown", safe.kernelMajorMinorBand)
        assertEquals("unavailable", safe.unprivilegedBindToDevice)
        assertEquals(0, safe.uidResolvedCount)
        assertEquals(0, safe.uidUnresolvedCount)
        assertEquals(0, safe.policyDecisionDeniedTcpCount)
        assertEquals(0, safe.policyDecisionDeniedUdpCount)
        assertEquals(0, safe.policyDecisionDeniedOtherCount)
    }

    private fun eligibility(
        sdkInt: Int,
        kernelRelease: String?,
        probeOutcome: BindToDeviceProbeOutcome,
    ): SoBindToDeviceUidPolicyEligibility =
        SoBindToDeviceUidPolicyEligibility.forTest(sdkInt, kernelRelease) { probeOutcome }
}
