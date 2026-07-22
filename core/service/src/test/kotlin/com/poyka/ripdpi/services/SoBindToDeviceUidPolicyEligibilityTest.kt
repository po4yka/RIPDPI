package com.poyka.ripdpi.services

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor

class SoBindToDeviceUidPolicyEligibilityTest {
    @Test
    fun `android 9 is ineligible without probing`() {
        var probeCalls = 0
        val eligibility =
            SoBindToDeviceUidPolicyEligibility.forTest(
                sdkInt = Build.VERSION_CODES.P,
                kernelRelease = "6.1.99-android14-11-gki",
                probe = {
                    probeCalls += 1
                    true
                },
            )

        assertFalse(eligibility.isEligible())
        assertTrue("API 28 must not perform the capability probe", probeCalls == 0)
    }

    @Test
    fun `android 10 accepts a successful capability probe on an old kernel`() {
        val eligibility = eligibility(Build.VERSION_CODES.Q, "4.19.157-android10", probeResult = true)

        assertTrue(eligibility.isEligible())
    }

    @Test
    fun `android 10 rejects a failed capability probe on an old kernel`() {
        val eligibility = eligibility(Build.VERSION_CODES.Q, "4.19.157-android10", probeResult = false)

        assertFalse(eligibility.isEligible())
    }

    @Test
    fun `android 10 accepts kernel 5_7 when the probe fails`() {
        val eligibility = eligibility(Build.VERSION_CODES.Q, "5.7.0-android", probeResult = false)

        assertTrue(eligibility.isEligible())
    }

    @Test
    fun `android 10 rejects kernel 5_6 when the probe fails`() {
        val eligibility = eligibility(Build.VERSION_CODES.Q, "5.6.19-android", probeResult = false)

        assertFalse(eligibility.isEligible())
    }

    @Test
    fun `android 12 accepts an unreadable kernel when the probe fails`() {
        val eligibility = eligibility(Build.VERSION_CODES.S, "not-a-kernel-release", probeResult = false)

        assertTrue(eligibility.isEligible())
    }

    @Test
    fun `android 11 rejects missing and malformed kernel releases when the probe fails`() {
        listOf(null, "", "not-a-kernel-release").forEach { release ->
            assertFalse(eligibility(Build.VERSION_CODES.R, release, probeResult = false).isEligible())
        }
    }

    @Test
    fun `android 12 accepts missing blank and malformed kernel releases when the probe fails`() {
        listOf(null, "", "not-a-kernel-release").forEach { release ->
            assertTrue(eligibility(Build.VERSION_CODES.S, release, probeResult = false).isEligible())
        }
    }

    @Test
    fun `android 10 accepts a newer kernel when the probe fails`() {
        assertTrue(eligibility(Build.VERSION_CODES.Q, "6.1.99-android14-11-gki", probeResult = false).isEligible())
    }

    @Test
    fun `kernel parser rejects a version with leading garbage`() {
        assertFalse(eligibility(Build.VERSION_CODES.R, "release-6.1.99-android", probeResult = false).isEligible())
    }

    @Test
    fun `capability decision is cached for the process singleton`() {
        var probeCalls = 0
        val eligibility =
            SoBindToDeviceUidPolicyEligibility.forTest(
                sdkInt = Build.VERSION_CODES.Q,
                kernelRelease = "4.19.157-android10",
                probe = {
                    probeCalls += 1
                    true
                },
            )

        assertTrue(eligibility.isEligible())
        assertTrue(eligibility.isEligible())
        assertTrue("SO_BINDTODEVICE must be probed once", probeCalls == 1)
    }

    @Test
    fun `capability probe closes its socket after a bind error`() {
        val socket = FileDescriptor()
        var closedSocket: FileDescriptor? = null

        val succeeded =
            probeUnprivilegedBindToDevice(
                openSocket = { socket },
                bindSocket = { throw IllegalStateException("denied") },
                closeSocket = { closedSocket = it },
            )

        assertFalse(succeeded)
        assertEquals(socket, closedSocket)
    }

    @Test
    fun `capability probe closes its socket exactly once after a successful bind`() {
        val socket = FileDescriptor()
        var closeCalls = 0

        val succeeded =
            probeUnprivilegedBindToDevice(
                openSocket = { socket },
                bindSocket = {},
                closeSocket = { closeCalls += 1 },
            )

        assertTrue(succeeded)
        assertEquals(1, closeCalls)
    }

    @Test
    fun `capability probe rejects a successful bind when closing fails`() {
        val socket = FileDescriptor()

        val succeeded =
            probeUnprivilegedBindToDevice(
                openSocket = { socket },
                bindSocket = {},
                closeSocket = { throw IllegalStateException("close failed") },
            )

        assertFalse(succeeded)
    }

    @Test
    fun `capability probe treats an open error as unsupported`() {
        var bindCalls = 0
        var closeCalls = 0

        val succeeded =
            probeUnprivilegedBindToDevice(
                openSocket = { throw IllegalStateException("socket unavailable") },
                bindSocket = { bindCalls += 1 },
                closeSocket = { closeCalls += 1 },
            )

        assertFalse(succeeded)
        assertEquals(0, bindCalls)
        assertEquals(0, closeCalls)
    }

    private fun eligibility(
        sdkInt: Int,
        kernelRelease: String?,
        probeResult: Boolean,
    ): SoBindToDeviceUidPolicyEligibility =
        SoBindToDeviceUidPolicyEligibility.forTest(sdkInt, kernelRelease) { probeResult }
}
